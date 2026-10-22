package com.voztranslate.app.data

import android.content.Context
import com.voztranslate.app.domain.SessionRepository
import com.voztranslate.app.domain.SessionStorageRepository
import java.io.File

object SessionRepositoryProvider {
    @Volatile
    private var repository: RoomSessionRepository? = null

    fun get(context: Context): SessionRepository = getRoomRepository(context)

    fun getStorage(context: Context): SessionStorageRepository = getRoomRepository(context)

    private fun getRoomRepository(context: Context): RoomSessionRepository = repository ?: synchronized(this) {
        repository ?: RoomSessionRepository(
            database = VozTranslateDatabase.get(context.applicationContext),
            recordingsDirectory = File(context.applicationContext.filesDir, "recordings"),
        ).also { repository = it }
    }
}
