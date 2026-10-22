package com.voztranslate.app.data

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Validates a new WAV destination without requiring the leaf file to exist yet. */
internal object PrivateRecordingPathValidator {
    fun isValidDestination(recordingsDirectory: File, audioPath: String): Boolean = runCatching {
        val rawDirectory = recordingsDirectory.absoluteFile
        if (rawDirectory.name != RECORDINGS_DIRECTORY) return@runCatching false

        val expectedParent = rawDirectory.parentFile?.canonicalFile ?: return@runCatching false
        val canonicalDirectory = recordingsDirectory.canonicalFile
        val expectedDirectory = File(expectedParent, RECORDINGS_DIRECTORY)
        if (canonicalDirectory != expectedDirectory) return@runCatching false

        val parentAttributes = Files.readAttributes(
            expectedParent.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!parentAttributes.isDirectory || parentAttributes.isSymbolicLink) return@runCatching false

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
        if (destination.parent != canonicalDirectory.toPath() ||
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
