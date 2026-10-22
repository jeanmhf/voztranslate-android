package com.voztranslate.app.ai

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Files required by the pinned M2M100 ONNX export and its SentencePiece tokenizer. */
internal data class M2m100ModelArtifact(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    val downloadUrl: String
        get() = "$MODEL_REPOSITORY/resolve/$MODEL_REVISION/$relativePath"

    companion object {
        private const val MODEL_REPOSITORY = "https://huggingface.co/huggingworld/m2m100_418M"
        const val MODEL_REVISION = M2m100ModelArtifacts.REVISION
    }
}

internal object M2m100ModelArtifacts {
    const val REVISION = "48f06c0fec544323fcf1546e312a617d962d3653"
    const val RELATIVE_MODEL_DIRECTORY = "m2m100/418m-int8/$REVISION"
    const val READY_MARKER = ".verified-manifest"

    val files = listOf(
        M2m100ModelArtifact(
            relativePath = "onnx/encoder_model_quantized.onnx",
            sizeBytes = 287_856_370L,
            sha256 = "13a94e354a9140764eb81102d77d3ec6952d796e6f113c651eeb3c3443da0386",
        ),
        M2m100ModelArtifact(
            relativePath = "onnx/decoder_model_merged_quantized.onnx",
            sizeBytes = 344_128_178L,
            sha256 = "007654bcabb6cea6fd3bde34ce933137b431330b3755781145d7b6906270b45a",
        ),
        M2m100ModelArtifact(
            relativePath = "sentencepiece.bpe.model",
            sizeBytes = 2_423_393L,
            sha256 = "d8f7c76ed2a5e0822be39f0a4f95a55eb19c78f4593ce609e2edbc2aea4d380a",
        ),
        M2m100ModelArtifact(
            relativePath = "vocab.json",
            sizeBytes = 3_708_092L,
            sha256 = "b6e77e474aeea8f441363aca7614317c06381f3eacfe10fb9856d5081d1074cc",
        ),
    )

    val totalSizeBytes: Long = files.sumOf(M2m100ModelArtifact::sizeBytes)
}

/** Hash and path checks independent of Android, WorkManager and ONNX Runtime. */
internal object M2m100ArtifactVerifier {
    private val validSha256 = Regex("[0-9a-f]{64}")

    fun resolveContained(root: File, relativePath: String): File {
        require(relativePath.isNotBlank()) { "La ruta del artefacto está vacía." }
        require(!File(relativePath).isAbsolute) { "La ruta del artefacto debe ser relativa." }
        val pathParts = relativePath.split('/', '\\')
        require(pathParts.none { it.isBlank() || it == "." || it == ".." }) {
            "La ruta del artefacto contiene segmentos no permitidos."
        }

        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relativePath).canonicalFile
        val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        require(target.path.startsWith(rootPrefix)) { "La ruta del artefacto escapa del directorio del modelo." }
        return target
    }

    fun verify(root: File, artifacts: List<M2m100ModelArtifact>): Boolean {
        val resolvedManifest = resolveManifest(root, artifacts)
        return resolvedManifest.all { (artifact, file) -> verifyFile(file, artifact) }
    }

    fun verifyFile(file: File, artifact: M2m100ModelArtifact): Boolean {
        validateArtifact(artifact)
        if (!file.isFile || file.length() != artifact.sizeBytes) return false
        return sha256(file).equals(artifact.sha256, ignoreCase = true)
    }

    /** A receipt is written only after every file has passed its full pinned SHA-256 check. */
    fun writeReadyMarker(root: File, artifacts: List<M2m100ModelArtifact>) {
        validateManifest(artifacts)
        check(verify(root, artifacts)) { "No se puede marcar listo un modelo con archivos sin verificar." }
        writeReadyMarkerForVerifiedFiles(root, artifacts)
    }

    /**
     * Used only by the downloader after each file has already passed verifyFile and has been
     * atomically promoted. Avoids hashing the complete 638 MB set a second time before receipt.
     */
    internal fun writeReadyMarkerForVerifiedFiles(root: File, artifacts: List<M2m100ModelArtifact>) {
        val resolvedManifest = resolveManifest(root, artifacts)
        check(resolvedManifest.all { (artifact, file) ->
            file.isFile && file.length() == artifact.sizeBytes
        }) { "No se puede marcar listo un modelo con archivos ausentes o incompletos." }
        if (!root.exists() && !root.mkdirs()) throw IOException("No se pudo crear el directorio privado M2M100.")

        val marker = readyMarkerContents(artifacts)
        val temporary = File(root, "${M2m100ModelArtifacts.READY_MARKER}.tmp")
        val ready = File(root, M2m100ModelArtifacts.READY_MARKER)
        temporary.writeText(marker, StandardCharsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(),
                ready.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: Exception) {
            temporary.delete()
            throw IOException("No se pudo finalizar la marca de integridad M2M100.", error)
        }
    }

    /**
     * Checks the private installation receipt and sizes without hashing 634 MB on every app
     * launch. The downloader computes full hashes before it writes this receipt.
     */
    fun isReady(root: File, artifacts: List<M2m100ModelArtifact>): Boolean {
        val resolvedManifest = resolveManifest(root, artifacts)
        val marker = File(root, M2m100ModelArtifacts.READY_MARKER)
        if (!marker.isFile || marker.readText(StandardCharsets.UTF_8) != readyMarkerContents(artifacts)) return false
        return resolvedManifest.all { (artifact, file) ->
            file.isFile && file.length() == artifact.sizeBytes
        }
    }

    /** Resolve every entry eagerly so a missing earlier file cannot hide a later unsafe path. */
    private fun resolveManifest(
        root: File,
        artifacts: List<M2m100ModelArtifact>,
    ): List<Pair<M2m100ModelArtifact, File>> {
        validateManifest(artifacts)
        return artifacts.map { artifact -> artifact to resolveContained(root, artifact.relativePath) }
    }

    private fun validateManifest(artifacts: List<M2m100ModelArtifact>) {
        require(artifacts.isNotEmpty()) { "El manifiesto M2M100 no contiene archivos." }
        artifacts.forEach(::validateArtifact)
        require(artifacts.map(M2m100ModelArtifact::relativePath).toSet().size == artifacts.size) {
            "El manifiesto M2M100 contiene rutas duplicadas."
        }
    }

    private fun validateArtifact(artifact: M2m100ModelArtifact) {
        require(artifact.sizeBytes > 0L) { "El tamaño esperado del artefacto debe ser positivo." }
        require(validSha256.matches(artifact.sha256)) { "El manifiesto contiene un SHA-256 inválido." }
        require(artifact.relativePath.isNotBlank()) { "La ruta del artefacto está vacía." }
    }

    private fun readyMarkerContents(artifacts: List<M2m100ModelArtifact>): String = buildString {
        append(M2m100ModelArtifacts.REVISION).append('\n')
        artifacts.sortedBy(M2m100ModelArtifact::relativePath).forEach { artifact ->
            append(artifact.relativePath).append('\t')
                .append(artifact.sizeBytes).append('\t')
                .append(artifact.sha256).append('\n')
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
