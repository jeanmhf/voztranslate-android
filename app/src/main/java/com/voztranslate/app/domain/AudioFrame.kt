package com.voztranslate.app.domain

/** A mono PCM frame delivered by an audio source. Consumers must treat the samples as read-only. */
data class AudioFrame(
    val samples: ShortArray,
    val sampleRateHz: Int,
    val capturedAtNanos: Long,
    /** Zero-based position in the source stream, assigned before delivery. */
    val sequenceNumber: Long = 0,
)
