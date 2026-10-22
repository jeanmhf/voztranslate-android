package com.voztranslate.app.ai

import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.JNI
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.MoonshineDownloadWorker
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.voztranslate.app.domain.Language
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Downloads and verifies the approved Tiny Streaming model in app-private storage. */
object MoonshineModelManager {
    const val MODEL_DIRECTORY = "moonshine/tiny-streaming"

    private const val MAX_INTEGRITY_REDOWNLOADS = 2
    private val downloader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AssetDownloader() }

    fun modelDirectory(context: Context, language: Language): File = File(
        File(context.applicationContext.filesDir, MODEL_DIRECTORY),
        language.localeTag,
    )

    fun modelSpec(language: Language): ModelSpec = ModelSpec.stt(
        language.localeTag,
        JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING,
        false,
    )

    suspend fun isPrepared(context: Context, language: Language): Boolean = withContext(Dispatchers.IO) {
        isPreparedOnIo(context.applicationContext, language)
    }

    suspend fun ensurePrepared(
        context: Context,
        language: Language,
        onProgress: (DownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val root = modelDirectory(appContext, language)
        if (isPreparedOnIo(appContext, language)) return@withContext root

        val workManager = WorkManager.getInstance(appContext)
        val workName = uniqueWorkName(language)
        repeat(MAX_INTEGRITY_REDOWNLOADS + 1) {
            val beforeEnqueue = awaitWorkInfos(workManager, workName)
            val knownPendingIds = beforeEnqueue.filterNot { it.state.isFinished }.map { it.id }.toSet()
            val request = MoonshineDownloadWorker.buildRequest(root, modelSpec(language), false)
            val enqueueOperation = workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
            awaitOperation(enqueueOperation)
            val afterEnqueue = awaitWorkInfos(workManager, workName)
            val effectiveWorkId = selectMoonshineWorkId(
                requestedId = request.id,
                knownPendingIds = knownPendingIds,
                candidates = afterEnqueue.map(::toWorkCandidate),
            ) ?: throw IOException("WorkManager no devolvió el trabajo de descarga efectivo.")
            try {
                observeUntilFinished(workManager, workName, effectiveWorkId, onProgress)
            } catch (_: DownloadCompleted) {
                // The worker only verifies that the files exist. Verify their catalog checksums
                // here before considering the model ready; a bad file is removed for the retry.
            }
            if (isPreparedOnIo(appContext, language)) return@withContext root
        }
        throw IOException("No se pudo verificar la integridad del modelo Moonshine tras descargarlo.")
    }

    /** Reattaches the UI to a WorkManager download that survived an app/process restart. */
    suspend fun observeExistingDownload(
        context: Context,
        language: Language,
        onProgress: (DownloadProgress) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)
        val workName = uniqueWorkName(language)
        val initial = workManager.getWorkInfosForUniqueWorkFlow(workName).first()
        val knownPendingIds = initial.filterNot { it.state.isFinished }.map { it.id }.toSet()
        if (knownPendingIds.isEmpty()) return@withContext false
        val effectiveWorkId = selectMoonshineWorkId(
            requestedId = null,
            knownPendingIds = knownPendingIds,
            candidates = initial.map(::toWorkCandidate),
        ) ?: return@withContext false
        try {
            observeUntilFinished(
                workManager = workManager,
                workName = workName,
                requestId = effectiveWorkId,
                onProgress = onProgress,
            )
        } catch (_: DownloadCompleted) {
            if (isPreparedOnIo(appContext, language)) return@withContext true
            throw IOException("La descarga terminó, pero el modelo no superó la comprobación de integridad.")
        }
        false
    }

    private fun awaitOperation(operation: androidx.work.Operation) {
        try {
            operation.result.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Se interrumpió el registro de la descarga Moonshine.", error)
        } catch (error: ExecutionException) {
            throw IOException("WorkManager no pudo registrar la descarga Moonshine.", error.cause ?: error)
        }
    }

    private fun awaitWorkInfos(workManager: WorkManager, workName: String): List<WorkInfo> = try {
        workManager.getWorkInfosForUniqueWork(workName).get()
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Se interrumpió la consulta del trabajo de descarga Moonshine.", error)
    } catch (error: ExecutionException) {
        throw IOException("No se pudo consultar el trabajo de descarga Moonshine.", error.cause ?: error)
    }

    private fun toWorkCandidate(workInfo: WorkInfo): MoonshineWorkCandidate = MoonshineWorkCandidate(
        id = workInfo.id,
        state = when (workInfo.state) {
            WorkInfo.State.ENQUEUED -> MoonshineWorkCandidate.State.ENQUEUED
            WorkInfo.State.RUNNING -> MoonshineWorkCandidate.State.RUNNING
            WorkInfo.State.BLOCKED -> MoonshineWorkCandidate.State.BLOCKED
            WorkInfo.State.SUCCEEDED -> MoonshineWorkCandidate.State.SUCCEEDED
            WorkInfo.State.FAILED -> MoonshineWorkCandidate.State.FAILED
            WorkInfo.State.CANCELLED -> MoonshineWorkCandidate.State.CANCELLED
        },
    )

    private fun isPreparedOnIo(context: Context, language: Language): Boolean {
        val root = modelDirectory(context, language)
        if (!downloader.isModelPresent(root, modelSpec(language))) return false
        return verifyManifestFiles(root, language)
    }

    private fun verifyManifestFiles(root: File, language: Language): Boolean {
        val options = listOf(
            TranscriberOption(
                "model_arch",
                JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING.toString(),
            ),
        )
        val manifest = try {
            JSONObject(Transcriber.getSttDependencies(language.localeTag, options))
        } catch (error: Exception) {
            throw IOException("Moonshine no pudo proporcionar el manifiesto de integridad.", error)
        }
        val groups = manifest.optJSONArray("groups")
            ?: throw IOException("El manifiesto Moonshine no contiene grupos de archivos.")
        val filesToVerify = mutableListOf<MoonshineModelFileEntry>()
        for (groupIndex in 0 until groups.length()) {
            val files = groups.optJSONObject(groupIndex)?.optJSONArray("files")
                ?: throw IOException("El manifiesto Moonshine contiene un grupo sin archivos.")
            for (fileIndex in 0 until files.length()) {
                val item = files.optJSONObject(fileIndex)
                    ?: throw IOException("El manifiesto Moonshine contiene una entrada de archivo inválida.")
                val relativePath = item.optString("name", "").takeIf(String::isNotBlank)
                    ?: throw IOException("El manifiesto Moonshine contiene un archivo sin nombre.")
                val expectedSize = if (item.has("size")) item.optLong("size", -1L) else null
                filesToVerify += MoonshineModelFileEntry(
                    relativeName = relativePath,
                    expectedSize = expectedSize,
                    checksum = item.optString("checksum", ""),
                    checksumType = item.optString("checksum_type", ""),
                )
            }
        }
        return MoonshineModelIntegrityVerifier.verify(root, filesToVerify)
    }

    private suspend fun observeUntilFinished(
        workManager: WorkManager,
        workName: String,
        requestId: UUID,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        workManager.getWorkInfosForUniqueWorkFlow(workName).collect { workInfos ->
            val work = workInfos.firstOrNull { it.id == requestId } ?: return@collect
            val progress = work.progress
            val update = DownloadProgress(
                status = work.state.name,
                relativePath = progress.getString(MoonshineDownloadWorker.PROGRESS_RELATIVE_PATH),
                fileIndex = progress.getInt(MoonshineDownloadWorker.PROGRESS_FILE_INDEX, 0),
                totalFiles = progress.getInt(MoonshineDownloadWorker.PROGRESS_TOTAL_FILES, 0),
                bytesDownloaded = progress.getLong(MoonshineDownloadWorker.PROGRESS_BYTES_DOWNLOADED, 0L),
                bytesTotal = progress.getLong(MoonshineDownloadWorker.PROGRESS_BYTES_TOTAL, -1L),
            )
            onProgress(update)
            when (work.state) {
                WorkInfo.State.SUCCEEDED -> throw DownloadCompleted()
                WorkInfo.State.FAILED -> throw IOException(
                    work.outputData.getString(MoonshineDownloadWorker.OUTPUT_ERROR)
                        ?: "WorkManager no pudo descargar el modelo Moonshine.",
                )

                WorkInfo.State.CANCELLED -> throw IOException("La descarga del modelo Moonshine fue cancelada.")
                else -> Unit
            }
        }
    }

    private fun uniqueWorkName(language: Language) = "moonshine-tiny-streaming-${language.localeTag}"

    private class DownloadCompleted : Exception()
}

data class DownloadProgress(
    val status: String,
    val relativePath: String?,
    val fileIndex: Int,
    val totalFiles: Int,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
)
