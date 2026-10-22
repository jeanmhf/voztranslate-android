package com.voztranslate.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.voztranslate.app.recording.MicrophoneRecordingService
import com.voztranslate.app.recording.ActiveTranscriptStateStore
import com.voztranslate.app.recording.RecordingPhase
import com.voztranslate.app.recording.RecordingUiState
import com.voztranslate.app.recording.RecordingUiStateStore
import com.voztranslate.app.ui.HomeScreen
import com.voztranslate.app.ui.HomeViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            requestMicrophonePermissionOrStart()
        } else {
            publishPermissionError(
                "Se necesita el permiso de notificaciones para mostrar los controles persistentes de captura.",
            )
        }
    }

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startRecordingService()
        } else {
            publishPermissionError("Concede permiso al micrófono para iniciar la captura.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VozTranslateTheme {
                Surface(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state = viewModel.uiState.collectAsStateWithLifecycle()
                    val modelState by viewModel.modelPreparation.collectAsStateWithLifecycle()
                    val translationModelState by viewModel.translationModelPreparation.collectAsStateWithLifecycle()
                    val sessionStorageState by viewModel.sessionStorage.collectAsStateWithLifecycle()
                    val recordingState by RecordingUiStateStore.state.collectAsStateWithLifecycle()
                    val transcriptState by ActiveTranscriptStateStore.state.collectAsStateWithLifecycle()
                    LaunchedEffect(state.value.sourceLanguage) {
                        viewModel.inspectModel(applicationContext, state.value.sourceLanguage)
                    }
                    LaunchedEffect(Unit) {
                        viewModel.inspectTranslationModel(applicationContext)
                    }
                    HomeScreen(
                        state = state.value,
                        recordingState = recordingState,
                        modelState = modelState,
                        translationModelState = translationModelState,
                        sessionStorageState = sessionStorageState,
                        transcriptState = transcriptState,
                        onSourceLanguageSelected = viewModel::selectSourceLanguage,
                        onTargetLanguageSelected = viewModel::selectTargetLanguage,
                        onSwapLanguages = viewModel::swapLanguages,
                        onPrepareModel = {
                            viewModel.prepareModel(applicationContext, viewModel.uiState.value.sourceLanguage)
                        },
                        onPrepareTranslationModel = {
                            viewModel.prepareTranslationModel(applicationContext)
                        },
                        onRefreshSessionStorage = {
                            viewModel.refreshSessionStorage(applicationContext)
                        },
                        onDeleteStoredSessions = {
                            viewModel.deleteStoredSessions(applicationContext)
                        },
                        onStartRecording = ::requestCapturePermissionsAndStart,
                        onStopRecording = { MicrophoneRecordingService.stop(this) },
                    )
                }
            }
        }
    }

    private fun requestCapturePermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestMicrophonePermissionOrStart()
        }
    }

    private fun requestMicrophonePermissionOrStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecordingService()
        } else {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecordingService() {
        if (!viewModel.canStartRecording()) {
            publishPermissionError("Prepara y verifica los modelos de transcripción y traducción antes de iniciar.")
            return
        }
        val languages = viewModel.uiState.value
        MicrophoneRecordingService.start(this, languages.sourceLanguage, languages.targetLanguage)
    }

    private fun publishPermissionError(message: String) {
        RecordingUiStateStore.publish(
            RecordingUiState(phase = RecordingPhase.ERROR, message = message),
        )
    }
}

@Composable
private fun VozTranslateTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
