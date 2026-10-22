package com.voztranslate.app.domain

interface TranslationEngine {
    suspend fun translate(
        text: String,
        sourceLanguage: Language,
        targetLanguage: Language,
    ): TranslationResult
}
