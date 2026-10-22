package com.voztranslate.app.ai

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TranscriptEvent
import android.content.Context
import com.voztranslate.app.domain.AudioFrame
import com.voztranslate.app.domain.Language
import com.voztranslate.app.domain.SpeechEngine
import com.voztranslate.app.domain.TranscriptSegment
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn

/** Adapts the single captured PCM stream to Moonshine's native streaming API. */
internal class MoonshineSpeechEngine(
    private val transcriber: MoonshineTranscriberPort,
    private val sourceLanguage: Language,
    @Volatile private var sessionStartedAtMillis: Long,
) : ManagedSpeechEngine {
    private val used = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun transcribe(frames: Flow<AudioFrame>, sourceLanguage: Language): Flow<TranscriptSegment> {
        require(sourceLanguage == this.sourceLanguage) { "El idioma no coincide con el modelo Moonshine cargado." }
        check(!closed.get()) { "El motor Moonshine ya está cerrado." }
        check(used.compareAndSet(false, true)) { "Este motor Moonshine solo admite una sesión de streaming." }

        return channelFlow {
            val stream = transcriber.createStream()
            check(stream >= 0) { "Moonshine no pudo crear el flujo de transcripción." }
            val failure = AtomicReference<Throwable?>(null)
            val lineStates = ConcurrentHashMap<Long, EmittedLineState>()
            val listener = Consumer<TranscriptEvent> { event ->
                if (event is TranscriptEvent.Error) {
                    failure.compareAndSet(
                        null,
                        event.cause ?: IOException("Moonshine informó un error de transcripción."),
                    )
                    return@Consumer
                }

                val line = when (event) {
                    is TranscriptEvent.LineStarted -> event.line
                    is TranscriptEvent.LineUpdated -> event.line
                    is TranscriptEvent.LineTextChanged -> event.line
                    is TranscriptEvent.LineCompleted -> event.line
                    // Speaker changes are ignored until the independent diarization phase.
                    is TranscriptEvent.LineSpeakersChanged, is TranscriptEvent.Error -> null
                    else -> null
                } ?: return@Consumer

                val text = line.text.orEmpty()
                if (text.isBlank()) return@Consumer
                val isFinal = event is TranscriptEvent.LineCompleted || line.isComplete
                val prior = lineStates[line.id]
                if (prior != null && prior.text == text && prior.isFinal == isFinal) return@Consumer
                val revision = (prior?.revision ?: -1L) + 1L
                lineStates[line.id] = EmittedLineState(text, revision, isFinal)

                val startOffset = secondsToMillis(line.startTime)
                val endOffset = safeAdd(startOffset, secondsToMillis(line.duration))
                val segment = TranscriptSegment(
                    id = line.id.toString(),
                    revision = revision,
                    startedAtMillis = safeAdd(sessionStartedAtMillis, startOffset),
                    endedAtMillis = endOffset.takeIf { isFinal }?.let { safeAdd(sessionStartedAtMillis, it) },
                    sourceLanguage = this@MoonshineSpeechEngine.sourceLanguage,
                    sourceText = text,
                    translatedText = null,
                    speakerId = null,
                    isFinal = isFinal,
                )
                val delivery = trySendBlocking(segment)
                if (delivery.isFailure) {
                    failure.compareAndSet(
                        null,
                        delivery.exceptionOrNull() ?: CancellationException("Se cerró el consumidor de transcripción."),
                    )
                }
            }

            var listenerRegistered = false
            try {
                transcriber.addListener(listener)
                listenerRegistered = true
                transcriber.startStream(stream)
                frames.collect { frame ->
                    failure.get()?.let { throw it }
                    require(frame.sampleRateHz == SAMPLE_RATE_HZ) {
                        "Moonshine requiere PCM a ${SAMPLE_RATE_HZ} Hz; se recibió ${frame.sampleRateHz} Hz."
                    }
                    if (frame.samples.isNotEmpty()) {
                        val samples = FloatArray(frame.samples.size) { index ->
                            frame.samples[index].toFloat() / PCM16_SCALE
                        }
                        transcriber.addAudioToStream(stream, samples, frame.sampleRateHz)
                        failure.get()?.let { throw it }
                    }
                }
                transcriber.stopStream(stream)
                failure.get()?.let { throw it }
            } finally {
                if (listenerRegistered) transcriber.removeListener(listener)
                transcriber.freeStream(stream)
            }
        }
            // Native callbacks are synchronous with addAudioToStream; they send into this
            // bounded buffer while the downstream Room/UI collector consumes them concurrently.
            .buffer(TRANSCRIPT_BUFFER_CAPACITY, BufferOverflow.SUSPEND)
            .flowOn(Dispatchers.Default)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) transcriber.close()
    }

    override fun setSessionStartedAtMillis(value: Long) {
        check(!used.get()) { "El ancla de tiempo debe fijarse antes de iniciar la transcripción." }
        sessionStartedAtMillis = value
    }

    private data class EmittedLineState(val text: String, val revision: Long, val isFinal: Boolean)

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        private const val PCM16_SCALE = 32_768f
        private const val TRANSCRIPT_BUFFER_CAPACITY = 32

        suspend fun load(
            context: Context,
            language: Language,
            sessionStartedAtMillis: Long,
        ): MoonshineSpeechEngine = createOwnedResourceOnDispatcher(
            dispatcher = Dispatchers.Default,
            create = {
                if (!MoonshineModelManager.isPrepared(context, language)) {
                    throw IOException("Prepara el modelo Tiny Streaming de ${language.displayName} antes de iniciar la captura.")
                }
                val options = if (language == Language.MANDARIN) {
                    listOf(TranscriberOption("max_tokens_per_second", "13.0"))
                } else {
                    emptyList()
                }
                val nativeTranscriber = Transcriber(options)
                try {
                    nativeTranscriber.loadFromFiles(
                        MoonshineModelManager.modelDirectory(context, language).absolutePath,
                        JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING,
                    )
                    MoonshineSpeechEngine(
                        transcriber = NativeMoonshineTranscriberPort(nativeTranscriber),
                        sourceLanguage = language,
                        sessionStartedAtMillis = sessionStartedAtMillis,
                    )
                } catch (error: Throwable) {
                    nativeTranscriber.close()
                    throw error
                }
            },
        )
    }
}

