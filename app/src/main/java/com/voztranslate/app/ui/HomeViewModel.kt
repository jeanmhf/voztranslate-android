package com.voztranslate.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voztranslate.app.ai.MoonshineModelManager
import com.voztranslate.app.ai.M2m100TranslationModelManager
import com.voztranslate.app.data.SessionRepositoryProvider
import com.voztranslate.app.domain.Language
import com.voztranslate.app.domain.SessionStorageDeletionResult
import com.voztranslate.app.domain.SessionStorageUsage
import com.voztranslate.app.recording.ActiveTranscriptStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class HomeUiState(
    val sourceLanguage: Language = Language.ENGLISH,
    val targetLanguage: Language = Language.SPANISH,
)

enum class SessionStoragePhase {
    LOADING,
    READY,
    DELETING,
    ERROR,
}

data class SessionStorageUiState(
    val phase: SessionStoragePhase = SessionStoragePhase.LOADING,
    val sessionCount: Int = 0,
    val audioBytes: Long = 0,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

class HomeViewModel : ViewModel() {
    private val mutableUiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = mutableUiState.asStateFlow()
    private val mutableModelPreparation = MutableStateFlow(ModelPreparationUiState())
    val modelPreparation: StateFlow<ModelPreparationUiState> = mutableModelPreparation.asStateFlow()
    private val mutableTranslationModelPreparation = MutableStateFlow(TranslationModelPreparationUiState())
    val translationModelPreparation: StateFlow<TranslationModelPreparationUiState> =
        mutableTranslationModelPreparation.asStateFlow()
    private val mutableSessionStorage = MutableStateFlow(SessionStorageUiState())
    val sessionStorage: StateFlow<SessionStorageUiState> = mutableSessionStorage.asStateFlow()
    private var modelJob: Job? = null
    private val modelRequestId = AtomicLong(0)
    private var translationModelJob: Job? = null
    private val translationModelRequestId = AtomicLong(0)
    private var sessionStorageJob: Job? = null
    private val sessionStorageRequestId = AtomicLong(0)

    fun canStartRecording(): Boolean =
        mutableModelPreparation.value.language == mutableUiState.value.sourceLanguage &&
            mutableModelPreparation.value.phase == ModelPreparationPhase.READY &&
            mutableTranslationModelPreparation.value.phase == ModelPreparationPhase.READY

    fun refreshSessionStorage(context: Context) {
        if (mutableSessionStorage.value.phase == SessionStoragePhase.DELETING) return
        sessionStorageJob?.cancel()
        val requestId = sessionStorageRequestId.incrementAndGet()
        mutableSessionStorage.value = mutableSessionStorage.value.copy(
            phase = SessionStoragePhase.LOADING,
            message = null,
            messageIsError = false,
        )
        sessionStorageJob = viewModelScope.launch {
            try {
                val usage = withContext(Dispatchers.IO) {
                    SessionRepositoryProvider.getStorage(context.applicationContext).storageUsage()
                }
                if (requestId == sessionStorageRequestId.get()) {
                    mutableSessionStorage.value = usage.toUiState()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestId == sessionStorageRequestId.get()) {
                    mutableSessionStorage.value = mutableSessionStorage.value.copy(
                        phase = SessionStoragePhase.ERROR,
                        message = error.message ?: "No se pudo consultar el almacenamiento de sesiones.",
                        messageIsError = true,
                    )
                }
            }
        }
    }

    fun deleteStoredSessions(context: Context) {
        if (mutableSessionStorage.value.phase == SessionStoragePhase.DELETING) return
        sessionStorageJob?.cancel()
        val requestId = sessionStorageRequestId.incrementAndGet()
        mutableSessionStorage.value = mutableSessionStorage.value.copy(
            phase = SessionStoragePhase.DELETING,
            message = null,
            messageIsError = false,
        )
        sessionStorageJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SessionRepositoryProvider.getStorage(context.applicationContext).deleteStoredSessions()
                }
                if (requestId == sessionStorageRequestId.get()) {
                    result.deletedSessionIds.forEach(ActiveTranscriptStateStore::clearIfSessionDeleted)
                    mutableSessionStorage.value = result.toUiState()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestId == sessionStorageRequestId.get()) {
                    val refreshedUsage = runCatching {
                        withContext(Dispatchers.IO) {
                            SessionRepositoryProvider.getStorage(context.applicationContext).storageUsage()
                        }
                    }.getOrNull()
                    val previous = mutableSessionStorage.value
                    mutableSessionStorage.value = (refreshedUsage?.toUiState() ?: previous).copy(
                        phase = SessionStoragePhase.ERROR,
                        message = error.message ?: "No se pudo completar el borrado de sesiones.",
                        messageIsError = true,
                    )
                }
            }
        }
    }

    fun selectSourceLanguage(language: Language) {
        mutableUiState.value = mutableUiState.value.withSourceSelection(language)
        syncModelPreparationWithSource()
    }

    fun selectTargetLanguage(language: Language) {
        mutableUiState.value = mutableUiState.value.withTargetSelection(language)
        syncModelPreparationWithSource()
    }

    fun swapLanguages() {
        val current = mutableUiState.value
        mutableUiState.value = current.copy(
            sourceLanguage = current.targetLanguage,
            targetLanguage = current.sourceLanguage,
        )
        syncModelPreparationWithSource()
    }

    private fun syncModelPreparationWithSource() {
        val selectedLanguage = mutableUiState.value.sourceLanguage
        if (mutableModelPreparation.value.language != selectedLanguage) {
            modelJob?.cancel()
            modelRequestId.incrementAndGet()
            mutableModelPreparation.value = ModelPreparationUiState(language = selectedLanguage)
        }
    }

    fun inspectModel(context: Context, language: Language) {
        val current = mutableModelPreparation.value
        if (current.language == language && current.phase in setOf(
                ModelPreparationPhase.CHECKING,
                ModelPreparationPhase.DOWNLOADING,
                ModelPreparationPhase.READY,
            )
        ) return

        modelJob?.cancel()
        val requestId = modelRequestId.incrementAndGet()
        mutableModelPreparation.value = ModelPreparationUiState(
            language = language,
            phase = ModelPreparationPhase.CHECKING,
        )
        modelJob = viewModelScope.launch {
            try {
                val prepared = MoonshineModelManager.isPrepared(context.applicationContext, language)
                if (prepared && requestId == modelRequestId.get()) {
                    mutableModelPreparation.value = ModelPreparationUiState(
                        language = language,
                        phase = ModelPreparationPhase.READY,
                    )
                } else if (!prepared) {
                    val foundDownload = MoonshineModelManager.observeExistingDownload(
                        context.applicationContext,
                        language,
                    ) { progress ->
                        publishDownloadProgress(requestId, language, progress)
                    }
                    if (requestId == modelRequestId.get()) {
                        mutableModelPreparation.value = if (foundDownload) {
                            ModelPreparationUiState(language = language, phase = ModelPreparationPhase.READY)
                        } else {
                            ModelPreparationUiState(language = language, phase = ModelPreparationPhase.NOT_READY)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestId == modelRequestId.get()) {
                    mutableModelPreparation.value = ModelPreparationUiState(
                        language = language,
                        phase = ModelPreparationPhase.ERROR,
                        error = error.message ?: "No se pudo comprobar el modelo local.",
                    )
                }
            }
        }
    }

    fun prepareModel(context: Context, language: Language) {
        if (mutableModelPreparation.value.phase == ModelPreparationPhase.DOWNLOADING) return
        modelJob?.cancel()
        val requestId = modelRequestId.incrementAndGet()
        mutableModelPreparation.value = ModelPreparationUiState(
            language = language,
            phase = ModelPreparationPhase.DOWNLOADING,
        )
        modelJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                MoonshineModelManager.ensurePrepared(context.applicationContext, language) { progress ->
                    publishDownloadProgress(requestId, language, progress)
                }
                if (requestId == modelRequestId.get()) {
                    mutableModelPreparation.value = ModelPreparationUiState(
                        language = language,
                        phase = ModelPreparationPhase.READY,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestId == modelRequestId.get()) {
                    mutableModelPreparation.value = ModelPreparationUiState(
                        language = language,
                        phase = ModelPreparationPhase.ERROR,
                        error = error.message ?: "No se pudo descargar el modelo. Comprueba la conexión y el espacio disponible.",
                    )
                }
            }
        }
    }

    fun inspectTranslationModel(context: Context) {
        val current = mutableTranslationModelPreparation.value
        if (current.phase in setOf(
                ModelPreparationPhase.CHECKING,
                ModelPreparationPhase.DOWNLOADING,
                ModelPreparationPhase.READY,
            )
        ) return

        translationModelJob?.cancel()
        val requestId = translationModelRequestId.incrementAndGet()
        mutableTranslationModelPreparation.value = TranslationModelPreparationUiState(
            phase = ModelPreparationPhase.CHECKING,
            bytesTotal = M2m100TranslationModelManager.requiredDownloadBytes,
            requiredBytes = M2m100TranslationModelManager.requiredDownloadBytes,
        )
        translationModelJob = viewModelScope.launch {
            try {
                val appContext = context.applicationContext
                val prepared = M2m100TranslationModelManager.isPrepared(appContext)
                if (prepared && requestId == translationModelRequestId.get()) {
                    publishTranslationReady(requestId)
                } else if (!prepared) {
                    val foundDownload = M2m100TranslationModelManager.observeExistingDownload(appContext) { progress ->
                        publishTranslationDownloadProgress(requestId, progress)
                    }
                    if (requestId == translationModelRequestId.get()) {
                        mutableTranslationModelPreparation.value = if (foundDownload) {
                            translationReadyState()
                        } else {
                            TranslationModelPreparationUiState(
                                phase = ModelPreparationPhase.NOT_READY,
                                bytesTotal = M2m100TranslationModelManager.requiredDownloadBytes,
                                requiredBytes = M2m100TranslationModelManager.requiredDownloadBytes,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestId == translationModelRequestId.get()) {
                    mutableTranslationModelPreparation.value = translationErrorState(
                        error.message ?: "No se pudo comprobar el modelo de traducción local.",
                    )
                }
            }
        }
    }

    fun prepareTranslationModel(context: Context) {
        if (mutableTranslationModelPreparation.value.phase == ModelPreparationPhase.DOWNLOADING) return
        translationModelJob?.cancel()
        val requestId = translationModelRequestId.incrementAndGet()
        mutableTranslationModelPreparation.value = TranslationModelPreparationUiState(
            phase = ModelPreparationPhase.DOWNLOADING,
            bytesTotal = M2m100TranslationModelManager.requiredDownloadBytes,
            requiredBytes = M2m100TranslationModelManager.requiredDownloadBytes,
        )
        translationModelJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                M2m100TranslationModelManager.ensurePrepared(context.applicationContext) { progress ->
                    publishTranslationDownloadProgress(requestId, progress)
                }
                publishTranslationReady(requestId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestId == translationModelRequestId.get()) {
                    mutableTranslationModelPreparation.value = translationErrorState(
                        error.message ?: "No se pudo descargar el modelo M2M100. Comprueba la conexión y el espacio disponible.",
                    )
                }
            }
        }
    }

    private fun publishDownloadProgress(
        requestId: Long,
        language: Language,
        progress: com.voztranslate.app.ai.DownloadProgress,
    ) {
        if (requestId != modelRequestId.get()) return
        mutableModelPreparation.value = ModelPreparationUiState(
            language = language,
            phase = ModelPreparationPhase.DOWNLOADING,
            downloadStatus = progress.status,
            relativePath = progress.relativePath,
            fileIndex = progress.fileIndex,
            totalFiles = progress.totalFiles,
            bytesDownloaded = progress.bytesDownloaded,
            bytesTotal = progress.bytesTotal,
        )
    }

    private fun publishTranslationDownloadProgress(
        requestId: Long,
        progress: com.voztranslate.app.ai.M2m100DownloadProgress,
    ) {
        if (requestId != translationModelRequestId.get()) return
        mutableTranslationModelPreparation.value = TranslationModelPreparationUiState(
            phase = ModelPreparationPhase.DOWNLOADING,
            downloadStatus = progress.state,
            relativePath = progress.relativePath,
            fileIndex = progress.artifactIndex,
            totalFiles = progress.artifactCount,
            bytesDownloaded = progress.bytesDownloaded,
            bytesTotal = progress.bytesTotal,
            requiredBytes = M2m100TranslationModelManager.requiredDownloadBytes,
            error = progress.error,
        )
    }

    private fun publishTranslationReady(requestId: Long) {
        if (requestId == translationModelRequestId.get()) {
            mutableTranslationModelPreparation.value = translationReadyState()
        }
    }

    private fun translationReadyState() = TranslationModelPreparationUiState(
        phase = ModelPreparationPhase.READY,
        bytesDownloaded = M2m100TranslationModelManager.requiredDownloadBytes,
        bytesTotal = M2m100TranslationModelManager.requiredDownloadBytes,
        requiredBytes = M2m100TranslationModelManager.requiredDownloadBytes,
    )

    private fun translationErrorState(message: String) = TranslationModelPreparationUiState(
        phase = ModelPreparationPhase.ERROR,
        bytesTotal = M2m100TranslationModelManager.requiredDownloadBytes,
        requiredBytes = M2m100TranslationModelManager.requiredDownloadBytes,
        error = message,
    )

    private fun SessionStorageUsage.toUiState() = SessionStorageUiState(
        phase = SessionStoragePhase.READY,
        sessionCount = sessionCount,
        audioBytes = audioBytes,
    )

    private fun SessionStorageDeletionResult.toUiState(): SessionStorageUiState {
        val message = when {
            blockedByInProgressSession -> "No se borró nada: hay una sesión en curso. Detén la captura antes de borrar."
            failedAudioFileCount > 0 -> "Se eliminaron $deletedSessionCount sesiones y $deletedAudioFileCount WAV. " +
                "Quedan $failedAudioFileCount archivos WAV que no se pudieron borrar; las sesiones asociadas se conservaron."
            deletedSessionCount == 0 && deletedAudioFileCount == 0 -> "No había grabaciones ni transcripciones guardadas."
            else -> "Eliminación completa: $deletedSessionCount sesiones y $deletedAudioFileCount archivos WAV privados borrados."
        }
        val hasError = blockedByInProgressSession || failedAudioFileCount > 0
        return SessionStorageUiState(
            phase = if (hasError) SessionStoragePhase.ERROR else SessionStoragePhase.READY,
            sessionCount = usage.sessionCount,
            audioBytes = usage.audioBytes,
            message = message,
            messageIsError = hasError,
        )
    }

    private fun HomeUiState.withSourceSelection(language: Language): HomeUiState =
        if (language == targetLanguage) {
            copy(sourceLanguage = targetLanguage, targetLanguage = sourceLanguage)
        } else {
            copy(sourceLanguage = language)
        }

    private fun HomeUiState.withTargetSelection(language: Language): HomeUiState =
        if (language == sourceLanguage) {
            copy(sourceLanguage = targetLanguage, targetLanguage = sourceLanguage)
        } else {
            copy(targetLanguage = language)
        }
}
