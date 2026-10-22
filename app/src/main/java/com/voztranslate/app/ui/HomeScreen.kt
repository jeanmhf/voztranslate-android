package com.voztranslate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voztranslate.app.domain.Language
import com.voztranslate.app.domain.TranscriptSegment
import com.voztranslate.app.recording.ActiveTranscriptState
import com.voztranslate.app.recording.RecordingPhase
import com.voztranslate.app.recording.RecordingUiState

private enum class HomeSection {
    PREPARE,
    LIVE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    recordingState: RecordingUiState,
    modelState: ModelPreparationUiState,
    translationModelState: TranslationModelPreparationUiState,
    sessionStorageState: SessionStorageUiState,
    transcriptState: ActiveTranscriptState,
    onSourceLanguageSelected: (Language) -> Unit,
    onTargetLanguageSelected: (Language) -> Unit,
    onSwapLanguages: () -> Unit,
    onPrepareModel: () -> Unit,
    onPrepareTranslationModel: () -> Unit,
    onRefreshSessionStorage: () -> Unit,
    onDeleteStoredSessions: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val recordingInProgress = recordingState.phase in setOf(
        RecordingPhase.STARTING,
        RecordingPhase.RECORDING,
        RecordingPhase.STOPPING,
    )
    val modelReady = modelState.language == state.sourceLanguage && modelState.phase == ModelPreparationPhase.READY
    val translationModelReady = translationModelState.phase == ModelPreparationPhase.READY
    val bothModelsReady = modelReady && translationModelReady
    var selectedSection by rememberSaveable { mutableStateOf(HomeSection.PREPARE) }
    var showDeleteSessionsDialog by rememberSaveable { mutableStateOf(false) }
    val visibleSection = if (recordingInProgress) HomeSection.LIVE else selectedSection

