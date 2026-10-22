package com.voztranslate.app.recording

import com.voztranslate.app.domain.AudioFrame
import java.io.File

/** Writer seam for ordered PCM consumption and deterministic pipeline validation. */
interface PcmAudioWriter {
    val file: File

    fun append(frame: AudioFrame)

    fun finish()

    /** Closes the writer while retaining only complete bytes already committed by append. */
    fun finishPreservingPrefix()

    fun abortAndDelete()
}
