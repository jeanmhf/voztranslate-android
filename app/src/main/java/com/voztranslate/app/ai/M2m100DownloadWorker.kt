package com.voztranslate.app.ai

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Resumable, process-persistent downloader for the four pinned M2M100 artifacts. */
class M2m100DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val root = M2m100TranslationModelManager.modelDirectory(applicationContext)
        val readyMarker = File(root, M2m100ModelArtifacts.READY_MARKER)
        val downloader = ResumableM2m100ArtifactDownloader()
        var completedBytes = 0L
        var lastProgressBytes = -PROGRESS_BYTE_INTERVAL
        var lastProgressAt = 0L

        return try {
            if (readyMarker.exists() && !readyMarker.delete()) {
                throw IOException("No se pudo retirar la marca anterior de integridad M2M100.")
            }
            setProgress(progressData(status = STATUS_CHECKING, bytes = 0L, artifactIndex = 0, path = null))
            M2m100ModelArtifacts.files.forEachIndexed { index, artifact ->
                downloader.download(root, artifact) { artifactBytes ->
                    val totalBytes = completedBytes + artifactBytes
                    val now = SystemClock.elapsedRealtime()
                    if (totalBytes - lastProgressBytes >= PROGRESS_BYTE_INTERVAL ||
                        now - lastProgressAt >= PROGRESS_TIME_INTERVAL_MS ||
                        artifactBytes == artifact.sizeBytes
                    ) {
                        setProgress(
                            progressData(
                                status = STATUS_DOWNLOADING,
                                bytes = totalBytes,
                                artifactIndex = index + 1,
                                path = artifact.relativePath,
                            ),
                        )
                        lastProgressBytes = totalBytes
                        lastProgressAt = now
                    }
                }
                completedBytes += artifact.sizeBytes
                setProgress(
                    progressData(
                        status = STATUS_DOWNLOADING,
                        bytes = completedBytes,
                        artifactIndex = index + 1,
                        path = artifact.relativePath,
                    ),
                )
            }

            M2m100ArtifactVerifier.writeReadyMarkerForVerifiedFiles(root, M2m100ModelArtifacts.files)
            setProgress(
                progressData(
                status = STATUS_FILES_VERIFIED,
                    bytes = M2m100ModelArtifacts.totalSizeBytes,
                    artifactIndex = M2m100ModelArtifacts.files.size,
                    path = null,
                ),
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(
                    Data.Builder()
                        .putString(KEY_ERROR, error.message ?: "No se pudo descargar el modelo M2M100.")
                        .build(),
                )
            }
        } catch (error: Exception) {
            Result.failure(
                Data.Builder()
                    .putString(KEY_ERROR, error.message ?: "No se pudo preparar el modelo M2M100.")
                    .build(),
            )
        }
    }

    private fun progressData(
        status: String,
        bytes: Long,
        artifactIndex: Int,
        path: String?,
    ): Data = Data.Builder()
        .putString(KEY_STATUS, status)
        .putLong(KEY_BYTES_DOWNLOADED, bytes.coerceAtMost(M2m100ModelArtifacts.totalSizeBytes))
        .putLong(KEY_BYTES_TOTAL, M2m100ModelArtifacts.totalSizeBytes)
        .putInt(KEY_ARTIFACT_INDEX, artifactIndex)
        .putInt(KEY_ARTIFACT_COUNT, M2m100ModelArtifacts.files.size)
        .putString(KEY_RELATIVE_PATH, path)
        .build()

    companion object {
        const val KEY_STATUS = "m2m100_status"
        const val KEY_BYTES_DOWNLOADED = "m2m100_bytes_downloaded"
        const val KEY_BYTES_TOTAL = "m2m100_bytes_total"
        const val KEY_ARTIFACT_INDEX = "m2m100_artifact_index"
        const val KEY_ARTIFACT_COUNT = "m2m100_artifact_count"
        const val KEY_RELATIVE_PATH = "m2m100_relative_path"
        const val KEY_ERROR = "m2m100_error"

        const val STATUS_CHECKING = "CHECKING"
        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_FILES_VERIFIED = "FILES_VERIFIED"

        private const val MAX_RETRY_ATTEMPTS = 5
        private const val PROGRESS_BYTE_INTERVAL = 4L * 1024L * 1024L
        private const val PROGRESS_TIME_INTERVAL_MS = 750L

        fun buildRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<M2m100DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
    }
}
