package com.voztranslate.app.data

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Validates a new WAV destination without requiring its files/recordings path to exist yet. */
internal object PrivateRecordingPathValidator {
    fun isValidDestination(recordingsDirectory: File, audioPath: String): Boolean = runCatching {
        val rawDirectory = recordingsDirectory.absoluteFile
        if (rawDirectory.name != RECORDINGS_DIRECTORY) return@runCatching false

        val rawParent = rawDirectory.parentFile ?: return@runCatching false
        val parentAttributes = try {
            Files.readAttributes(
                rawParent.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: NoSuchFileException) {
            // Android's private data path may contain a platform alias in an ancestor. Resolve
            // that existing ancestor, then verify the actual directory without following its leaf.
            val existingAncestor = rawParent.parentFile?.canonicalFile ?: return@runCatching false
            val ancestorAttributes = Files.readAttributes(
                existingAncestor.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (!ancestorAttributes.isDirectory || ancestorAttributes.isSymbolicLink) {
                return@runCatching false
            }
            null
        }
        if (parentAttributes != null && (!parentAttributes.isDirectory || parentAttributes.isSymbolicLink)) {
            return@runCatching false
        }

        val expectedParent = rawParent.canonicalFile
        val canonicalDirectory = recordingsDirectory.canonicalFile
        val expectedDirectory = File(expectedParent, RECORDINGS_DIRECTORY)
        if (canonicalDirectory != expectedDirectory) return@runCatching false

        // The recordings folder can be absent on the first capture; the writer creates it after
        // the Room session has been reserved. If present, it must itself be a real directory.
        val directoryAttributes = try {
            Files.readAttributes(
                canonicalDirectory.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: NoSuchFileException) {
            null
        }
        if (directoryAttributes != null &&
            (!directoryAttributes.isDirectory || directoryAttributes.isSymbolicLink)
        ) {
            return@runCatching false
        }

        val destination = File(audioPath).toPath().toAbsolutePath().normalize()
        val destinationParent = destination.parent?.toFile()?.canonicalFile?.toPath()
            ?: return@runCatching false
        if (destinationParent != canonicalDirectory.toPath() ||
            !destination.fileName.toString().endsWith(WAV_EXTENSION, ignoreCase = true)
        ) {
            return@runCatching false
        }

        // NOFOLLOW_LINKS distinguishes a missing leaf from an existing symlink, including a
        // dangling one. Only confirmed absence is accepted; other I/O errors reject the path.
        val destinationAttributes = try {
            Files.readAttributes(destination, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return@runCatching true
        }
        destinationAttributes.isRegularFile && !destinationAttributes.isSymbolicLink
    }.getOrDefault(false)

    private const val RECORDINGS_DIRECTORY = "recordings"
    private const val WAV_EXTENSION = ".wav"
}
