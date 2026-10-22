package com.voztranslate.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingSessionDao {
    @Query("SELECT * FROM recording_sessions ORDER BY started_at_millis DESC")
    fun observeAll(): Flow<List<RecordingSessionEntity>>

    @Query("SELECT * FROM recording_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getById(sessionId: String): RecordingSessionEntity?

    @Query("SELECT * FROM recording_sessions WHERE status = 'IN_PROGRESS'")
    suspend fun getInProgress(): List<RecordingSessionEntity>

    @Query("SELECT * FROM recording_sessions WHERE status = 'INTERRUPTED'")
    suspend fun getInterrupted(): List<RecordingSessionEntity>

    @Query("SELECT * FROM recording_sessions")
    suspend fun getAllSessions(): List<RecordingSessionEntity>

    @Query("SELECT COUNT(*) FROM recording_sessions")
    suspend fun countAllSessions(): Int

    @Query("DELETE FROM recording_sessions WHERE id IN (:sessionIds) AND status != 'IN_PROGRESS'")
    suspend fun deleteTerminalSessions(sessionIds: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: RecordingSessionEntity)

    @Query(
        "UPDATE recording_sessions " +
            "SET ended_at_millis = :endedAtMillis, status = :terminalStatus " +
            "WHERE id = :sessionId AND status = 'IN_PROGRESS'",
    )
    suspend fun finishIfInProgress(sessionId: String, endedAtMillis: Long, terminalStatus: String): Int

    @Query("SELECT * FROM transcript_segments WHERE session_id = :sessionId ORDER BY started_at_millis, id")
    fun observeSegments(sessionId: String): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE session_id = :sessionId AND id = :segmentId LIMIT 1")
    suspend fun getSegment(sessionId: String, segmentId: String): TranscriptSegmentEntity?

    @Query(
        "SELECT * FROM transcript_segments " +
            "WHERE session_id = :sessionId AND is_final = 1 AND translated_text IS NULL " +
            "ORDER BY started_at_millis, id LIMIT :limit",
    )
    suspend fun getPendingFinalTranslations(sessionId: String, limit: Int): List<TranscriptSegmentEntity>

    @Query(
        "SELECT COUNT(*) FROM transcript_segments " +
            "WHERE session_id = :sessionId AND is_final = 1 AND translated_text IS NULL",
    )
    suspend fun countPendingFinalTranslations(sessionId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegment(segment: TranscriptSegmentEntity)

    @Update
    suspend fun updateSegment(segment: TranscriptSegmentEntity)
}