    LaunchedEffect(recordingInProgress) {
        if (recordingInProgress) selectedSection = HomeSection.LIVE
    }
    LaunchedEffect(visibleSection) {
        if (visibleSection == HomeSection.PREPARE) onRefreshSessionStorage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VozTranslate") },
                actions = {
                    Text(
                        "PROTOTIPO",
                        modifier = Modifier.padding(end = 20.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        },
        bottomBar = {
            Column {
                if (visibleSection == HomeSection.LIVE) {
                    LiveCaptureControlBar(
                        recordingState = recordingState,
                        bothModelsReady = bothModelsReady,
                        onOpenPreparation = { selectedSection = HomeSection.PREPARE },
                        onStartRecording = onStartRecording,
                        onStopRecording = onStopRecording,
                    )
                }
                NavigationBar {
                    NavigationBarItem(
                        selected = visibleSection == HomeSection.PREPARE,
                        onClick = { selectedSection = HomeSection.PREPARE },
                        enabled = !recordingInProgress,
                        icon = { Text("⚙", style = MaterialTheme.typography.titleMedium) },
                        label = { Text("Preparar") },
                    )
                    NavigationBarItem(
                        selected = visibleSection == HomeSection.LIVE,
                        onClick = { selectedSection = HomeSection.LIVE },
                        icon = { Text("●", style = MaterialTheme.typography.titleMedium) },
                        label = { Text("En vivo") },
                    )
                }
            }
        },
    ) { contentPadding ->
        when (visibleSection) {
            HomeSection.PREPARE -> PreparationSection(
                state = state,
                modelState = modelState,
                translationModelState = translationModelState,
                sessionStorageState = sessionStorageState,
                bothModelsReady = bothModelsReady,
                recordingInProgress = recordingInProgress,
                onSourceLanguageSelected = onSourceLanguageSelected,
                onTargetLanguageSelected = onTargetLanguageSelected,
                onSwapLanguages = onSwapLanguages,
                onPrepareModel = onPrepareModel,
                onPrepareTranslationModel = onPrepareTranslationModel,
                onRequestDeleteStoredSessions = { showDeleteSessionsDialog = true },
                onOpenLive = { selectedSection = HomeSection.LIVE },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )

            HomeSection.LIVE -> LiveSection(
                state = state,
                recordingState = recordingState,
                bothModelsReady = bothModelsReady,
                transcriptState = transcriptState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        }
    }

    if (showDeleteSessionsDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSessionsDialog = false },
            title = { Text("Borrar grabaciones y transcripciones") },
            text = {
                Text(
                    "Se eliminarán las sesiones y transcripciones guardadas en Room, además de los WAV " +
                        "directamente dentro del almacenamiento privado files/recordings. No se tocarán " +
                        "archivos fuera de esa carpeta, los modelos descargados ni la aplicación.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteSessionsDialog = false
                        onDeleteStoredSessions()
                    },
                    enabled = !recordingInProgress && sessionStorageState.phase != SessionStoragePhase.DELETING,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSessionsDialog = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun PreparationSection(
    state: HomeUiState,
    modelState: ModelPreparationUiState,
    translationModelState: TranslationModelPreparationUiState,
    sessionStorageState: SessionStorageUiState,
    bothModelsReady: Boolean,
    recordingInProgress: Boolean,
    onSourceLanguageSelected: (Language) -> Unit,
    onTargetLanguageSelected: (Language) -> Unit,
    onSwapLanguages: () -> Unit,
    onPrepareModel: () -> Unit,
    onPrepareTranslationModel: () -> Unit,
    onRequestDeleteStoredSessions: () -> Unit,
    onOpenLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modelReady = modelState.language == state.sourceLanguage && modelState.phase == ModelPreparationPhase.READY
    val translationModelReady = translationModelState.phase == ModelPreparationPhase.READY
    val modelOperationActive = modelState.phase in setOf(
        ModelPreparationPhase.CHECKING,
        ModelPreparationPhase.DOWNLOADING,
    )
    val canChangeSource = !recordingInProgress && modelState.phase != ModelPreparationPhase.DOWNLOADING

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Preparar captura", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Elige los idiomas y prepara los modelos para usarlos sin conexión. La captura guarda audio WAV privado.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LanguageCard(
            title = "Hablo",
            selected = state.sourceLanguage,
            onSelected = onSourceLanguageSelected,
            enabled = canChangeSource,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = onSwapLanguages, enabled = canChangeSource) {
                Text("Intercambiar idiomas")
            }
        }
        LanguageCard(
            title = "Traducir a",
            selected = state.targetLanguage,
            onSelected = onTargetLanguageSelected,
            enabled = !recordingInProgress,
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Captura de micrófono", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Se captura PCM mono a 16 kHz. Cada frame se guarda en el WAV privado y se envía en orden a Moonshine Tiny Streaming.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Modelo de transcripción", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    modelPreparationMessage(modelState, state.sourceLanguage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (modelState.phase == ModelPreparationPhase.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (modelState.phase == ModelPreparationPhase.DOWNLOADING) {
                    DownloadProgress(modelState.bytesDownloaded, modelState.bytesTotal)
                } else if (modelState.phase == ModelPreparationPhase.CHECKING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(
                    onClick = onPrepareModel,
                    enabled = canChangeSource && !modelOperationActive && !modelReady,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when (modelState.phase) {
                            ModelPreparationPhase.ERROR -> "Reintentar preparación"
                            ModelPreparationPhase.CHECKING -> "Comprobando modelo…"
                            ModelPreparationPhase.DOWNLOADING -> "Descargando modelo…"
                            ModelPreparationPhase.READY -> "Modelo preparado"
                            else -> "Descargar y preparar modelo"
                        },
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Modelo de traducción", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    translationModelPreparationMessage(translationModelState),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (translationModelState.phase == ModelPreparationPhase.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (translationModelState.phase == ModelPreparationPhase.DOWNLOADING) {
                    DownloadProgress(translationModelState.bytesDownloaded, translationModelState.bytesTotal)
                } else if (translationModelState.phase == ModelPreparationPhase.CHECKING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(
                    onClick = onPrepareTranslationModel,
                    enabled = !recordingInProgress &&
                        translationModelState.phase !in setOf(ModelPreparationPhase.CHECKING, ModelPreparationPhase.DOWNLOADING) &&
                        !translationModelReady,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when (translationModelState.phase) {
                            ModelPreparationPhase.ERROR -> "Reintentar preparación"
                            ModelPreparationPhase.CHECKING -> "Comprobando modelo…"
                            ModelPreparationPhase.DOWNLOADING -> "Descargando modelo…"
                            ModelPreparationPhase.READY -> "Modelo preparado"
                            else -> "Descargar y preparar M2M100"
                        },
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (bothModelsReady) "Todo listo" else "Preparación pendiente",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (bothModelsReady) {
                        "Ambos modelos están verificados. Abre En vivo para iniciar la captura."
                    } else {
                        "Para iniciar, prepara y verifica el modelo de transcripción y M2M100."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (bothModelsReady) {
                    Button(onClick = onOpenLive, modifier = Modifier.fillMaxWidth()) {
                        Text("Ir a En vivo")
                    }
                }
            }
        }

        SessionStorageCard(
            state = sessionStorageState,
            recordingInProgress = recordingInProgress,
            onDelete = onRequestDeleteStoredSessions,
        )
        Spacer(Modifier.heightIn(min = 4.dp))
    }
}

@Composable
private fun SessionStorageCard(
    state: SessionStorageUiState,
    recordingInProgress: Boolean,
    onDelete: () -> Unit,
) {
    val hasStoredData = state.sessionCount > 0 || state.audioBytes > 0
    val canDelete = !recordingInProgress &&
        state.phase !in setOf(SessionStoragePhase.LOADING, SessionStoragePhase.DELETING) &&
        hasStoredData

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Almacenamiento de sesiones", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when (state.phase) {
                SessionStoragePhase.LOADING -> Text(
                    "Consultando el almacenamiento privado…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SessionStoragePhase.DELETING -> Text(
                    "Borrando archivos WAV y transcripciones…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    if (!hasStoredData) {
                        "No hay sesiones guardadas."
                    } else {
                        val sessionLabel = if (state.sessionCount == 1) "sesión guardada" else "sesiones guardadas"
                        "${state.sessionCount} $sessionLabel · ${formatBytes(state.audioBytes)} de audio WAV"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Button(
                onClick = onDelete,
                enabled = canDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Borrar grabaciones y transcripciones")
            }
            if (recordingInProgress) {
                Text(
                    "Detén la captura antes de borrar sesiones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LiveSection(
    state: HomeUiState,
    recordingState: RecordingUiState,
    bothModelsReady: Boolean,
    transcriptState: ActiveTranscriptState,
    modifier: Modifier = Modifier,
) {
    val recordingInProgress = recordingState.phase in setOf(
        RecordingPhase.STARTING,
        RecordingPhase.RECORDING,
        RecordingPhase.STOPPING,
    )
    val visibleSegments = remember(transcriptState.segments) { transcriptState.segments.takeLast(30) }
    val lastSegment = visibleSegments.lastOrNull()
    val listState = rememberLazyListState()
    val speakerLabels = remember(transcriptState.segments) {
        transcriptState.segments
            .mapNotNull { it.speakerId?.takeIf { speakerId -> speakerId.isNotBlank() } }
            .distinct()
            .mapIndexed { index, speakerId -> speakerId to "Speaker ${index + 1}" }
            .toMap()
    }

    LaunchedEffect(lastSegment?.id, lastSegment?.revision, visibleSegments.size) {
        if (lastSegment != null) {
            listState.animateScrollToItem(index = visibleSegments.lastIndex)
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("En vivo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Captura y subtítulos de la sesión actual.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LiveStatusCard(
            state = state,
            recordingState = recordingState,
            bothModelsReady = bothModelsReady,
            modifier = Modifier
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState()),
        )

        Text(
            "La separación de voces aún está pendiente.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Subtítulos de la sesión", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        if (visibleSegments.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Text(
                    if (recordingInProgress) "Esperando las primeras palabras…" else "Inicia una captura para ver los subtítulos aquí.",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visibleSegments, key = { it.id }) { segment ->
                    SubtitleCard(segment = segment, speakerLabel = speakerLabels[segment.speakerId])
                }
            }
        }
    }
}

@Composable
private fun LiveStatusCard(
    state: HomeUiState,
    recordingState: RecordingUiState,
    bothModelsReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val status = when (recordingState.phase) {
        RecordingPhase.STARTING -> "Preparando la sesión…"
        RecordingPhase.RECORDING -> "Capturando audio · ${recordingState.elapsedSeconds}s"
        RecordingPhase.STOPPING -> "Deteniendo y guardando la grabación…"
        RecordingPhase.ERROR -> "La sesión requiere atención"
        RecordingPhase.IDLE -> if (bothModelsReady) "Lista para iniciar" else "Faltan modelos por preparar"
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${state.sourceLanguage.displayName} → ${state.targetLanguage.displayName}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            when {
                recordingState.isClientSilenced -> Text(
                    "Android está silenciando el micrófono; el audio recibido puede contener silencio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                !recordingState.message.isNullOrBlank() -> Text(
                    recordingState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!recordingState.translationMessage.isNullOrBlank()) {
                Text(
                    recordingState.translationMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (recordingState.pendingTranslationCount > 0) {
                Text(
                    "Traducciones finales pendientes: ${recordingState.pendingTranslationCount}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun LiveCaptureControlBar(
    recordingState: RecordingUiState,
    bothModelsReady: Boolean,
    onOpenPreparation: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Button(
                onClick = if (recordingState.phase == RecordingPhase.RECORDING) {
                    onStopRecording
                } else {
                    onStartRecording
                },
                enabled = when (recordingState.phase) {
                    RecordingPhase.STARTING, RecordingPhase.STOPPING -> false
                    RecordingPhase.RECORDING -> true
                    else -> bothModelsReady
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
            ) {
                Text(
                    when (recordingState.phase) {
                        RecordingPhase.STARTING -> "Iniciando captura…"
                        RecordingPhase.RECORDING -> "Detener captura"
                        RecordingPhase.STOPPING -> "Finalizando y guardando…"
                        else -> "Iniciar captura"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (!bothModelsReady && recordingState.phase !in setOf(
                    RecordingPhase.STARTING,
                    RecordingPhase.RECORDING,
                    RecordingPhase.STOPPING,
                )
            ) {
                TextButton(onClick = onOpenPreparation) {
                    Text("Preparar")
                }
            }
        }
    }
}

@Composable
private fun SubtitleCard(segment: TranscriptSegment, speakerLabel: String?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    speakerLabel ?: "Hablante sin identificar",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    if (segment.isFinal) "Final" else "Parcial",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Original", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(segment.sourceText, style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider()
            Text("Traducción", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                segment.translatedText?.takeIf { it.isNotBlank() }
                    ?: if (segment.isFinal) "Traducción pendiente" else "Esperando traducción…",
                style = MaterialTheme.typography.bodyLarge,
                color = if (segment.translatedText.isNullOrBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun DownloadProgress(bytesDownloaded: Long, bytesTotal: Long) {
    val progress = bytesTotal.takeIf { it > 0 }
        ?.let { (bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
    if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
}

private fun translationModelPreparationMessage(state: TranslationModelPreparationUiState): String {
    val required = "${formatBytes(state.requiredBytes)} (${state.requiredBytes} bytes)"
    return when (state.phase) {
        ModelPreparationPhase.IDLE -> "M2M100 418M · se requieren $required en almacenamiento privado."
        ModelPreparationPhase.CHECKING -> "Comprobando los archivos M2M100 locales · se requieren $required…"
        ModelPreparationPhase.NOT_READY -> "Descarga M2M100 antes de iniciar · se requieren $required."
        ModelPreparationPhase.DOWNLOADING -> {
            val filePart = when (state.downloadStatus) {
                "ENQUEUED" -> "Descarga en cola"
                "BLOCKED" -> "Descarga en espera"
                else -> if (state.totalFiles > 0) "Archivo ${state.fileIndex}/${state.totalFiles}" else "Descargando"
            }
            val pathPart = state.relativePath?.let { " · $it" }.orEmpty()
            val bytesPart = if (state.bytesTotal > 0) {
                " · ${formatBytes(state.bytesDownloaded)} de ${formatBytes(state.bytesTotal)}"
            } else {
                " · ${formatBytes(state.bytesDownloaded)}"
            }
            "$filePart$pathPart$bytesPart · requiere $required"
        }

        ModelPreparationPhase.READY -> "M2M100 está verificado y listo offline · $required descargados."
        ModelPreparationPhase.ERROR -> state.error ?: "No se pudo preparar M2M100. Comprueba la conexión y el espacio disponible."
    }
}

private fun modelPreparationMessage(state: ModelPreparationUiState, selectedLanguage: Language): String {
    if (state.language != selectedLanguage) return "Comprobando el modelo Tiny Streaming de ${selectedLanguage.displayName}…"
    return when (state.phase) {
        ModelPreparationPhase.IDLE -> "El modelo se guarda en el almacenamiento privado y funciona offline después de descargarlo."
        ModelPreparationPhase.CHECKING -> "Comprobando los archivos locales de ${selectedLanguage.displayName}…"
        ModelPreparationPhase.NOT_READY -> "Descarga el modelo Tiny Streaming de ${selectedLanguage.displayName} antes de iniciar la captura."
        ModelPreparationPhase.DOWNLOADING -> {
            val filePart = when (state.downloadStatus) {
                "ENQUEUED" -> "Descarga en cola"
                "BLOCKED" -> "Descarga en espera"
                else -> if (state.totalFiles > 0) "Archivo ${state.fileIndex}/${state.totalFiles}" else "Descargando"
            }
            val pathPart = state.relativePath?.let { " · $it" }.orEmpty()
            val bytesPart = if (state.bytesTotal > 0) {
                " · ${formatBytes(state.bytesDownloaded)} de ${formatBytes(state.bytesTotal)}"
            } else {
                " · ${formatBytes(state.bytesDownloaded)}"
            }
            "$filePart$pathPart$bytesPart"
        }

        ModelPreparationPhase.READY -> "Tiny Streaming para ${selectedLanguage.displayName} está listo en este dispositivo."
        ModelPreparationPhase.ERROR -> state.error ?: "No se pudo preparar el modelo. Comprueba la conexión y el espacio disponible."
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> "${bytes / 1_000} kB"
    else -> "${bytes / 1_000_000} MB"
}

@Composable
private fun LanguageCard(
    title: String,
    selected: Language,
    onSelected: (Language) -> Unit,
    enabled: Boolean,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Language.entries.forEach { language ->
                    FilterChip(
                        selected = selected == language,
                        enabled = enabled,
                        onClick = { onSelected(language) },
                        label = { Text(language.displayName) },
                    )
                }
            }
        }
    }
}
