package com.voztranslate.app.ai

import ai.moonshine.voice.JNI
import android.content.Context
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers

/** JNI implementation backed by the ONNX Runtime already loaded from Moonshine Voice 0.1.5. */
internal class M2m100JniInferencePort private constructor(
    handle: Long,
) : M2m100InferencePort {
    private val nativeLock = Any()
    private var nativeHandle = handle

    override fun translate(request: M2m100InferenceRequest): String =
        translate(request, AtomicBoolean(false))

    override fun translate(request: M2m100InferenceRequest, cancelled: AtomicBoolean): String =
        synchronized(nativeLock) {
            check(nativeHandle != 0L) { "El port nativo M2M100 ya está cerrado." }
            nativeTranslate(
                nativeHandle,
                request.text,
                request.sourceLanguageTokenId,
                request.targetLanguageTokenId,
                request.decoderStartTokenId,
                request.forcedFirstGeneratedTokenId,
                request.eosTokenId,
                request.maxLength,
                cancelled,
            )
        }

    override fun close() {
        synchronized(nativeLock) {
            val handle = nativeHandle
            if (handle == 0L) return
            nativeHandle = 0L
            nativeDestroy(handle)
        }
    }

    private external fun nativeTranslate(
        handle: Long,
        text: String,
        sourceLanguageTokenId: Int,
        targetLanguageTokenId: Int,
        decoderStartTokenId: Int,
        forcedFirstGeneratedTokenId: Int,
        eosTokenId: Int,
        maxLength: Int,
        cancelled: AtomicBoolean,
    ): String

    private external fun nativeDestroy(handle: Long)

    companion object {
        @Volatile
        private var libraryLoaded = false

        /** Full hashes are checked at load time; a size-only WorkManager receipt is insufficient. */
        suspend fun load(context: Context): M2m100JniInferencePort =
            createOwnedResourceOnDispatcher(Dispatchers.IO, create = {
                val root = M2m100TranslationModelManager.modelDirectory(context.applicationContext)
                if (!M2m100ArtifactVerifier.isReady(root, M2m100ModelArtifacts.files)) {
                    throw IOException("El modelo M2M100 no está preparado; verifica/descarga sus archivos primero.")
                }
                if (!M2m100ArtifactVerifier.verify(root, M2m100ModelArtifacts.files)) {
                    throw IOException("La verificación SHA-256 del modelo M2M100 falló.")
                }

                // Moonshine's JNI load brings its own libonnxruntime.so into the app linker namespace.
                JNI.ensureLibraryLoaded()
                loadNativeLibraryOnce()
                M2m100JniInferencePort(nativeCreate(root.absolutePath))
            })

        @Synchronized
        private fun loadNativeLibraryOnce() {
            if (libraryLoaded) return
            System.loadLibrary("voztranslate_m2m100")
            libraryLoaded = true
        }

        @JvmStatic
        private external fun nativeCreate(modelRoot: String): Long
    }
}
