package com.voztranslate.app.data

import java.io.File
import java.nio.file.Files
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
    fun acceptsNewWaveDestinationWhenRecordingsDirectoryAndLeafDoNotExist() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val recordingsDirectory = File(filesDirectory, "recordings")
        val destination = File(recordingsDirectory, "new.wav")

        assertFalse(recordingsDirectory.exists())
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
    fun rejectsSymlinkedWaveLeafWhenHostAllowsCreatingSymlinks() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val recordingsDirectory = File(filesDirectory, "recordings").apply { mkdir() }
        val externalTarget = File(temporaryFolder.newFolder("external"), "target.wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val linkedDestination = File(recordingsDirectory, "linked.wav")

        try {
            Files.createSymbolicLink(linkedDestination.toPath(), externalTarget.toPath())
        } catch (error: Exception) {
            assumeNoException("Host does not permit creating a symlink for this test", error)
        }

        assertFalse(PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, linkedDestination.path))
    }

    @Test
    fun rejectsSymlinkedRecordingsDirectoryWhenHostAllowsCreatingSymlinks() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val externalDirectory = temporaryFolder.newFolder("external-recordings")
        val recordingsDirectory = File(filesDirectory, "recordings")
        val destination = File(recordingsDirectory, "linked-directory.wav")

        try {
            Files.createSymbolicLink(recordingsDirectory.toPath(), externalDirectory.toPath())
        } catch (error: Exception) {
            assumeNoException("Host does not permit creating a symlink for this test", error)
        }

        assertFalse(PrivateRecordingPathValidator.isValidDestination(recordingsDirectory, destination.path))
    }
}
