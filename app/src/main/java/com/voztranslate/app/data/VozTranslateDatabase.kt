package com.voztranslate.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.voztranslate.app.domain.Language
import com.voztranslate.app.domain.SessionStatus

@Database(
    entities = [RecordingSessionEntity::class, TranscriptSegmentEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(RecordingDatabaseConverters::class)
abstract class VozTranslateDatabase : RoomDatabase() {
    abstract fun recordingSessionDao(): RecordingSessionDao

    companion object {
        @Volatile
        private var instance: VozTranslateDatabase? = null

        fun get(context: Context): VozTranslateDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                VozTranslateDatabase::class.java,
                "voz_translate.db",
            ).build().also { instance = it }
        }
    }
}

class RecordingDatabaseConverters {
    @TypeConverter
    fun languageToString(value: Language): String = value.name

    @TypeConverter
    fun stringToLanguage(value: String): Language = Language.valueOf(value)

    @TypeConverter
    fun sessionStatusToString(value: SessionStatus): String = value.name

    @TypeConverter
    fun stringToSessionStatus(value: String): SessionStatus = SessionStatus.valueOf(value)
}
