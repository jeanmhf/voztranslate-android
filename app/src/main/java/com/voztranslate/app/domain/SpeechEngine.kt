package com.voztranslate.app.domain

import kotlinx.coroutines.flow.Flow

interface SpeechEngine {
    /** Partial events may be revised; a final event closes the stable segment ID. */
    fun transcribe(frames: Flow<AudioFrame>, sourceLanguage: Language): Flow<TranscriptSegment>
}
