package com.voztranslate.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.voztranslate.app.domain.Language
import com.voztranslate.app.domain.SessionStatus

@Entity(
    tableName = "recording_sessions",
    indices = [Index(value = ["started_at_millis"])],
)
data class RecordingSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "started_at_millis") val startedAtMillis: Long,
    @ColumnInfo(name = "ended_at_millis") val endedAtMillis: Long?,
    @ColumnInfo(name = "source_language") val sourceLanguage: Language,
    @ColumnInfo(name = "target_language") val targetLanguage: Language,
    @ColumnInfo(name = "audio_path") val audioPath: String,
    val status: SessionStatus,
)
