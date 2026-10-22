package com.voztranslate.app.ai

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/** A single file entry from Moonshine's STT dependency catalog. */
internal data class MoonshineModelFileEntry(
    val relativeName: String,
    val expectedSize: Long?,
    val checksum: String,
    val checksumType: String,
)

/** Pure file/catalog verifier, kept independent of Android and Moonshine JNI for JVM tests. */
internal object MoonshineModelIntegrityVerifier {
    private const val CRC32C_REVERSED_POLYNOMIAL = -2_097_792_136

    /**
     * Returns true when every listed file exists and matches its catalog size/checksum.
     * Incomplete files are left in place for the downloader to repair. Files with a definite
     * size/checksum mismatch are removed so the downloader can fetch them again. Invalid catalog
     * metadata, unreadable files, and paths outside [root] fail without deleting anything.
     */
    fun verify(root: File, entries: List<MoonshineModelFileEntry>): Boolean {
        if (entries.isEmpty()) throw IOException("El manifiesto Moonshine no contiene archivos para verificar.")

        val canonicalRoot = root.canonicalFile
        val validatedEntries = entries.map { entry ->
            if (entry.relativeName.isBlank()) throw IOException("El manifiesto Moonshine contiene un archivo sin nombre.")
            val canonicalFile = File(canonicalRoot, entry.relativeName).canonicalFile
            if (canonicalFile == canonicalRoot || !canonicalFile.toPath().startsWith(canonicalRoot.toPath())) {
                throw IOException("Ruta de modelo fuera del almacenamiento privado: ${entry.relativeName}")
            }
            val expectedSize = entry.expectedSize
            if (expectedSize != null && expectedSize < 0) {
                throw IOException("Tamaño inválido en el manifiesto para ${entry.relativeName}.")
            }
            if (!entry.checksumType.equals("crc32c", ignoreCase = true)) {
                throw IOException("Tipo de checksum Moonshine no admitido para ${entry.relativeName}: ${entry.checksumType}")
            }
            val expectedChecksum = decodeExpectedCrc32c(entry)
            ValidatedEntry(
                file = canonicalFile,
                relativeName = entry.relativeName,
                expectedSize = expectedSize,
                expectedChecksum = expectedChecksum,
            )
        }
        if (validatedEntries.map { it.file.path }.toSet().size != validatedEntries.size) {
            throw IOException("El manifiesto Moonshine contiene rutas de archivo duplicadas.")
        }

        var allFilesPresentAndValid = true
        val corruptFiles = mutableListOf<File>()
        for (entry in validatedEntries) {
            if (!entry.file.exists()) {
                allFilesPresentAndValid = false
                continue
            }
            if (!entry.file.isFile) {
                throw IOException("La ruta de modelo no corresponde a un archivo regular: ${entry.relativeName}")
            }
            if (entry.expectedSize != null && entry.file.length() != entry.expectedSize) {
                corruptFiles += entry.file
                allFilesPresentAndValid = false
                continue
            }
            if (!MessageDigest.isEqual(entry.expectedChecksum, crc32c(entry.file))) {
                corruptFiles += entry.file
                allFilesPresentAndValid = false
            }
        }

        corruptFiles.forEach { file ->
            if (!file.delete() && file.exists()) {
                throw IOException("No se pudo eliminar el archivo de modelo con checksum incorrecto: ${file.name}")
            }
        }
        return allFilesPresentAndValid
    }

    private fun decodeExpectedCrc32c(entry: MoonshineModelFileEntry): ByteArray {
        if (entry.checksum.isBlank()) {
            throw IOException("El manifiesto de ${entry.relativeName} no publica un checksum CRC32C.")
        }
        val decoded = try {
            Base64.getDecoder().decode(entry.checksum)
        } catch (error: IllegalArgumentException) {
            throw IOException("El checksum CRC32C de ${entry.relativeName} no es Base64 válido.", error)
        }
        if (decoded.size != Int.SIZE_BYTES) {
            throw IOException("El checksum CRC32C de ${entry.relativeName} no tiene cuatro bytes.")
        }
        return decoded
    }

    private fun crc32c(file: File): ByteArray {
        var crc = -1
        val buffer = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    crc = crc xor (buffer[index].toInt() and 0xff)
                    repeat(Byte.SIZE_BITS) {
                        crc = if ((crc and 1) != 0) {
                            (crc ushr 1) xor CRC32C_REVERSED_POLYNOMIAL
                        } else {
                            crc ushr 1
                        }
                    }
                }
            }
        }
        crc = crc.inv()
        // Moonshine encodes its 32-bit CRC in network byte order, then Base64.
        return byteArrayOf(
            (crc ushr 24).toByte(),
            (crc ushr 16).toByte(),
            (crc ushr 8).toByte(),
            crc.toByte(),
        )
    }

    private data class ValidatedEntry(
        val file: File,
        val relativeName: String,
        val expectedSize: Long?,
        val expectedChecksum: ByteArray,
    )
}
