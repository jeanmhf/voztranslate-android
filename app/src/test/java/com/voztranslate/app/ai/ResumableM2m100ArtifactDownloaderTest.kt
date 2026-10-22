package com.voztranslate.app.ai

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class ResumableM2m100ArtifactDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun downloadsFreshArtifactAndAtomicallyPromotesOnlyVerifiedBytes() = runBlocking {
        val root = temporaryFolder.newFolder("models")
        val contents = bytes("fresh model")
        val artifact = artifact(contents)
        val response = StubResponse(HttpURLConnection.HTTP_OK, body = contents)
        val transport = StubTransport(response)
        val progress = mutableListOf<Long>()

        ResumableM2m100ArtifactDownloader(transport).download(root, artifact) { progress += it }

        assertEquals(listOf(0L), transport.offsets)
        assertArrayEquals(contents, File(root, artifact.relativePath).readBytes())
        assertTrue(M2m100ArtifactVerifier.verifyFile(File(root, artifact.relativePath), artifact))
        assertFalse(partialFile(root, artifact).exists())
        assertEquals(listOf(contents.size.toLong()), progress)
        assertTrue(response.bodyOpened)
        assertEquals(1, response.closeCount)
    }

    @Test
    fun resumesValid206AtTheExistingPartialOffset() = runBlocking {
        val root = temporaryFolder.newFolder("models")
        val contents = bytes("resumable bytes")
        val artifact = artifact(contents)
        val split = 6
        val partial = partialFile(root, artifact).apply {
            parentFile.mkdirs()
            writeBytes(contents.copyOfRange(0, split))
        }
        val response = StubResponse(
            statusCode = HttpURLConnection.HTTP_PARTIAL,
            contentRangeStart = split.toLong(),
            contentRangeTotal = contents.size.toLong(),
            body = contents.copyOfRange(split, contents.size),
        )
        val transport = StubTransport(response)

        ResumableM2m100ArtifactDownloader(transport).download(root, artifact)

        assertEquals(listOf(split.toLong()), transport.offsets)
        assertArrayEquals(contents, File(root, artifact.relativePath).readBytes())
        assertTrue(M2m100ArtifactVerifier.verifyFile(File(root, artifact.relativePath), artifact))
        assertFalse(partial.exists())
        assertEquals(1, response.closeCount)
    }

    @Test
    fun serverIgnoringRangeWith200RestartsInsteadOfAppendingDuplicateBytes() = runBlocking {
        val root = temporaryFolder.newFolder("models")
        val contents = bytes("complete response")
        val artifact = artifact(contents)
        val partial = partialFile(root, artifact).apply {
            parentFile.mkdirs()
            writeBytes(contents.copyOfRange(0, 4))
        }
        val response = StubResponse(HttpURLConnection.HTTP_OK, body = contents)
        val transport = StubTransport(response)

        ResumableM2m100ArtifactDownloader(transport).download(root, artifact)

        assertEquals(listOf(4L), transport.offsets)
        assertArrayEquals(contents, File(root, artifact.relativePath).readBytes())
        assertTrue(M2m100ArtifactVerifier.verifyFile(File(root, artifact.relativePath), artifact))
        assertFalse(partial.exists())
    }

    @Test
    fun rejects206WithWrongStartOrTotalAndDeletesUntrustedPartial() = runBlocking {
        val contents = bytes("range target")
        val artifact = artifact(contents)
        val split = 4

        listOf(
            StubResponse(HttpURLConnection.HTTP_PARTIAL, contentRangeStart = 2L, contentRangeTotal = contents.size.toLong(), body = bytes("tail")),
            StubResponse(HttpURLConnection.HTTP_PARTIAL, contentRangeStart = split.toLong(), contentRangeTotal = contents.size + 1L, body = bytes("tail")),
        ).forEachIndexed { index, response ->
            val root = temporaryFolder.newFolder("bad-range-$index")
            val partial = partialFile(root, artifact).apply {
                parentFile.mkdirs()
                writeBytes(contents.copyOfRange(0, split))
            }
            val transport = StubTransport(response)

            assertThrows(IOException::class.java) {
                runBlocking { ResumableM2m100ArtifactDownloader(transport).download(root, artifact) }
            }

            assertEquals(listOf(split.toLong()), transport.offsets)
            assertFalse(partial.exists())
            assertFalse(File(root, artifact.relativePath).exists())
            assertEquals(1, response.closeCount)
        }
    }

    @Test
    fun rejectsShaMismatchAndRemovesTheCompletedPartial() = runBlocking {
        val root = temporaryFolder.newFolder("models")
        val actual = bytes("same size bad hash")
        val artifact = artifact(actual, sha256 = "0".repeat(64))
        val response = StubResponse(HttpURLConnection.HTTP_OK, body = actual)
        val transport = StubTransport(response)

        val error = assertThrows(IOException::class.java) {
            runBlocking { ResumableM2m100ArtifactDownloader(transport).download(root, artifact) }
        }

        assertTrue(error.message.orEmpty().contains("SHA-256"))
        assertFalse(partialFile(root, artifact).exists())
        assertFalse(File(root, artifact.relativePath).exists())
        assertEquals(1, response.closeCount)
    }

    @Test
    fun shortBodyReportsExpectedAndObservedSizeButRetainsPartialForRetry() = runBlocking {
        val root = temporaryFolder.newFolder("models")
        val contents = bytes("expected entire body")
        val artifact = artifact(contents)
        val partialContents = contents.copyOfRange(0, 5)
        val response = StubResponse(HttpURLConnection.HTTP_OK, body = partialContents)
        val transport = StubTransport(response)

        val error = assertThrows(IOException::class.java) {
            runBlocking { ResumableM2m100ArtifactDownloader(transport).download(root, artifact) }
        }

        assertTrue(error.message.orEmpty().contains("${partialContents.size} de ${contents.size}"))
        assertArrayEquals(partialContents, partialFile(root, artifact).readBytes())
        assertFalse(File(root, artifact.relativePath).exists())
    }

    @Test
    fun cancellationRetainsWrittenPrefixAndClosesResponse() = runBlocking {
        val root = temporaryFolder.newFolder("models")
        val contents = bytes("cancel after first chunk")
        val artifact = artifact(contents)
        val response = StubResponse(
            statusCode = HttpURLConnection.HTTP_OK,
            input = ChunkedInputStream(contents, chunkSize = 3),
        )
        val transport = StubTransport(response)
        val cancellation = CancellationException("cancel deterministic download")

        val observed = try {
            ResumableM2m100ArtifactDownloader(transport).download(root, artifact) { downloaded ->
                if (downloaded >= 3L) throw cancellation
            }
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, observed)
        assertArrayEquals(contents.copyOfRange(0, 3), partialFile(root, artifact).readBytes())
        assertFalse(File(root, artifact.relativePath).exists())
        assertEquals(1, response.closeCount)
    }

    @Test
    fun completeVerifiedPartialIsPromotedWhenServerReturns416() = runBlocking {
        val root = temporaryFolder.newFolder("models")
        val contents = bytes("already complete")
        val artifact = artifact(contents)
        val partial = partialFile(root, artifact).apply {
            parentFile.mkdirs()
            writeBytes(contents)
        }
        val response = StubResponse(statusCode = 416, body = bytes("unused"))
        val transport = StubTransport(response)

        ResumableM2m100ArtifactDownloader(transport).download(root, artifact)

        assertEquals(listOf(contents.size.toLong()), transport.offsets)
        assertFalse(response.bodyOpened)
        assertFalse(partial.exists())
        assertArrayEquals(contents, File(root, artifact.relativePath).readBytes())
    }

    private fun artifact(
        contents: ByteArray,
        relativePath: String = "nested/model.onnx",
        size: Long = contents.size.toLong(),
        sha256: String = sha256(contents),
    ) = M2m100ModelArtifact(relativePath, size, sha256)

    private fun partialFile(root: File, artifact: M2m100ModelArtifact) =
        File(root, "${artifact.relativePath}.part")

    private fun bytes(value: String) = value.toByteArray(StandardCharsets.UTF_8)

    private fun sha256(contents: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(contents)
        .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private class StubTransport(private vararg val responses: StubResponse) : M2m100RangeTransport {
        val offsets = mutableListOf<Long>()
        private var responseIndex = 0

        override fun open(url: String, offset: Long): M2m100RangeResponse {
            assertTrue("URL should be pinned to a nonempty artifact path", url.contains("/resolve/") && url.contains("/nested/model.onnx"))
            offsets += offset
            return responses.getOrElse(responseIndex++) { error("No scripted response for offset $offset") }
        }
    }

    private class StubResponse(
        override val statusCode: Int,
        override val contentRangeStart: Long? = null,
        override val contentRangeTotal: Long? = null,
        body: ByteArray = byteArrayOf(),
        private val input: InputStream = ByteArrayInputStream(body),
    ) : M2m100RangeResponse {
        var bodyOpened = false
            private set
        var closeCount = 0
            private set

        override fun openBody(): InputStream {
            bodyOpened = true
            return input
        }

        override fun close() {
            closeCount += 1
            input.close()
        }
    }

    private class ChunkedInputStream(
        bytes: ByteArray,
        private val chunkSize: Int,
    ) : ByteArrayInputStream(bytes) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(length, chunkSize))

        // BufferedInputStream otherwise drains this in-memory stream into its own buffer before
        // returning, so the downloader's first progress callback may see the entire small body.
        override fun available(): Int = 0
    }
}
