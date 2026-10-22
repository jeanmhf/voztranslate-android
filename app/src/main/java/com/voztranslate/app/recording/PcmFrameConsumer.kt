package com.voztranslate.app.recording

import com.voztranslate.app.domain.AudioSource
import com.voztranslate.app.domain.AudioFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.collect

/** Consumes one source stream in order and makes consumer failures visible to the capture owner. */
class PcmFrameConsumer(
    private val source: AudioSource,
    private val writer: PcmAudioWriter,
    private val framesForSpeech: SendChannel<AudioFrame>? = null,
) {
    private val mutableStreamFailure = CompletableDeferred<Throwable?>()
    val streamFailure: Deferred<Throwable?> = mutableStreamFailure

    @Volatile
    var writerFailure: Throwable? = null
        private set

    fun start(scope: CoroutineScope): Job = scope.launch {
        var terminalError: Throwable? = null
        try {
            source.frames().collect { frame ->
                try {
                    writer.append(frame)
                } catch (error: Throwable) {
                    writerFailure = error
                    throw error
                }
                // This is a fan-out after the durable audio write, from the single source
                // collector. Bounded speech backpressure therefore never reorders or drops PCM.
                framesForSpeech?.send(frame)
            }
            mutableStreamFailure.complete(null)
        } catch (error: Throwable) {
            terminalError = error
            // Unblock a producer suspended in a bounded channel send. The capture owner still
            // calls source.stop() to release AudioRecord after this consumer has failed.
            runCatching { source.cancelPendingFrames(error) }
            mutableStreamFailure.complete(error)
        } finally {
            if (terminalError == null) framesForSpeech?.close() else framesForSpeech?.close(terminalError)
        }
    }
}
