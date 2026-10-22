package com.voztranslate.app.ai

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class M2m100ArtifactVerifierTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun pinnedArtifactManifestHasExpectedFilesRevisionAndAggregateSize() {
        assertEquals("48f06c0fec544323fcf1546e312a617d962d3653", M2m100ModelArtifacts.REVISION)
        assertEquals(
            setOf(
                "onnx/encoder_model_quantized.onnx",
                "onnx/decoder_model_merged_quantized.onnx",
                "sentencepiece.bpe.model",
                "vocab.json",
            ),
            M2m100ModelArtifacts.files.map(M2m100ModelArtifact::relativePath).toSet(),
        )
        assertEquals(638_116_033L, M2m100ModelArtifacts.totalSizeBytes)
        assertEquals(4, M2m100ModelArtifacts.files.size)
    }

    @Test
    fun verifiesSha256AndExactSizeForAValidManifest() {
        val root = temporaryFolder.newFolder("models")
        val contents = "abc".toByteArray(StandardCharsets.US_ASCII)
        val artifact = artifact(
            "encoder.bin",
            contents,
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        )
        File(root, artifact.relativePath).writeBytes(contents)

        assertTrue(M2m100ArtifactVerifier.verify(root, listOf(artifact)))
        assertTrue(M2m100ArtifactVerifier.verifyFile(File(root, artifact.relativePath), artifact))
    }

    @Test
    fun rejectsSizeAndShaMismatchesWithoutMarkingTheModelReady() {
        val root = temporaryFolder.newFolder("models")
        val contents = "abc".toByteArray(StandardCharsets.US_ASCII)
        val wrongSize = artifact("encoder.bin", contents, sizeBytes = 4L)
        val wrongSha = artifact("encoder.bin", contents, sha256 = "0".repeat(64))
        File(root, "encoder.bin").writeBytes(contents)

        assertFalse(M2m100ArtifactVerifier.verifyFile(File(root, "encoder.bin"), wrongSize))
        assertFalse(M2m100ArtifactVerifier.verifyFile(File(root, "encoder.bin"), wrongSha))
        assertThrows(IllegalStateException::class.java) {
            M2m100ArtifactVerifier.writeReadyMarker(root, listOf(wrongSha))
        }
        assertFalse(File(root, M2m100ModelArtifacts.READY_MARKER).exists())
    }

    @Test
    fun readyReceiptRequiresVerifiedFilesAndMatchesTheManifest() {
        val root = temporaryFolder.newFolder("models")
        val contents = "abc".toByteArray(StandardCharsets.US_ASCII)
        val artifact = artifact("onnx/encoder.bin", contents)
        val modelFile = File(root, artifact.relativePath).apply {
            parentFile.mkdirs()
            writeBytes(contents)
        }

        assertFalse(M2m100ArtifactVerifier.isReady(root, listOf(artifact)))
        M2m100ArtifactVerifier.writeReadyMarker(root, listOf(artifact))
        assertTrue(M2m100ArtifactVerifier.isReady(root, listOf(artifact)))

        val changedManifest = artifact.copy(sha256 = "0".repeat(64))
        assertFalse(M2m100ArtifactVerifier.isReady(root, listOf(changedManifest)))
        modelFile.writeBytes(byteArrayOf(0x61, 0x62))
        assertFalse(M2m100ArtifactVerifier.isReady(root, listOf(artifact)))
    }

    @Test
    fun containedPathResolutionRejectsAbsoluteTraversalAndEmptySegments() {
        val root = temporaryFolder.newFolder("models")
        val contained = M2m100ArtifactVerifier.resolveContained(root, "onnx/encoder.bin")
        assertTrue(contained.canonicalPath.startsWith(root.canonicalPath + File.separator))

        val rejected = listOf("", ".", "../outside.bin", "onnx/../outside.bin", "onnx//encoder.bin", root.absolutePath)
        rejected.forEach { path ->
            assertThrows("must reject $path", IllegalArgumentException::class.java) {
                M2m100ArtifactVerifier.resolveContained(root, path)
            }
        }
    }

    @Test
    fun verifyRejectsEscapingPathEvenWhenAnEarlierManifestFileIsMissing() {
        val parent = temporaryFolder.root
        val root = File(parent, "models").apply { mkdir() }
        val outside = File(parent, "outside.bin").apply { writeText("keep") }
        val firstMissing = artifact("missing.bin", byteArrayOf(1))
        val escaping = artifact("../outside.bin", "keep".toByteArray(StandardCharsets.UTF_8))

        assertThrows(IllegalArgumentException::class.java) {
            M2m100ArtifactVerifier.verify(root, listOf(firstMissing, escaping))
        }

        assertTrue(outside.isFile)
        assertEquals("keep", outside.readText())
    }

    @Test
    fun rejectsEmptyDuplicateOrMalformedManifestEntries() {
        val root = temporaryFolder.newFolder("models")
        assertThrows(IllegalArgumentException::class.java) {
            M2m100ArtifactVerifier.verify(root, emptyList())
        }

        val contents = "abc".toByteArray(StandardCharsets.US_ASCII)
        val valid = artifact("one.bin", contents)
        assertThrows(IllegalArgumentException::class.java) {
            M2m100ArtifactVerifier.verify(root, listOf(valid, valid))
        }
        assertThrows(IllegalArgumentException::class.java) {
            M2m100ArtifactVerifier.verify(root, listOf(valid.copy(sizeBytes = 0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            M2m100ArtifactVerifier.verify(root, listOf(valid.copy(sha256 = "not-a-sha")))
        }
    }

    private fun artifact(
        relativePath: String,
        contents: ByteArray,
        sizeBytes: Long = contents.size.toLong(),
        sha256: String = sha256(contents),
    ) = M2m100ModelArtifact(relativePath, sizeBytes, sha256)

    private fun sha256(contents: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(contents)
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