internal interface ManagedSpeechEngine : SpeechEngine, AutoCloseable {
    fun setSessionStartedAtMillis(value: Long)
}

internal fun interface RecordingSpeechEngineFactory {
    suspend fun load(context: Context, language: Language): ManagedSpeechEngine
}

/** Replaceable factory seam for pipeline tests; production uses the pinned Moonshine adapter. */
internal object RecordingSpeechEngineFactoryProvider {
    @Volatile
    var factory: RecordingSpeechEngineFactory = RecordingSpeechEngineFactory { context, language ->
        MoonshineSpeechEngine.load(context, language, 0L)
    }
}

/** Native boundary kept injectable so JVM tests can drive streaming events without loading JNI. */
internal interface MoonshineTranscriberPort : AutoCloseable {
    fun createStream(): Int
    fun startStream(stream: Int)
    fun addAudioToStream(stream: Int, samples: FloatArray, sampleRateHz: Int)
    fun stopStream(stream: Int)
    fun freeStream(stream: Int)
    fun addListener(listener: Consumer<TranscriptEvent>)
    fun removeListener(listener: Consumer<TranscriptEvent>)
}

private class NativeMoonshineTranscriberPort(
    private val delegate: Transcriber,
) : MoonshineTranscriberPort {
    override fun createStream(): Int = delegate.createStream()
    override fun startStream(stream: Int) = delegate.startStream(stream)
    override fun addAudioToStream(stream: Int, samples: FloatArray, sampleRateHz: Int) =
        delegate.addAudioToStream(stream, samples, sampleRateHz)
    override fun stopStream(stream: Int) = delegate.stopStream(stream)
    override fun freeStream(stream: Int) = delegate.freeStream(stream)
    override fun addListener(listener: Consumer<TranscriptEvent>) = delegate.addListener(listener)
    override fun removeListener(listener: Consumer<TranscriptEvent>) = delegate.removeListener(listener)
    override fun close() = delegate.close()
}

private fun secondsToMillis(seconds: Float): Long {
    val milliseconds = seconds.toDouble() * 1_000.0
    return if (milliseconds.isFinite() && milliseconds > 0.0) milliseconds.roundToLong() else 0L
}

private fun safeAdd(left: Long, right: Long): Long =
    if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
