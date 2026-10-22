package com.voztranslate.app.ai

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Small HTTP seam for deterministic range/resume tests without Android or Hugging Face access. */
internal interface M2m100RangeResponse : Closeable {
    val statusCode: Int
    val contentRangeStart: Long?
    val contentRangeTotal: Long?
    fun openBody(): InputStream
}

internal fun interface M2m100RangeTransport {
    fun open(url: String, offset: Long): M2m100RangeResponse
}

internal class HttpM2m100RangeTransport : M2m100RangeTransport {
    override fun open(url: String, offset: Long): M2m100RangeResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
        return try {
            val statusCode = connection.responseCode
            val range = CONTENT_RANGE.matchEntire(connection.getHeaderField("Content-Range").orEmpty())
            object : M2m100RangeResponse {
                override val statusCode = statusCode
                override val contentRangeStart = range?.groupValues?.get(1)?.toLongOrNull()
                override val contentRangeTotal = range?.groupValues?.get(3)?.toLongOrNull()

                override fun openBody(): InputStream = connection.inputStream

                override fun close() {
                    connection.disconnect()
                }
            }
        } catch (error: Throwable) {
            connection.disconnect()
            throw error
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    }
}

/**
 * Downloads into a sibling `.part` file, resumes with HTTP Range, verifies exact size and SHA-256,
 * and atomically promotes the verified file. A cancelled or interrupted transfer leaves `.part`
 * available to the next WorkManager attempt.
 */
internal class ResumableM2m100ArtifactDownloader(
    private val transport: M2m100RangeTransport = HttpM2m100RangeTransport(),
) {
    suspend fun download(
        root: File,
        artifact: M2m100ModelArtifact,
        onProgress: suspend (bytesDownloadedForArtifact: Long) -> Unit = {},
    ) {
        val destination = M2m100ArtifactVerifier.resolveContained(root, artifact.relativePath)
        val parent = destination.parentFile ?: throw IOException("La ruta del artefacto no tiene directorio padre.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("No se pudo crear el directorio privado M2M100.")

        if (destination.exists()) {
            if (M2m100ArtifactVerifier.verifyFile(destination, artifact)) {
                onProgress(artifact.sizeBytes)
                return
            }
            if (!destination.delete()) throw IOException("No se pudo quitar el archivo M2M100 inválido.")
        }

        val partial = File(parent, "${destination.name}.part")
        if (partial.length() > artifact.sizeBytes && !partial.delete()) {
            throw IOException("El archivo parcial M2M100 excede el tamaño esperado y no se pudo quitar.")
        }
        val offset = if (partial.exists()) partial.length() else 0L

        transport.open(artifact.downloadUrl, offset).use { response ->
            when (response.statusCode) {
                HTTP_RANGE_NOT_SATISFIABLE -> {
                    if (offset == artifact.sizeBytes && M2m100ArtifactVerifier.verifyFile(partial, artifact)) {
                        promote(partial, destination)
                        onProgress(artifact.sizeBytes)
                        return
                    }
                    partial.delete()
                    throw IOException("El servidor rechazó el rango parcial del artefacto M2M100.")
                }

                HttpURLConnection.HTTP_PARTIAL -> {
                    if (response.contentRangeStart != offset ||
                        (response.contentRangeTotal != null && response.contentRangeTotal != artifact.sizeBytes)
                    ) {
                        partial.delete()
                        throw IOException("El servidor devolvió un rango M2M100 que no corresponde al archivo parcial.")
                    }
                }

                HttpURLConnection.HTTP_OK -> Unit // Server ignored Range; restart this artifact.
                else -> throw IOException("El servidor devolvió HTTP ${response.statusCode} al descargar M2M100.")
            }

            val append = response.statusCode == HttpURLConnection.HTTP_PARTIAL && offset > 0L
            val startBytes = if (append) offset else 0L
            var downloaded = startBytes
            try {
                response.openBody().buffered().use { input ->
                    FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            if (downloaded > artifact.sizeBytes) {
                                throw IOException("El servidor descargó más bytes de los fijados para M2M100.")
                            }
                            output.write(buffer, 0, count)
                            onProgress(downloaded)
                        }
                        output.fd.sync()
                    }
                }
            } catch (error: Throwable) {
                if (partial.length() > artifact.sizeBytes) partial.delete()
                throw error
            }
        }

        if (partial.length() != artifact.sizeBytes) {
            throw IOException("Descarga parcial M2M100: ${partial.length()} de ${artifact.sizeBytes} bytes.")
        }
        if (!M2m100ArtifactVerifier.verifyFile(partial, artifact)) {
            partial.delete()
            throw IOException("El SHA-256 del artefacto M2M100 no coincide con el manifiesto pinned.")
        }
        promote(partial, destination)
    }

    private fun promote(partial: File, destination: File) {
        try {
            java.nio.file.Files.move(
                partial.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: Exception) {
            throw IOException("No se pudo finalizar el artefacto M2M100 verificado.", error)
        }
    }

    private companion object {
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
