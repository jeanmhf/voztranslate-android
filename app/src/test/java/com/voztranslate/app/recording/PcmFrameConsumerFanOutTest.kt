package com.voztranslate.app.recording

import com.voztranslate.app.domain.AudioFrame
import com.voztranslate.app.domain.AudioSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmFrameConsumerFanOutTest {
    @Test
    fun writesAndForwardsTheSameFramesInSourceOrder() = runBlocking {
        val expectedFrames = listOf(frame(0), frame(1), frame(2))
        val source = ChannelAudioSource()
        val writer = RecordingWriter()
        val speech = Channel<AudioFrame>(Channel.UNLIMITED)
        val consumer = PcmFrameConsumer(source, writer, speech)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = consumer.start(scope)

        try {
            for (frame in expectedFrames) source.channel.send(frame)
            source.channel.close()
            withTimeout(5_000) { job.join() }
            val forwardedFrames = buildList {
                for (audioFrame in speech) add(audioFrame)
            }

            assertEquals(null, consumer.streamFailure.await())
            assertEquals(listOf(0L, 1L, 2L), writer.frames.map(AudioFrame::sequenceNumber))
            assertEquals(listOf(0L, 1L, 2L), forwardedFrames.map(AudioFrame::sequenceNumber))
            expectedFrames.indices.forEach { index ->
                assertSame("writer frame $index", expectedFrames[index], writer.frames[index])
                assertSame("ASR frame $index", expectedFrames[index], forwardedFrames[index])
            }
        } finally {
            source.channel.cancel()
            job.cancel()
            speech.cancel()
            scope.cancel()
        }
    }

    @Test
    fun writerFailureIsReportedBeforeThatFrameCanReachAsr() = runBlocking {
        val writeFailure = IOException("deterministic writer failure")
        val frames = listOf(frame(0), frame(1), frame(2))
        val source = ChannelAudioSource()
        val writer = RecordingWriter(failingSequence = 1, failure = writeFailure)
        val speech = Channel<AudioFrame>(Channel.UNLIMITED)
        val consumer = PcmFrameConsumer(source, writer, speech)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = consumer.start(scope)

        try {
            for (frame in frames) source.channel.send(frame)
            source.channel.close()
            withTimeout(5_000) { job.join() }

            assertSame(writeFailure, consumer.writerFailure)
            assertSame(writeFailure, consumer.streamFailure.await())
            assertEquals(listOf(0L, 1L), writer.frames.map(AudioFrame::sequenceNumber))
            val firstAsrFrame = speech.receiveCatching().getOrNull()
            assertSame(frames[0], firstAsrFrame)
            val closedResult = speech.receiveCatching()
            assertTrue(closedResult.isClosed)
            assertSame(writeFailure, closedResult.exceptionOrNull())
            assertEquals("failed frame must not be sent to ASR", listOf(0L), listOfNotNull(firstAsrFrame).map(AudioFrame::sequenceNumber))
        } finally {
            source.channel.cancel()
            job.cancel()
            speech.cancel()
            scope.cancel()
        }
    }

    private fun frame(sequence: Long) = AudioFrame(
        samples = shortArrayOf((sequence + 100).toShort()),
        sampleRateHz = 16_000,
        capturedAtNanos = sequence * 10_000_000L,
        sequenceNumber = sequence,
    )

    private class ChannelAudioSource : AudioSource {
        val channel = Channel<AudioFrame>(Channel.UNLIMITED)

        override fun frames(): Flow<AudioFrame> = channel.receiveAsFlow()

        override suspend fun start() = Unit

        override suspend fun stop() {
            channel.close()
        }

        override suspend fun abort() {
            channel.cancel()
        }
    }

    private class RecordingWriter(
        private val failingSequence: Long? = null,
        private val failure: Throwable? = null,
    ) : PcmAudioWriter {
        override val file: File = File("test-only-pcm-writer.wav")
        val frames = mutableListOf<AudioFrame>()

        override fun append(frame: AudioFrame) {
            frames += frame
            if (frame.sequenceNumber == failingSequence) throw requireNotNull(failure)
        }

        override fun finish() = Unit
        override fun finishPreservingPrefix() = Unit
        override fun abortAndDelete() = Unit
    }
}
