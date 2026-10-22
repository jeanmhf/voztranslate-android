package com.voztranslate.app.domain

data class TranslationResult(
    val text: String,
    val sourceLanguage: Language,
    val targetLanguage: Language,
)
