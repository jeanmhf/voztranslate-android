package com.voztranslate.app.domain

/** Storage operations for the private recordings and their Room-backed transcripts. */
interface SessionStorageRepository {
    suspend fun storageUsage(): SessionStorageUsage

    /**
     * Removes terminal session rows and private WAVs. A live session blocks the whole operation.
     * Files that cannot be removed keep their associated session and transcript rows for retry.
     */
    suspend fun deleteStoredSessions(): SessionStorageDeletionResult
}

data class SessionStorageUsage(
    val sessionCount: Int,
    val audioBytes: Long,
)

data class SessionStorageDeletionResult(
    val usage: SessionStorageUsage,
    val deletedSessionCount: Int,
    val deletedSessionIds: Set<String>,
    val deletedAudioFileCount: Int,
    val failedAudioFileCount: Int,
    val blockedByInProgressSession: Boolean,
)
