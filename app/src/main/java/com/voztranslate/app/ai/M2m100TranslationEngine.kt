package com.voztranslate.app.ai

import android.content.Context
import com.voztranslate.app.domain.Language
import com.voztranslate.app.domain.TranslationEngine
import com.voztranslate.app.domain.TranslationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

internal const val M2M100_DEFAULT_MAX_LENGTH = 200

/**
 * Token and generation parameters the native implementation needs for one M2M100 request.
 * The port owns SentencePiece tokenization, ONNX Runtime sessions, greedy decoding, cache use,
 * and removal of special tokens from its decoded result.
 */
internal data class M2m100InferenceRequest(
    val text: String,
    val sourceLanguageTokenId: Int,
    val targetLanguageTokenId: Int,
    val decoderStartTokenId: Int = M2m100TokenIds.EOS,
    val forcedFirstGeneratedTokenId: Int = targetLanguageTokenId,
    val eosTokenId: Int = M2m100TokenIds.EOS,
    /** Transformers M2M100 default generation cap, including the decoder start token. */
    val maxLength: Int = M2M100_DEFAULT_MAX_LENGTH,
)

/** Injectable boundary for a future JNI implementation and JVM fakes. */
internal interface M2m100InferencePort : AutoCloseable {
    /** Blocking native inference. Callers are dispatched away from the UI thread. */
    fun translate(request: M2m100InferenceRequest): String

    /** Ports with interruptible native loops may observe cancellation between decoder steps. */
    fun translate(request: M2m100InferenceRequest, cancelled: AtomicBoolean): String = translate(request)

    override fun close()
}

/**
 * Kotlin adapter for M2M100. A loaded inference port must be supplied explicitly; verified model
 * files alone do not enable translation until a native port successfully creates its sessions.
 */
internal class M2m100TranslationEngine(
    private val port: M2m100InferencePort,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ManagedTranslationEngine {
    private val inferenceMutex = Mutex()
    private val portLock = Any()
    @Volatile
    private var closed = false

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun translate(
        text: String,
        sourceLanguage: Language,
        targetLanguage: Language,
    ): TranslationResult {
        require(text.isNotBlank()) { "El texto de entrada para M2M100 está vacío." }
        if (sourceLanguage == targetLanguage) {
            return TranslationResult(text, sourceLanguage, targetLanguage)
        }
        val sourceTokenId = M2m100TokenIds.forLanguage(sourceLanguage)
        val targetTokenId = M2m100TokenIds.forLanguage(targetLanguage)
        val request = M2m100InferenceRequest(
            text = text,
            sourceLanguageTokenId = sourceTokenId,
            targetLanguageTokenId = targetTokenId,
        )

        val cancellation = AtomicBoolean(false)
        val job = coroutineContext[Job]
        val cancellationHandle = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) cancellation.set(true)
        }
        val translatedText = try {
            inferenceMutex.withLock {
                check(!closed) { "El motor M2M100 ya está cerrado." }
                withContext(dispatcher) {
                    coroutineContext.ensureActive()
                    synchronized(portLock) {
                        check(!closed) { "El motor M2M100 ya está cerrado." }
                        port.translate(request, cancellation)
                    }
                }
            }
        } finally {
            cancellationHandle?.dispose()
        }
        return TranslationResult(translatedText, sourceLanguage, targetLanguage)
    }

    /** Call from a worker thread; this waits for an in-flight native inference before closing. */
    override fun close() {
        synchronized(portLock) {
            if (closed) return
            closed = true
            port.close()
        }
    }

    companion object {
        /** Load only after the downloader's verified installation is present. */
        suspend fun load(
            context: Context,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): M2m100TranslationEngine = M2m100TranslationEngine(
            port = M2m100JniInferencePort.load(context),
            dispatcher = dispatcher,
        )
    }
}

/**
 * IDs from the pinned model's 128,004-entry base vocabulary plus the official FAIRSEQ language
 * order used by Transformers M2M100. The native port must encode source text as
 * `[source-language, SentencePiece pieces..., EOS]` and force the target-language token first.
 */
internal object M2m100TokenIds {
    const val EOS = 2
    const val ENGLISH = 128_022
    const val SPANISH = 128_023
    const val MANDARIN = 128_102

    fun forLanguage(language: Language): Int = when (language) {
        Language.ENGLISH -> ENGLISH
        Language.SPANISH -> SPANISH
        Language.MANDARIN -> MANDARIN
    }
}
