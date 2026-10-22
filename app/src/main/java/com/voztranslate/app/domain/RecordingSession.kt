package com.voztranslate.app.domain

data class RecordingSession(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val sourceLanguage: Language,
    val targetLanguage: Language,
    /** Absolute path within the app's private files directory. */
    val audioPath: String,
    val status: SessionStatus,
)

enum class SessionStatus {
    IN_PROGRESS,
    COMPLETED,
    INTERRUPTED,
    FAILED,
}
