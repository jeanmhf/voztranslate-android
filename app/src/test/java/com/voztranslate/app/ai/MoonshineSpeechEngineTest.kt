package com.voztranslate.app.ai

import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptLine
import com.voztranslate.app.domain.AudioFrame
import com.voztranslate.app.domain.Language
import com.voztranslate.app.domain.TranscriptSegment
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineSpeechEngineTest {
    @Test
    fun convertsPcm16AndKeepsConfiguredLanguageAndStableSegmentRevisions() = runBlocking {
        val transcriber = FakeMoonshineTranscriber().apply {
            onAddAudio = { _, _ ->
                emit(lineEvent(TranscriptEvent.LineStarted::class.java, line(42L, "hel", 1.25f, 0.4f)))
                emit(lineEvent(TranscriptEvent.LineTextChanged::class.java, line(42L, "hello", 1.25f, 0.6f)))
                emit(lineEvent(TranscriptEvent.LineCompleted::class.java, line(42L, "hello", 1.25f, 0.75f, complete = true)))
            }
        }
        val engine = MoonshineSpeechEngine(transcriber, Language.SPANISH, sessionStartedAtMillis = 10_000L)
        val audio = AudioFrame(
            samples = shortArrayOf(Short.MIN_VALUE, -16_384, 0, 16_384, Short.MAX_VALUE),
            sampleRateHz = MoonshineSpeechEngine.SAMPLE_RATE_HZ,
            capturedAtNanos = 99L,
            sequenceNumber = 0L,
        )

        val segments = engine.transcribe(flowOf(audio), Language.SPANISH).toList()

        assertEquals(listOf(42), transcriber.createdStreams)
        assertEquals(listOf(42), transcriber.startedStreams)
        assertEquals(listOf(42), transcriber.stoppedStreams)
        assertEquals(listOf(42), transcriber.freedStreams)
        assertEquals(1, transcriber.capturedAudio.size)
        assertEquals(MoonshineSpeechEngine.SAMPLE_RATE_HZ, transcriber.capturedAudio.single().sampleRateHz)
        assertFloatArrayEquals(
            floatArrayOf(-1.0f, -0.5f, 0.0f, 0.5f, Short.MAX_VALUE.toFloat() / 32_768.0f),
            transcriber.capturedAudio.single().samples,
        )
        assertEquals(3, segments.size)
        assertEquals(listOf("42", "42", "42"), segments.map(TranscriptSegment::id))
        assertEquals(listOf(0L, 1L, 2L), segments.map(TranscriptSegment::revision))
        assertEquals(listOf("hel", "hello", "hello"), segments.map(TranscriptSegment::sourceText))
        assertEquals(listOf(false, false, true), segments.map(TranscriptSegment::isFinal))
        assertEquals(listOf(Language.SPANISH, Language.SPANISH, Language.SPANISH), segments.map(TranscriptSegment::sourceLanguage))
        assertEquals(11_250L, segments.first().startedAtMillis)
        assertEquals(12_000L, segments.last().endedAtMillis)
        assertTrue(transcriber.removedListeners.single() === transcriber.addedListeners.single())
        engine.close()
        assertEquals(1, transcriber.closeCount)
    }

    @Test
    fun stopStreamCanEmitTheFinalTranscriptAndDuplicateCallbacksDoNotCreateRevisions() = runBlocking {
        val transcriber = FakeMoonshineTranscriber().apply {
            onAddAudio = { _, _ ->
                val partial = line(7L, "hola", 0.2f, 0.3f)
                emit(lineEvent(TranscriptEvent.LineTextChanged::class.java, partial))
                emit(lineEvent(TranscriptEvent.LineUpdated::class.java, partial))
            }
            onStopStream = {
                emit(lineEvent(TranscriptEvent.LineCompleted::class.java, line(7L, "hola", 0.2f, 0.5f, complete = true)))
            }
        }
        val engine = MoonshineSpeechEngine(transcriber, Language.SPANISH, sessionStartedAtMillis = 0L)

        val segments = engine.transcribe(flowOf(frame(0)), Language.SPANISH).toList()

        assertEquals(listOf(0L, 1L), segments.map(TranscriptSegment::revision))
        assertEquals(listOf(false, true), segments.map(TranscriptSegment::isFinal))
        assertEquals("hola", segments.last().sourceText)
        assertEquals(700L, segments.last().endedAtMillis)
        assertEquals(listOf(42), transcriber.stoppedStreams)
        engine.close()
    }

    @Test
    fun rejectsWrongSelectedLanguageBeforeOpeningNativeStream() {
        val transcriber = FakeMoonshineTranscriber()
        val engine = MoonshineSpeechEngine(transcriber, Language.ENGLISH, sessionStartedAtMillis = 0L)

        val error = assertThrows(IllegalArgumentException::class.java) {
            engine.transcribe(flowOf(frame(0)), Language.MANDARIN)
        }

        assertTrue(error.message.orEmpty().contains("idioma", ignoreCase = true))
        assertTrue(transcriber.createdStreams.isEmpty())
        engine.close()
    }

    @Test
    fun preservesEachSupportedModelLanguageOnFinalSegments() = runBlocking {
        Language.values().forEach { language ->
            val transcriber = FakeMoonshineTranscriber().apply {
                onStopStream = {
                    emit(lineEvent(TranscriptEvent.LineCompleted::class.java, line(1L, "ok", 0f, 0.1f, complete = true)))
                }
            }
            val engine = MoonshineSpeechEngine(transcriber, language, sessionStartedAtMillis = 0L)

            val result = engine.transcribe(emptyFlow(), language).single()

            assertEquals(language, result.sourceLanguage)
            engine.close()
        }
    }

    @Test
    fun rejectsUnexpectedSampleRateAndReleasesListenerAndStream() = runBlocking {
        val transcriber = FakeMoonshineTranscriber()
        val engine = MoonshineSpeechEngine(transcriber, Language.ENGLISH, sessionStartedAtMillis = 0L)

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.transcribe(flowOf(frame(0, sampleRateHz = 48_000)), Language.ENGLISH).toList() }
        }

        assertTrue(error.message.orEmpty().contains("16000"))
        assertEquals(listOf(42), transcriber.freedStreams)
        assertEquals(1, transcriber.removedListeners.size)
        engine.close()
    }

    @Test
    fun reportsNativeCallbackErrorAndReleasesListenerAndStream() = runBlocking {
        val nativeFailure = IOException("native ASR failure")
        val transcriber = FakeMoonshineTranscriber().apply {
            onAddAudio = { _, _ -> emit(TranscriptEvent.Error(nativeFailure, 42)) }
        }
        val engine = MoonshineSpeechEngine(transcriber, Language.ENGLISH, sessionStartedAtMillis = 0L)

        val error = assertThrows(IOException::class.java) {
            runBlocking { engine.transcribe(flowOf(frame(0)), Language.ENGLISH).toList() }
        }

        assertEquals(nativeFailure.javaClass, error.javaClass)
        assertEquals(nativeFailure.message, error.message)
        var cause: Throwable? = error
        var originalFailurePreserved = false
        while (cause != null) {
            if (cause === nativeFailure) {
                originalFailurePreserved = true
                break
            }
            cause = cause.cause
        }
        assertTrue("the native IOException should remain in the recovered cause chain", originalFailurePreserved)
        assertEquals(1, transcriber.removedListeners.size)
        assertEquals(listOf(42), transcriber.freedStreams)
        // The recording owner calls close() in its NonCancellable cleanup, after collection fails.
        engine.close()
        assertEquals(1, transcriber.closeCount)
    }

    @Test
    fun cancellationReleasesListenerAndStreamAndOwnerCanCloseTranscriber() = runBlocking {
        val transcriber = FakeMoonshineTranscriber()
        val engine = MoonshineSpeechEngine(transcriber, Language.ENGLISH, sessionStartedAtMillis = 0L)
        val started = CompletableDeferred<Unit>()
        transcriber.onStartStream = { started.complete(Unit) }
        val collector = launch(Dispatchers.Default + SupervisorJob()) {
            engine.transcribe(flow { awaitCancellation() }, Language.ENGLISH).collect()
        }

        withTimeout(5_000) { started.await() }
        collector.cancelAndJoin()

        assertEquals(1, transcriber.removedListeners.size)
        assertEquals(listOf(42), transcriber.freedStreams)
        // The service owns the model/transcriber lifetime and closes it in its cleanup path.
        engine.close()
        assertEquals(1, transcriber.closeCount)
    }

    @Test
    fun engineCloseIsIdempotentAndPreventsStartingAnotherSession() {
        val transcriber = FakeMoonshineTranscriber()
        val engine = MoonshineSpeechEngine(transcriber, Language.ENGLISH, sessionStartedAtMillis = 0L)

        engine.close()
        engine.close()

        assertEquals(1, transcriber.closeCount)
        assertThrows(IllegalStateException::class.java) {
            engine.transcribe(flowOf(frame(0)), Language.ENGLISH)
        }
    }

    private fun frame(sequence: Long, sampleRateHz: Int = MoonshineSpeechEngine.SAMPLE_RATE_HZ) = AudioFrame(
        samples = shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE),
        sampleRateHz = sampleRateHz,
        capturedAtNanos = sequence * 10_000_000L,
        sequenceNumber = sequence,
    )

    private fun line(
        id: Long,
        text: String,
        startTime: Float,
        duration: Float,
        complete: Boolean = false,
    ) = TranscriptLine().apply {
        this.id = id
        this.text = text
        this.startTime = startTime
        this.duration = duration
        this.isComplete = complete
    }

    private fun lineEvent(type: Class<out TranscriptEvent>, line: TranscriptLine): TranscriptEvent = when (type) {
        TranscriptEvent.LineStarted::class.java -> TranscriptEvent.LineStarted(line, 42)
        TranscriptEvent.LineUpdated::class.java -> TranscriptEvent.LineUpdated(line, 42)
        TranscriptEvent.LineTextChanged::class.java -> TranscriptEvent.LineTextChanged(line, 42)
        TranscriptEvent.LineCompleted::class.java -> TranscriptEvent.LineCompleted(line, 42)
        else -> error("Unsupported test event type: $type")
    }

    private fun assertFloatArrayEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index -> assertEquals(expected[index], actual[index], 0.000001f) }
    }

    private class FakeMoonshineTranscriber : MoonshineTranscriberPort {
        private val listeners = CopyOnWriteArrayList<Consumer<TranscriptEvent>>()
        val addedListeners = CopyOnWriteArrayList<Consumer<TranscriptEvent>>()
        val removedListeners = CopyOnWriteArrayList<Consumer<TranscriptEvent>>()
        val createdStreams = CopyOnWriteArrayList<Int>()
        val startedStreams = CopyOnWriteArrayList<Int>()
        val stoppedStreams = CopyOnWriteArrayList<Int>()
        val freedStreams = CopyOnWriteArrayList<Int>()
        val capturedAudio = CopyOnWriteArrayList<CapturedAudio>()
        var closeCount = 0
            private set
        var onAddAudio: ((stream: Int, samples: FloatArray) -> Unit)? = null
        var onStartStream: ((stream: Int) -> Unit)? = null
        var onStopStream: ((stream: Int) -> Unit)? = null

        override fun createStream(): Int {
            createdStreams.add(42)
            return 42
        }

        override fun startStream(stream: Int) {
            startedStreams += stream
            onStartStream?.invoke(stream)
        }

        override fun addAudioToStream(stream: Int, samples: FloatArray, sampleRateHz: Int) {
            capturedAudio += CapturedAudio(stream, samples.copyOf(), sampleRateHz)
            onAddAudio?.invoke(stream, samples)
        }

        override fun stopStream(stream: Int) {
            stoppedStreams += stream
            onStopStream?.invoke(stream)
        }

        override fun freeStream(stream: Int) {
            freedStreams += stream
        }

        override fun addListener(listener: Consumer<TranscriptEvent>) {
            listeners += listener
            addedListeners += listener
        }

        override fun removeListener(listener: Consumer<TranscriptEvent>) {
            removedListeners += listener
            listeners.remove(listener)
        }

        override fun close() {
            closeCount += 1
        }

        fun emit(event: TranscriptEvent) {
            listeners.forEach { it.accept(event) }
        }
    }

    private data class CapturedAudio(
        val stream: Int,
        val samples: FloatArray,
        val sampleRateHz: Int,
    )
}
