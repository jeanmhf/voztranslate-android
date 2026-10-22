package com.voztranslate.app.data

import androidx.room.withTransaction
import com.voztranslate.app.domain.RecordingSession
import com.voztranslate.app.domain.PendingTranslationBatch
import com.voztranslate.app.domain.SessionRepository
import com.voztranslate.app.domain.SessionStorageDeletionResult
import com.voztranslate.app.domain.SessionStorageRepository
import com.voztranslate.app.domain.SessionStorageUsage
import com.voztranslate.app.domain.SessionStatus
import com.voztranslate.app.domain.TranscriptSegment
import com.voztranslate.app.recording.PcmPayloadPresence
import com.voztranslate.app.recording.PrivatePcmWavWriter
import com.voztranslate.app.recording.SessionOperationGate
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Room-backed session/segment storage with startup recovery for abandoned recordings. */
class RoomSessionRepository(
    private val database: VozTranslateDatabase,
    private val recordingsDirectory: File,
) : SessionRepository, SessionStorageRepository {
    private val dao = database.recordingSessionDao()
    private val startMutex = Mutex()

    override fun observeSessions(): Flow<List<RecordingSession>> =
        dao.observeAll().map { sessions -> sessions.map(RecordingSessionEntity::toDomain) }

    override fun observeSegments(sessionId: String): Flow<List<TranscriptSegment>> =
        dao.observeSegments(sessionId).map { segments -> segments.map(TranscriptSegmentEntity::toDomain) }

    override suspend fun startSession(session: RecordingSession) = withContext(Dispatchers.IO) {
        require(session.status == SessionStatus.IN_PROGRESS && session.endedAtMillis == null) {
            "Una sesión nueva debe iniciar en estado IN_PROGRESS."
        }
        require(PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, session.audioPath)) {
            "La ruta de audio debe estar dentro de la carpeta privada de grabaciones."
        }

        startMutex.withLock {
            recoverInProgressSessions()
            retryInterruptedAudioRecovery()
            database.withTransaction {
                dao.insert(session.toEntity())
            }
        }
    }

    override suspend fun finishSession(
        sessionId: String,
        endedAtMillis: Long,
        status: SessionStatus,
    ) {
        require(status != SessionStatus.IN_PROGRESS) { "El estado terminal no puede ser IN_PROGRESS." }
        withContext(Dispatchers.IO) {
            dao.finishIfInProgress(sessionId, endedAtMillis, status.name)
        }
    }

    override suspend fun saveSegment(sessionId: String, segment: TranscriptSegment) =
        withContext(Dispatchers.IO) {
            val entity = TranscriptSegmentEntity(
                sessionId = sessionId,
                id = segment.id,
                revision = segment.revision,
                startedAtMillis = segment.startedAtMillis,
                endedAtMillis = segment.endedAtMillis,
                sourceLanguage = segment.sourceLanguage,
                sourceText = segment.sourceText,
                translatedText = segment.translatedText,
                speakerId = segment.speakerId,
                isFinal = segment.isFinal,
            )
            database.withTransaction {
                val existing = dao.getSegment(sessionId, segment.id)
                when {
                    existing == null -> dao.insertSegment(entity)
                    segment.revision > existing.revision -> dao.updateSegment(entity)
                    else -> Unit // Ignore duplicate or out-of-order revisions.
                }
            }
        }

    override suspend fun pendingFinalTranslations(sessionId: String, limit: Int): PendingTranslationBatch =
        withContext(Dispatchers.IO) {
            require(limit > 0) { "El límite de lectura de traducciones pendientes debe ser positivo." }
            database.withTransaction {
                val pending = dao.getPendingFinalTranslations(sessionId, limit)
                    .map(TranscriptSegmentEntity::toDomain)
                val totalCount = dao.countPendingFinalTranslations(sessionId)
                PendingTranslationBatch(pending, totalCount)
            }
        }

    override suspend fun pendingFinalTranslationCount(sessionId: String): Int =
        withContext(Dispatchers.IO) {
            dao.countPendingFinalTranslations(sessionId)
        }

    override suspend fun storageUsage(): SessionStorageUsage = withContext(Dispatchers.IO) {
        SessionStorageUsage(
            sessionCount = dao.countAllSessions(),
            audioBytes = privateWaveFiles().sumOf(PrivateWaveFile::sizeBytes),
        )
    }

    override suspend fun deleteStoredSessions(): SessionStorageDeletionResult = withContext(Dispatchers.IO) {
        startMutex.withLock {
            // The gate is acquired before recovery or filesystem work. Capture acquires the same
            // process-wide gate before publishing STARTING, so a live or starting service cannot
            // be mistaken for an abandoned database row.
            val storageLease = SessionOperationGate.tryAcquireStorage()
            if (storageLease == null) {
                return@withLock blockedDeletionResult(storageUsage())
            }

            try {
                // With no live service holding CAPTURE, remaining IN_PROGRESS rows are abandoned
                // from an earlier process lifetime and can be terminalized before deletion.
                recoverInProgressSessions()

                val attempt = database.withTransaction {
                    val sessions = dao.getAllSessions()
                    if (sessions.any { it.status == SessionStatus.IN_PROGRESS }) {
                        return@withTransaction DeleteAttempt.Blocked
                    }

                    // Preflight all regular direct-child WAV entries before changing anything.
                    val files = privateWaveEntries()
                    val failedPaths = mutableSetOf<String>()
                    var deletedAudioFileCount = 0
                    for (entry in files) {
                        // Room and the filesystem cannot commit atomically. If a later Room
                        // operation/commit fails, the rows roll back but the WAV unlink does not;
                        // retry is safe because the missing path is treated as already removed.
                        val removed = runCatching { Files.deleteIfExists(entry.file.toPath()) }.getOrDefault(false)
                        if (removed) {
                            deletedAudioFileCount++
                        } else if (Files.exists(entry.file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            failedPaths += entry.pathKey
                        }
                    }

                    val deletableSessionIds = sessions.asSequence()
                        .filter { session ->
                            val privateFile = resolvePrivateWave(session.audioPath)
                            privateFile == null || privateFile.pathKey !in failedPaths
                        }
                        .map(RecordingSessionEntity::id)
                        .toList()
                    if (deletableSessionIds.isNotEmpty()) {
                        dao.deleteTerminalSessions(deletableSessionIds)
                    }
                    val remainingIds = dao.getAllSessions().mapTo(mutableSetOf()) { it.id }
                    val deletedSessionIds = deletableSessionIds.filterNot(remainingIds::contains).toSet()
                    val remainingAudioBytes = privateWaveFiles().sumOf(PrivateWaveFile::sizeBytes)
                    DeleteAttempt.Completed(
                        deletedSessionIds = deletedSessionIds,
                        deletedAudioFileCount = deletedAudioFileCount,
                        failedAudioFileCount = failedPaths.size,
                        usage = SessionStorageUsage(
                            sessionCount = dao.countAllSessions(),
                            audioBytes = remainingAudioBytes,
                        ),
                    )
                }

                when (attempt) {
                    DeleteAttempt.Blocked -> blockedDeletionResult(storageUsage())

                    is DeleteAttempt.Completed -> SessionStorageDeletionResult(
                        usage = attempt.usage,
                        deletedSessionCount = attempt.deletedSessionIds.size,
                        deletedSessionIds = attempt.deletedSessionIds,
                        deletedAudioFileCount = attempt.deletedAudioFileCount,
                        failedAudioFileCount = attempt.failedAudioFileCount,
                        blockedByInProgressSession = false,
                    )
                }
            } finally {
                storageLease.close()
            }
        }
    }

    private suspend fun blockedDeletionResult(usage: SessionStorageUsage) = SessionStorageDeletionResult(
        usage = usage,
        deletedSessionCount = 0,
        deletedSessionIds = emptySet(),
        deletedAudioFileCount = 0,
        failedAudioFileCount = 0,
        blockedByInProgressSession = true,
    )

    private suspend fun recoverInProgressSessions() {
        val abandoned = dao.getInProgress()
        for (session in abandoned) {
            val audioFile = File(session.audioPath)
            val privatePath = isPrivateRecordingPath(session.toDomain())
            var payloadPresence = if (privatePath) {
                PrivatePcmWavWriter.inspectPcmPayload(audioFile)
            } else {
                PcmPayloadPresence.ABSENT
            }
            if (privatePath && payloadPresence != PcmPayloadPresence.ABSENT) {
                // A false repair result is ambiguous: malformed headers and transient I/O both
                // fail recovery. Preserve candidate bytes and only delete after confirming there
                // is no PCM payload.
                PrivatePcmWavWriter.recoverInterruptedFile(audioFile)
                payloadPresence = PrivatePcmWavWriter.inspectPcmPayload(audioFile)
            }
            val retainAudioCandidate = privatePath && payloadPresence != PcmPayloadPresence.ABSENT
            if (privatePath && !retainAudioCandidate) runCatching { audioFile.delete() }
            dao.finishIfInProgress(
                sessionId = session.id,
                endedAtMillis = System.currentTimeMillis(),
                terminalStatus = if (retainAudioCandidate) {
                    SessionStatus.INTERRUPTED.name
                } else {
                    SessionStatus.FAILED.name
                },
            )
        }
    }

    /** Retry WAV repair for retained interrupted candidates on a later capture start. */
    private suspend fun retryInterruptedAudioRecovery() {
        for (session in dao.getInterrupted()) {
            val domainSession = session.toDomain()
            if (!isPrivateRecordingPath(domainSession)) continue
            val audioFile = File(session.audioPath)
            if (PrivatePcmWavWriter.inspectPcmPayload(audioFile) != PcmPayloadPresence.ABSENT) {
                PrivatePcmWavWriter.recoverInterruptedFile(audioFile)
            }
        }
    }

    private fun isPrivateRecordingPath(session: RecordingSession): Boolean =
        resolvePrivateWave(session.audioPath) != null

    private fun resolvePrivateWave(path: String): PrivateWaveFile? = runCatching {
        val file = File(path).toPath().toAbsolutePath().normalize().toFile()
        privateWaveEntry(file)
    }.getOrNull()

    private fun canonicalRecordingsDirectory(): File {
        val rawDirectory = recordingsDirectory.absoluteFile
        val expectedParent = rawDirectory.parentFile?.canonicalFile
            ?: throw IOException("La carpeta privada de grabaciones no tiene carpeta padre.")
        val directory = recordingsDirectory.canonicalFile
        if (rawDirectory.name != "recordings" || directory.name != "recordings" || directory.parentFile != expectedParent) {
            throw IOException("La carpeta privada de grabaciones no coincide con files/recordings.")
        }
        return directory
    }

    /** Only regular direct-child WAV files; symbolic links are excluded without following targets. */
    private fun privateWaveEntries(): List<PrivateWaveFile> {
        val directory = canonicalRecordingsDirectory()
        if (!directory.exists()) return emptyList()
        if (!directory.isDirectory) throw IOException("La ruta privada de grabaciones no es una carpeta.")
        val children = directory.listFiles()
            ?: throw IOException("No se pudo leer la carpeta privada de grabaciones.")
        return children.mapNotNull { child ->
            if (child.parentFile != directory) null else privateWaveEntry(child)
        }
    }

    /** Storage counts each regular file identity once, while deletion unlinks each direct entry. */
    private fun privateWaveFiles(): List<PrivateWaveFile> =
        privateWaveEntries().distinctBy(PrivateWaveFile::identity)

    private fun privateWaveEntry(file: File): PrivateWaveFile? {
        val path = file.toPath().toAbsolutePath().normalize()
        val directory = canonicalRecordingsDirectory().toPath()
        if (path.parent != directory || !path.fileName.toString().endsWith(".wav", ignoreCase = true)) return null
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
        if (!attributes.isRegularFile) return null
        val pathKey = path.toString()
        val identity = attributes.fileKey()?.let { "file:$it" } ?: "path:$pathKey"
        return PrivateWaveFile(
            file = path.toFile(),
            pathKey = pathKey,
            identity = identity,
            sizeBytes = attributes.size(),
        )
    }

    private data class PrivateWaveFile(
        val file: File,
        val pathKey: String,
        val identity: String,
        val sizeBytes: Long,
    )

    private sealed interface DeleteAttempt {
        data object Blocked : DeleteAttempt

        data class Completed(
            val deletedSessionIds: Set<String>,
            val deletedAudioFileCount: Int,
            val failedAudioFileCount: Int,
            val usage: SessionStorageUsage,
        ) : DeleteAttempt
    }
}

private fun RecordingSession.toEntity() = RecordingSessionEntity(
    id = id,
    startedAtMillis = startedAtMillis,
    endedAtMillis = endedAtMillis,
    sourceLanguage = sourceLanguage,
    targetLanguage = targetLanguage,
    audioPath = audioPath,
    status = status,
)

private fun RecordingSessionEntity.toDomain() = RecordingSession(
    id = id,
    startedAtMillis = startedAtMillis,
    endedAtMillis = endedAtMillis,
    sourceLanguage = sourceLanguage,
    targetLanguage = targetLanguage,
    audioPath = audioPath,
    status = status,
)

private fun TranscriptSegmentEntity.toDomain() = TranscriptSegment(
    id = id,
    revision = revision,
    startedAtMillis = startedAtMillis,
    endedAtMillis = endedAtMillis,
    sourceLanguage = sourceLanguage,
    sourceText = sourceText,
    translatedText = translatedText,
    speakerId = speakerId,
    isFinal = isFinal,
)
