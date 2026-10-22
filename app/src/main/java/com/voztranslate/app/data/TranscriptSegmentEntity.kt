package com.voztranslate.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.voztranslate.app.domain.Language

@Entity(
    tableName = "transcript_segments",
    primaryKeys = ["session_id", "id"],
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["session_id", "started_at_millis"])],
)
data class TranscriptSegmentEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    val id: String,
    val revision: Long,
    @ColumnInfo(name = "started_at_millis") val startedAtMillis: Long,
    @ColumnInfo(name = "ended_at_millis") val endedAtMillis: Long?,
    @ColumnInfo(name = "source_language") val sourceLanguage: Language,
    @ColumnInfo(name = "source_text") val sourceText: String,
    @ColumnInfo(name = "translated_text") val translatedText: String?,
    @ColumnInfo(name = "speaker_id") val speakerId: String?,
    @ColumnInfo(name = "is_final") val isFinal: Boolean,
)
