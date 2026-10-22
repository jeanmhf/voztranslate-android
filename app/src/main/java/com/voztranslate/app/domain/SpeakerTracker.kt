package com.voztranslate.app.domain

import kotlinx.coroutines.flow.Flow

interface SpeakerTracker {
    /** Speaker IDs are scoped to one recording; recent assignments may be revised. */
    fun identifySpeakers(frames: Flow<AudioFrame>): Flow<SpeakerTurn>
}
