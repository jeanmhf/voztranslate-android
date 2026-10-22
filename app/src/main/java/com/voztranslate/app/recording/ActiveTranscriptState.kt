package com.voztranslate.app.recording

import com.voztranslate.app.domain.TranscriptSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ActiveTranscriptState(
    val sessionId: String? = null,
    val segments: List<TranscriptSegment> = emptyList(),
)

/** In-process view of the same revisions that the service has committed to Room. */
object ActiveTranscriptStateStore {
    private val mutableState = MutableStateFlow(ActiveTranscriptState())
    val state: StateFlow<ActiveTranscriptState> = mutableState.asStateFlow()

    internal fun begin(sessionId: String) {
        mutableState.value = ActiveTranscriptState(sessionId = sessionId)
    }

    /** Clear only the visible session that the repository confirmed it deleted. */
    internal fun clearIfSessionDeleted(sessionId: String) {
        mutableState.update { current ->
            if (current.sessionId == sessionId) ActiveTranscriptState() else current
        }
    }

    internal fun upsert(sessionId: String, segment: TranscriptSegment) {
        mutableState.update { current ->
            if (current.sessionId != sessionId) return@update current
            val priorIndex = current.segments.indexOfFirst { it.id == segment.id }
            when {
                priorIndex < 0 -> current.copy(segments = current.segments + segment)
                segment.revision <= current.segments[priorIndex].revision -> current
                else -> current.copy(
                    segments = current.segments.toMutableList().also { it[priorIndex] = segment },
                )
            }
        }
    }
}
