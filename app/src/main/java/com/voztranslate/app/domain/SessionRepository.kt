package com.voztranslate.app.domain

import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    fun observeSessions(): Flow<List<RecordingSession>>

    fun observeSegments(sessionId: String): Flow<List<TranscriptSegment>>

    suspend fun startSession(session: RecordingSession)

    suspend fun finishSession(sessionId: String, endedAtMillis: Long, status: SessionStatus)

    suspend fun saveSegment(sessionId: String, segment: TranscriptSegment)

    /** Final transcript rows whose translation has not been committed yet, oldest first. */
    suspend fun pendingFinalTranslations(sessionId: String, limit: Int): PendingTranslationBatch =
        PendingTranslationBatch(emptyList(), 0)

    /** Count of durable final transcript rows that still need translation. */
    suspend fun pendingFinalTranslationCount(sessionId: String): Int =
        pendingFinalTranslations(sessionId, limit = 1).totalCount
}

data class PendingTranslationBatch(
    val segments: List<TranscriptSegment>,
    val totalCount: Int,
)
