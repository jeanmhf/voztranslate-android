package com.voztranslate.app.ui

import com.voztranslate.app.ai.M2m100TranslationModelManager
import com.voztranslate.app.domain.Language

enum class ModelPreparationPhase {
    IDLE,
    CHECKING,
    NOT_READY,
    DOWNLOADING,
    READY,
    ERROR,
}

data class ModelPreparationUiState(
    val language: Language = Language.ENGLISH,
    val phase: ModelPreparationPhase = ModelPreparationPhase.IDLE,
    val downloadStatus: String? = null,
    val relativePath: String? = null,
    val fileIndex: Int = 0,
    val totalFiles: Int = 0,
    val bytesDownloaded: Long = 0,
    val bytesTotal: Long = -1,
    val error: String? = null,
)

/** Download/verification state for the shared M2M100 model, independent of language selection. */
data class TranslationModelPreparationUiState(
    val phase: ModelPreparationPhase = ModelPreparationPhase.IDLE,
    val downloadStatus: String? = null,
    val relativePath: String? = null,
    val fileIndex: Int = 0,
    val totalFiles: Int = 0,
    val bytesDownloaded: Long = 0,
    val bytesTotal: Long = -1,
    val requiredBytes: Long = M2m100TranslationModelManager.requiredDownloadBytes,
    val error: String? = null,
)
