package com.voztranslate.app.domain

data class TranscriptSegment(
    val id: String,
    val revision: Long,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val sourceLanguage: Language,
    val sourceText: String,
    val translatedText: String?,
    val speakerId: String?,
    val isFinal: Boolean,
)

data class SpeakerTurn(
    val speakerId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val revision: Long,
    val isFinal: Boolean,
)
