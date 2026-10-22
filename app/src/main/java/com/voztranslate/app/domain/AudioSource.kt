package com.voztranslate.app.domain

import kotlinx.coroutines.flow.Flow

interface AudioSource {
    /** Emits one ordered PCM stream until capture is stopped or interrupted. */
    fun frames(): Flow<AudioFrame>

    /** Starts a source once; its frame stream should be collected before starting. */
    suspend fun start()

    /** Stops capture and releases the underlying audio input. */
    suspend fun stop()

    /** Aborts capture and may discard frames that were queued but not yet consumed. */
    suspend fun abort()

    /** Cancels queued frame delivery without releasing the native input, so [stop] can clean it up. */
    fun cancelPendingFrames(cause: Throwable) = Unit
}
