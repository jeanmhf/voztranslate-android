package com.voztranslate.app.recording

import com.voztranslate.app.domain.AudioFrame
import com.voztranslate.app.domain.AudioSource
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivatePcmWavWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesPlayablePcmWaveHeaderAfterEachAppendAndFinish() {
        val recordingsDirectory = temporaryFolder.newFolder("recordings")
        val writer = PrivatePcmWavWriter.create(recordingsDirectory, "session_01-abc")
        val expectedPcm = byteArrayOf(0x34, 0x12, 0xFE.toByte(), 0xFF.toByte(), 0, 0x80.toByte())

        writer.append(frame(shortArrayOf(0x1234, -2, Short.MIN_VALUE), sequence = 0))
        assertPlayableWave(writer.file, expectedDataBytes = expectedPcm.size)

        writer.append(frame(shortArrayOf(Short.MAX_VALUE), sequence = 1))
        writer.finish()
        writer.finish() // Finishing an already closed writer is safe.

        assertPlayableWave(writer.file, expectedDataBytes = expectedPcm.size + 2)
        assertArrayEquals(
            expectedPcm + byteArrayOf(0xFF.toByte(), 0x7F),
            writer.file.readBytes().copyOfRange(44, writer.file.length().toInt()),
        )
    }

    @Test
    fun rejectsMissingAndDuplicateFrameSequenceNumbersWithoutAdvancingWriter() {
        val recordingsDirectory = temporaryFolder.newFolder("recordings")
        val writer = PrivatePcmWavWriter.create(recordingsDirectory, "ordered")
        try {
            val missingFirstFrame = assertThrows(IOException::class.java) {
                writer.append(frame(shortArrayOf(1), sequence = 1))
            }
            assertTrue(missingFirstFrame.message!!.contains("se esperaba el frame PCM 0", ignoreCase = true))

            writer.append(frame(shortArrayOf(1), sequence = 0))
            val duplicate = assertThrows(IOException::class.java) {
                writer.append(frame(shortArrayOf(2), sequence = 0))
            }
            assertTrue(duplicate.message!!.contains("se esperaba el frame PCM 1", ignoreCase = true))

            writer.append(frame(shortArrayOf(3), sequence = 1))
            writer.finish()
            assertEquals(4L, readUnsignedLittleEndian(writer.file, 40))
            assertArrayEquals(byteArrayOf(1, 0, 3, 0), writer.file.readBytes().copyOfRange(44, 48))
        } finally {
            writer.abortAndDelete()
        }
    }

    @Test
    fun repairsInterruptedHeaderUsingCompletePcmBytesAlreadyOnDisk() {
        val recordingsDirectory = temporaryFolder.newFolder("recordings")
        val writer = PrivatePcmWavWriter.create(recordingsDirectory, "interrupted")
        writer.append(frame(shortArrayOf(0x1234, -2), sequence = 0))
        writer.finish()

        // Model a process interruption while publishing chunk lengths: the samples survived,
        // while both size fields were left at stale values.
        RandomAccessFile(writer.file, "rw").use { file ->
            file.seek(4)
            file.write(byteArrayOf(0, 0, 0, 0))
            file.seek(40)
            file.write(byteArrayOf(0, 0, 0, 0))
        }

        assertTrue(PrivatePcmWavWriter.recoverInterruptedFile(writer.file))

        assertPlayableWave(writer.file, expectedDataBytes = 4)
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0xFE.toByte(), 0xFF.toByte()),
            writer.file.readBytes().copyOfRange(44, 48),
        )
    }

    @Test
    fun rejectsInvalidSessionIdsBeforeCreatingAnyAudioFile() {
        val recordingsDirectory = temporaryFolder.newFolder("recordings")

        assertThrows(IOException::class.java) {
            PrivatePcmWavWriter.create(recordingsDirectory, "../escape")
        }
        assertThrows(IOException::class.java) {
            PrivatePcmWavWriter.create(recordingsDirectory, "")
        }

        assertTrue(recordingsDirectory.listFiles().orEmpty().isEmpty())
        assertFalse(File(temporaryFolder.root, "escape.wav").exists())
    }

    @Test
    fun recoveryRejectsNonPcmWaveFormat() {
        val recordingsDirectory = temporaryFolder.newFolder("recordings")
        val writer = PrivatePcmWavWriter.create(recordingsDirectory, "unsupported")
        writer.append(frame(shortArrayOf(7), sequence = 0))
        writer.finish()
        RandomAccessFile(writer.file, "rw").use { it.seek(20); it.write(3) }

        assertFalse(PrivatePcmWavWriter.recoverInterruptedFile(writer.file))
    }

    @Test
    fun inspectionTreatsFileNotFoundAsAbsentOnlyWhenProbeConfirmsAbsence() {
        val missingFile = File(temporaryFolder.root, "missing.wav")

        val presence = PrivatePcmWavWriter.inspectPcmPayload(missingFile) { candidate ->
            assertEquals(missingFile, candidate)
            true
        }

        assertEquals(PcmPayloadPresence.ABSENT, presence)
    }

    @Test
    fun inspectionReturnsUnknownWhenFileNotFoundAbsenceProbeIsFalse() {
        val missingFile = File(temporaryFolder.root, "missing.wav")

        val presence = PrivatePcmWavWriter.inspectPcmPayload(missingFile) { false }

        assertEquals(PcmPayloadPresence.UNKNOWN, presence)
    }

    @Test
    fun inspectionReturnsUnknownWhenFileNotFoundAbsenceProbeThrows() {
        val missingFile = File(temporaryFolder.root, "missing.wav")

        val presence = PrivatePcmWavWriter.inspectPcmPayload(missingFile) {
            throw SecurityException("cannot determine whether the file exists")
        }

        assertEquals(PcmPayloadPresence.UNKNOWN, presence)
    }

    @Test
    fun failingWriterCancelsProducerBlockedByOneFrameBufferAndKeepsCommittedPrefix() = runBlocking {
        val recordingsDirectory = temporaryFolder.newFolder("recordings")
        val delegate = PrivatePcmWavWriter.create(recordingsDirectory, "blocked-drain")
        val source = ChannelAudioSource(capacity = 1)
        val writerFailure = IOException("simulated disk write failure")
        val wrappedWriter = BlockAndFailOnFirstFrameWriter(delegate, writerFailure)
        val consumer = PcmFrameConsumer(source, wrappedWriter)
        val consumerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val consumerJob = consumer.start(consumerScope)
        val sendingThirdFrame = CompletableDeferred<Unit>()
        val thirdFrameSent = CompletableDeferred<Unit>()
        val producerResult = CompletableDeferred<Throwable?>()
        val producer = launch {
            try {
                source.channel.send(frame(shortArrayOf(0x1234), sequence = 0))
                source.channel.send(frame(shortArrayOf(0x2345), sequence = 1))
                sendingThirdFrame.complete(Unit)
                source.channel.send(frame(shortArrayOf(0x3456), sequence = 2))
                thirdFrameSent.complete(Unit)
                producerResult.complete(null)
            } catch (error: Throwable) {
                producerResult.complete(error)
            }
        }

        try {
            withTimeout(5_000) { wrappedWriter.firstAppendStarted.await() }
            withTimeout(5_000) { sendingThirdFrame.await() }
            yield()
            assertFalse("the producer must be suspended sending frame 2", thirdFrameSent.isCompleted)
            assertFalse("writer must hold the collector while the one-frame queue is full", producer.isCompleted)
            assertFalse("the bounded channel must already contain a queued frame", source.channel.trySend(frame(shortArrayOf(9), 3)).isSuccess)

            wrappedWriter.releaseFirstAppend.complete(Unit)

            withTimeout(5_000) {
                producer.join()
                consumerJob.join()
            }
            assertTrue(consumer.streamFailure.await() === writerFailure)
            assertTrue(consumer.writerFailure === writerFailure)
            assertTrue(producerResult.await() is CancellationException)

            // This is the same cleanup operation the capture owner uses after the consumer fails.
            wrappedWriter.finishPreservingPrefix()
            assertPlayableWave(delegate.file, expectedDataBytes = 2)
            assertArrayEquals(byteArrayOf(0x34, 0x12), delegate.file.readBytes().copyOfRange(44, 46))
        } finally {
            wrappedWriter.releaseFirstAppend.complete(Unit)
            runCatching { producer.cancelAndJoin() }
            runCatching { consumerJob.cancelAndJoin() }
            consumerScope.cancel()
            runCatching { wrappedWriter.abortAndDelete() }
        }
    }

    private fun frame(samples: ShortArray, sequence: Long) = AudioFrame(
        samples = samples,
        sampleRateHz = PrivatePcmWavWriter.SAMPLE_RATE_HZ,
        capturedAtNanos = sequence * 100_000_000L,
        sequenceNumber = sequence,
    )

    private fun assertPlayableWave(file: File, expectedDataBytes: Int) {
        val bytes = file.readBytes()
        assertEquals(44 + expectedDataBytes, bytes.size)
        assertEquals("RIFF", ascii(bytes, 0, 4))
        assertEquals(36L + expectedDataBytes, readUnsignedLittleEndian(bytes, 4))
        assertEquals("WAVE", ascii(bytes, 8, 4))
        assertEquals("fmt ", ascii(bytes, 12, 4))
        assertEquals(16L, readUnsignedLittleEndian(bytes, 16))
        assertEquals(1, readUnsignedLittleEndianShort(bytes, 20)) // PCM
        assertEquals(1, readUnsignedLittleEndianShort(bytes, 22)) // Mono
        assertEquals(16_000L, readUnsignedLittleEndian(bytes, 24))
        assertEquals(32_000L, readUnsignedLittleEndian(bytes, 28))
        assertEquals(2, readUnsignedLittleEndianShort(bytes, 32))
        assertEquals(16, readUnsignedLittleEndianShort(bytes, 34))
        assertEquals("data", ascii(bytes, 36, 4))
        assertEquals(expectedDataBytes.toLong(), readUnsignedLittleEndian(bytes, 40))
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int) =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun readUnsignedLittleEndian(file: File, offset: Long): Long =
        readUnsignedLittleEndian(file.readBytes(), offset.toInt())

    private fun readUnsignedLittleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUnsignedLittleEndian(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private class ChannelAudioSource(capacity: Int) : AudioSource {
        val channel = Channel<AudioFrame>(capacity)

        override fun frames(): Flow<AudioFrame> = channel.receiveAsFlow()

        override suspend fun start() = Unit

        override suspend fun stop() {
            channel.close()
        }

        override suspend fun abort() {
            channel.cancel()
        }

        override fun cancelPendingFrames(cause: Throwable) {
            val cancellation = CancellationException("test source canceled pending PCM frames")
            cancellation.initCause(cause)
            channel.cancel(cancellation)
        }
    }

    private class BlockAndFailOnFirstFrameWriter(
        private val delegate: PrivatePcmWavWriter,
        private val failure: IOException,
    ) : PcmAudioWriter {
        override val file: File = delegate.file
        val firstAppendStarted = CompletableDeferred<Unit>()
        val releaseFirstAppend = CompletableDeferred<Unit>()
        private var appendFailed = false

        override fun append(frame: AudioFrame) {
            if (appendFailed) throw AssertionError("Unexpected PCM frame ${frame.sequenceNumber} after writer failure")
            delegate.append(frame)
            appendFailed = true
            firstAppendStarted.complete(Unit)
            runBlocking { releaseFirstAppend.await() }
            throw failure
        }

        override fun finish() = delegate.finish()

        override fun finishPreservingPrefix() = delegate.finishPreservingPrefix()

        override fun abortAndDelete() = delegate.abortAndDelete()
    }
}
