package com.voztranslate.app.ai

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Prepares one offline model shared by all six directions among English, Spanish and Mandarin. */
object M2m100TranslationModelManager {
    val requiredDownloadBytes: Long get() = M2m100ModelArtifacts.totalSizeBytes

    fun modelDirectory(context: Context): File = File(
        context.applicationContext.filesDir,
        M2m100ModelArtifacts.RELATIVE_MODEL_DIRECTORY,
    )

    /** Checks the installation receipt left only after all pinned files passed full SHA-256 checks. */
    suspend fun isPrepared(context: Context): Boolean = withContext(Dispatchers.IO) {
        M2m100ArtifactVerifier.isReady(modelDirectory(context.applicationContext), M2m100ModelArtifacts.files)
    }

    /** Enqueues or joins the process-persistent WorkManager download and emits its latest progress. */
    suspend fun ensurePrepared(
        context: Context,
        onProgress: (M2m100DownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val root = modelDirectory(appContext)
        if (M2m100ArtifactVerifier.isReady(root, M2m100ModelArtifacts.files)) return@withContext root

        val workManager = WorkManager.getInstance(appContext)
        val workName = uniqueWorkName()
        val beforeEnqueue = awaitWorkInfos(workManager, workName)
        val knownPendingIds = beforeEnqueue.filterNot { it.state.isFinished }.map { it.id }.toSet()
        val request = M2m100DownloadWorker.buildRequest()
        awaitOperation(workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request))
        val afterEnqueue = awaitWorkInfos(workManager, workName)
        val effectiveWorkId = selectM2m100WorkId(
            requestedId = request.id,
            knownPendingIds = knownPendingIds,
            candidates = afterEnqueue.map(::toCandidate),
        ) ?: throw IOException("WorkManager no devolvió el trabajo efectivo para M2M100.")

        val terminalWork = observeUntilTerminal(workManager, workName, effectiveWorkId, onProgress)
        when (terminalWork.state) {
            WorkInfo.State.SUCCEEDED -> {
                if (M2m100ArtifactVerifier.isReady(root, M2m100ModelArtifacts.files)) root
                else throw IOException("La descarga terminó sin una marca de integridad válida para M2M100.")
            }

            WorkInfo.State.FAILED -> throw IOException(
                terminalWork.outputData.getString(M2m100DownloadWorker.KEY_ERROR)
                    ?: "No se pudo descargar y verificar el modelo M2M100.",
            )

            WorkInfo.State.CANCELLED -> throw IOException("La descarga del modelo M2M100 fue cancelada.")
            else -> throw IOException("El trabajo M2M100 terminó en un estado inesperado: ${terminalWork.state}.")
        }
    }

    /** Reattaches to a download that is still pending after a process or screen restart. */
    suspend fun observeExistingDownload(
        context: Context,
        onProgress: (M2m100DownloadProgress) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val root = modelDirectory(appContext)
        if (M2m100ArtifactVerifier.isReady(root, M2m100ModelArtifacts.files)) return@withContext false
        val workManager = WorkManager.getInstance(appContext)
        val workName = uniqueWorkName()
        val initial = workManager.getWorkInfosForUniqueWorkFlow(workName).first()
        val knownPendingIds = initial.filterNot { it.state.isFinished }.map { it.id }.toSet()
        if (knownPendingIds.isEmpty()) return@withContext false
        val effectiveWorkId = selectM2m100WorkId(
            requestedId = null,
            knownPendingIds = knownPendingIds,
            candidates = initial.map(::toCandidate),
        ) ?: return@withContext false

        val terminalWork = observeUntilTerminal(workManager, workName, effectiveWorkId, onProgress)
        when (terminalWork.state) {
            WorkInfo.State.SUCCEEDED -> M2m100ArtifactVerifier.isReady(root, M2m100ModelArtifacts.files)
            WorkInfo.State.FAILED -> throw IOException(
                terminalWork.outputData.getString(M2m100DownloadWorker.KEY_ERROR)
                    ?: "No se pudo descargar y verificar el modelo M2M100.",
            )

            WorkInfo.State.CANCELLED -> throw IOException("La descarga del modelo M2M100 fue cancelada.")
            else -> false
        }
    }

    private fun uniqueWorkName() = "m2m100-418m-int8-${M2m100ModelArtifacts.REVISION}"

