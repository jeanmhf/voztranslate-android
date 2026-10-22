package com.voztranslate.app.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RecordingPhase {
    IDLE,
    STARTING,
    RECORDING,
    STOPPING,
    ERROR,
}

data class RecordingUiState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val elapsedSeconds: Long = 0,
    val isClientSilenced: Boolean = false,
    val message: String? = null,
    val translationMessage: String? = null,
    val pendingTranslationCount: Int = 0,
)

object RecordingUiStateStore {
    private val mutableState = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = mutableState.asStateFlow()

    internal fun publish(state: RecordingUiState) {
        mutableState.value = state
    }

    internal fun update(transform: (RecordingUiState) -> RecordingUiState) {
        mutableState.update(transform)
    }
}
