package com.voztranslate.app.ai

import android.content.Context
import com.voztranslate.app.domain.Language
import com.voztranslate.app.domain.TranslationEngine

/** Translation engines owned by a single capture session and closed when that session drains. */
internal interface ManagedTranslationEngine : TranslationEngine, AutoCloseable

internal fun interface RecordingTranslationEngineFactory {
    suspend fun load(
        context: Context,
        sourceLanguage: Language,
        targetLanguage: Language,
    ): ManagedTranslationEngine
}

/** Injectable production boundary so service tests can supply a deterministic translation engine. */
internal object RecordingTranslationEngineFactoryProvider {
    @Volatile
    var factory: RecordingTranslationEngineFactory = RecordingTranslationEngineFactory { context, _, _ ->
        M2m100TranslationEngine.load(context.applicationContext)
    }
}