    private fun awaitOperation(operation: androidx.work.Operation) {
        try {
            operation.result.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Se interrumpió el registro del trabajo M2M100.", error)
        } catch (error: ExecutionException) {
            throw IOException("WorkManager no pudo registrar el trabajo M2M100.", error.cause ?: error)
        }
    }

    private fun awaitWorkInfos(workManager: WorkManager, workName: String): List<WorkInfo> = try {
        workManager.getWorkInfosForUniqueWork(workName).get()
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Se interrumpió la consulta del trabajo M2M100.", error)
    } catch (error: ExecutionException) {
        throw IOException("No se pudo consultar el trabajo M2M100.", error.cause ?: error)
    }

    private suspend fun observeUntilTerminal(
        workManager: WorkManager,
        workName: String,
        workId: UUID,
        onProgress: (M2m100DownloadProgress) -> Unit,
    ): WorkInfo = try {
        workManager.getWorkInfosForUniqueWorkFlow(workName).collect { workInfos ->
            val workInfo = workInfos.firstOrNull { it.id == workId }
                ?: throw IOException("El trabajo efectivo de descarga M2M100 ya no está disponible.")
            onProgress(workInfo.toProgress())
            if (workInfo.state.isFinished) throw TerminalWorkObserved(workInfo)
        }
        throw IOException("WorkManager dejó de observar el trabajo M2M100 sin estado terminal.")
    } catch (terminal: TerminalWorkObserved) {
        terminal.workInfo
    }

    private fun WorkInfo.toProgress(): M2m100DownloadProgress = M2m100DownloadProgress(
        state = state.name,
        relativePath = progress.getString(M2m100DownloadWorker.KEY_RELATIVE_PATH),
        artifactIndex = progress.getInt(M2m100DownloadWorker.KEY_ARTIFACT_INDEX, 0),
        artifactCount = progress.getInt(M2m100DownloadWorker.KEY_ARTIFACT_COUNT, M2m100ModelArtifacts.files.size),
        bytesDownloaded = progress.getLong(M2m100DownloadWorker.KEY_BYTES_DOWNLOADED, 0L),
        bytesTotal = progress.getLong(M2m100DownloadWorker.KEY_BYTES_TOTAL, requiredDownloadBytes),
        error = outputData.getString(M2m100DownloadWorker.KEY_ERROR),
    )

    private fun toCandidate(workInfo: WorkInfo): M2m100WorkCandidate = M2m100WorkCandidate(
        id = workInfo.id,
        state = when (workInfo.state) {
            WorkInfo.State.ENQUEUED -> M2m100WorkCandidate.State.ENQUEUED
            WorkInfo.State.RUNNING -> M2m100WorkCandidate.State.RUNNING
            WorkInfo.State.BLOCKED -> M2m100WorkCandidate.State.BLOCKED
            WorkInfo.State.SUCCEEDED -> M2m100WorkCandidate.State.SUCCEEDED
            WorkInfo.State.FAILED -> M2m100WorkCandidate.State.FAILED
            WorkInfo.State.CANCELLED -> M2m100WorkCandidate.State.CANCELLED
        },
    )

    private class TerminalWorkObserved(val workInfo: WorkInfo) : Exception()
}

data class M2m100DownloadProgress(
    val state: String,
    val relativePath: String?,
    val artifactIndex: Int,
    val artifactCount: Int,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val error: String?,
)

internal data class M2m100WorkCandidate(
    val id: UUID,
    val state: State,
) {
    enum class State { ENQUEUED, RUNNING, BLOCKED, SUCCEEDED, FAILED, CANCELLED }
    val isFinished: Boolean
        get() = state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED
}

/** Pure selector test seam for WorkManager KEEP requests and retained jobs. */
internal fun selectM2m100WorkId(
    requestedId: UUID?,
    knownPendingIds: Set<UUID>,
    candidates: List<M2m100WorkCandidate>,
): UUID? {
    val requested = requestedId?.let { id -> candidates.firstOrNull { it.id == id } }
    if (requested != null && requested.state != M2m100WorkCandidate.State.CANCELLED) return requested.id
    return candidates.firstOrNull { it.id in knownPendingIds }?.id
        ?: candidates.firstOrNull { !it.isFinished }?.id
        ?: requested?.id
        ?: candidates.lastOrNull()?.id
}
