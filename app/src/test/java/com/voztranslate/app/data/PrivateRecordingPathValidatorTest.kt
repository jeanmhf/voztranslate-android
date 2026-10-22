package com.voztranslate.app.data

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateRecordingPathValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsNewWaveDestinationWhenFilesDirectoryDoesNotExistYet() {
        val appDataDirectory = temporaryFolder.newFolder("app-data")
        val filesDirectory = File(appDataDirectory, "files")
        val recordingsDirectory = File(filesDirectory, "recordings")
        val destination = File(recordingsDirectory, "new.wav")

        assertFalse(filesDirectory.exists())
        assertFalse(recordingsDirectory.exists())
        assertFalse(destination.exists())
        assertTrue(PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, destination.path))
    }

    @Test
    fun acceptsNewWaveDestinationWhenFilesDirectoryExistsButRecordingsDoesNot() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val recordingsDirectory = File(filesDirectory, "recordings")
        val destination = File(recordingsDirectory, "new.wav")

        assertTrue(filesDirectory.isDirectory)
        assertFalse(recordingsDirectory.exists())
        assertFalse(destination.exists())
        assertTrue(PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, destination.path))
    }

    @Test
    fun acceptsMissingWaveLeafInsideExistingRecordingsDirectory() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val recordingsDirectory = File(filesDirectory, "recordings").apply { mkdir() }
        val destination = File(recordingsDirectory, "new.wav")

        assertTrue(recordingsDirectory.isDirectory)
        assertFalse(destination.exists())
        assertTrue(PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, destination.path))
    }

    @Test
    fun acceptsExistingRegularWaveAndRejectsExternalNestedOrNonWaveDestinations() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val recordingsDirectory = File(filesDirectory, "recordings").apply { mkdir() }
        val existingWave = File(recordingsDirectory, "existing.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val externalWave = File(temporaryFolder.newFolder("external"), "outside.wav")
        val nestedWave = File(File(recordingsDirectory, "nested").apply { mkdir() }, "nested.wav")
        val audioFileWithWrongExtension = File(recordingsDirectory, "recording.mp3")

        assertTrue(PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, existingWave.path))
        assertFalse(PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, externalWave.path))
        assertFalse(PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, nestedWave.path))
        assertFalse(
            PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, audioFileWithWrongExtension.path),
        )
    }

    @Test
    fun rejectsDestinationWhenMoreThanFilesDirectoryIsMissing() {
        val missingAppDataDirectory = File(temporaryFolder.root, "missing-app-data")
        val filesDirectory = File(missingAppDataDirectory, "files")
        val recordingsDirectory = File(filesDirectory, "recordings")
        val destination = File(recordingsDirectory, "new.wav")

        assertFalse(missingAppDataDirectory.exists())
        assertFalse(filesDirectory.exists())
        assertFalse(PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, destination.path))
    }

    @Test
    fun rejectsSymlinkedFilesDirectoryWhenHostAllowsCreatingSymlinks() {
        val targetFilesDirectory = temporaryFolder.newFolder("target-files")
        val filesDirectory = File(temporaryFolder.root, "files-link")
        val recordingsDirectory = File(filesDirectory, "recordings")
        val destination = File(recordingsDirectory, "new.wav")

        createSymlinkOrSkipIfHostPreventsIt(filesDirectory.toPath(), targetFilesDirectory.toPath())

        assertFalse(PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, destination.path))
    }

    @Test
    fun acceptsCanonicalAliasOfPrivateRecordingsParentWhenHostAllowsCreatingSymlinks() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val recordingsDirectory = File(filesDirectory, "recordings").apply { mkdir() }
        val recordingsAlias = File(temporaryFolder.root, "recordings-alias")
        val destination = File(recordingsAlias, "new.wav")

        createSymlinkOrSkipIfHostPreventsIt(recordingsAlias.toPath(), recordingsDirectory.toPath())

        assertFalse(destination.exists())
        assertTrue(PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, destination.path))
    }

    @Test
    fun rejectsSymlinkedWaveLeafWhenHostAllowsCreatingSymlinks() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val recordingsDirectory = File(filesDirectory, "recordings").apply { mkdir() }
        val externalTarget = File(temporaryFolder.newFolder("external"), "target.wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val linkedDestination = File(recordingsDirectory, "linked.wav")

        createSymlinkOrSkipIfHostPreventsIt(linkedDestination.toPath(), externalTarget.toPath())

        assertFalse(PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, linkedDestination.path))
    }

    @Test
    fun rejectsSymlinkedRecordingsDirectoryWhenHostAllowsCreatingSymlinks() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val externalDirectory = temporaryFolder.newFolder("external-recordings")
        val recordingsDirectory = File(filesDirectory, "recordings")
        val destination = File(recordingsDirectory, "linked-directory.wav")

        createSymlinkOrSkipIfHostPreventsIt(recordingsDirectory.toPath(), externalDirectory.toPath())

        assertFalse(PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, destination.path))
    }

    private fun createSymlinkOrSkipIfHostPreventsIt(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (error: Exception) {
            val reason = (error as? FileSystemException)?.reason.orEmpty()
            val hostPreventsSymlinks = error is UnsupportedOperationException ||
                error is SecurityException ||
                error is AccessDeniedException ||
                listOf("privilege", "privilegio", "permission denied", "access denied", "operation not permitted", "operation not supported")
                    .any { marker -> reason.contains(marker, ignoreCase = true) }
            if (!hostPreventsSymlinks) throw error
            assumeNoException("Host does not permit creating a symlink for this test", error)
        }
    }
}
