package com.voztranslate.app.ai

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MoonshineModelIntegrityVerifierTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun verifiesKnownCrc32cVectorAndExpectedSize() {
        val root = temporaryFolder.newFolder("models")
        val bytes = "123456789".toByteArray(StandardCharsets.US_ASCII)
        File(root, "encoder.bin").writeBytes(bytes)

        val isValid = MoonshineModelIntegrityVerifier.verify(
            root,
            listOf(entry("encoder.bin", bytes.size.toLong(), checksum = "4waSgw==")),
        )

        assertTrue("CRC32C of the standard 123456789 vector must match the manifest", isValid)
        assertTrue(File(root, "encoder.bin").isFile)
    }

    @Test
    fun removesFileWhenManifestSizeDoesNotMatch() {
        val root = temporaryFolder.newFolder("models")
        val file = File(root, "decoder.bin").apply { writeText("123456789", StandardCharsets.US_ASCII) }

        val isValid = MoonshineModelIntegrityVerifier.verify(
            root,
            listOf(entry("decoder.bin", expectedSize = 8L, checksum = "4waSgw==")),
        )

        assertFalse(isValid)
        assertFalse("a definitely corrupt size must be removed for redownload", file.exists())
    }

    @Test
    fun removesFileWhenCrc32cDoesNotMatch() {
        val root = temporaryFolder.newFolder("models")
        val file = File(root, "decoder.bin").apply { writeText("123456789", StandardCharsets.US_ASCII) }

        val isValid = MoonshineModelIntegrityVerifier.verify(
            root,
            listOf(entry("decoder.bin", expectedSize = 9L, checksum = "AAAAAA==")),
        )

        assertFalse(isValid)
        assertFalse("a checksum-mismatched model file must be removed", file.exists())
    }

    @Test
    fun missingModelFileReturnsFalseWithoutCreatingOrDeletingOtherFiles() {
        val root = temporaryFolder.newFolder("models")
        val unrelated = File(root, "other.bin").apply { writeText("keep") }

        val isValid = MoonshineModelIntegrityVerifier.verify(
            root,
            listOf(entry("missing.bin", expectedSize = 9L, checksum = "4waSgw==")),
        )

        assertFalse(isValid)
        assertFalse(File(root, "missing.bin").exists())
        assertTrue(unrelated.isFile)
    }

    @Test
    fun rejectsTraversalPathWithoutDeletingExternalFile() {
        val parent = temporaryFolder.root
        val root = File(parent, "models").apply { mkdir() }
        val outside = File(parent, "outside.bin").apply { writeText("protected") }

        assertThrows(IOException::class.java) {
            MoonshineModelIntegrityVerifier.verify(
                root,
                listOf(entry("../outside.bin", expectedSize = 9L, checksum = "4waSgw==")),
            )
        }

        assertTrue(outside.isFile)
        assertEquals("protected", outside.readText())
    }

    @Test
    fun rejectsMalformedManifestMetadataWithoutDeletingModel() {
        val root = temporaryFolder.newFolder("models")
        val file = File(root, "encoder.bin").apply { writeText("123456789", StandardCharsets.US_ASCII) }

        assertThrows(IOException::class.java) {
            MoonshineModelIntegrityVerifier.verify(
                root,
                listOf(entry("encoder.bin", expectedSize = 9L, checksum = "not-base64!")),
            )
        }
        assertTrue("invalid catalog metadata must not delete the file", file.isFile)

        assertThrows(IOException::class.java) {
            MoonshineModelIntegrityVerifier.verify(
                root,
                listOf(entry("encoder.bin", expectedSize = -1L, checksum = "4waSgw==")),
            )
        }
        assertTrue(file.isFile)
    }

    @Test
    fun rejectsUnsupportedChecksumTypeAndEmptyManifest() {
        val root = temporaryFolder.newFolder("models")
        val file = File(root, "encoder.bin").apply { writeText("123456789", StandardCharsets.US_ASCII) }

        assertThrows(IOException::class.java) {
            MoonshineModelIntegrityVerifier.verify(
                root,
                listOf(entry("encoder.bin", expectedSize = 9L, checksum = "4waSgw==", checksumType = "sha256")),
            )
        }
        assertTrue(file.isFile)
        assertThrows(IOException::class.java) {
            MoonshineModelIntegrityVerifier.verify(root, emptyList())
        }
    }

    private fun entry(
        relativeName: String,
        expectedSize: Long?,
        checksum: String,
        checksumType: String = "crc32c",
    ) = MoonshineModelFileEntry(relativeName, expectedSize, checksum, checksumType)
}
