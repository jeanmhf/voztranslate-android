package com.voztranslate.app.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.voztranslate.app.MainActivity
import com.voztranslate.app.R
import com.voztranslate.app.ai.ManagedSpeechEngine
import com.voztranslate.app.ai.ManagedTranslationEngine
import com.voztranslate.app.ai.RecordingTranslationEngineFactoryProvider
import com.voztranslate.app.ai.RecordingSpeechEngineFactoryProvider
import com.voztranslate.app.data.SessionRepositoryProvider
import com.voztranslate.app.domain.AudioFrame
import com.voztranslate.app.domain.Language
import com.voztranslate.app.domain.RecordingSession
import com.voztranslate.app.domain.SessionStatus
import com.voztranslate.app.domain.AudioSource
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** Owns microphone capture for as long as the user keeps the foreground session active. */
class MicrophoneRecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var stopSignal: CompletableDeferred<Unit>? = null
    private var pendingStartId: Int? = null
    private var pendingLanguages: Pair<Language, Language>? = null
    private var latestStartId = 0
    private val captureLease = AtomicReference<SessionOperationGate.Lease?>(null)
    private val captureGeneration = AtomicLong(0)

    @Volatile
    private var isFinishingCapture = false

    @Volatile
    private var isDestroyed = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                val languages = readLanguages(intent)
                val currentStopSignal = stopSignal
                if (currentStopSignal != null || isFinishingCapture) {
                    if (isFinishingCapture || currentStopSignal?.isCompleted == true) {
                        pendingStartId = startId
                        pendingLanguages = languages
                    }
                    // A repeated start during an active capture is idempotent.
                } else {
                    startCapture(startId, languages)
                }
            }

            ACTION_STOP -> {
                pendingStartId = null
                pendingLanguages = null
                val signal = stopSignal
                if (signal != null) {
                    signal.complete(Unit)
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }

            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isDestroyed = true
        // The capture coroutine is the sole owner of AudioRecord start/stop/release. Cancelling
        // it enters a NonCancellable cleanup section; do not release the recorder from here.
        captureJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startCapture(
        startId: Int,
        languages: Pair<Language, Language>,
        handoffLease: SessionOperationGate.Lease? = null,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            failToStart(
                startId,
                "Se necesita el permiso de notificaciones para mostrar los controles persistentes de captura.",
            )
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failToStart(startId, "Concede permiso al micrófono para iniciar la captura.")
            return
        }

        val admittedLease = handoffLease ?: SessionOperationGate.tryAcquireCapture()
        if (admittedLease == null) {
            failToStart(startId, "Hay una operación de almacenamiento en curso. Espera y vuelve a iniciar la captura.")
            return
        }
        if (handoffLease != null) {
            if (captureLease.get() !== handoffLease) {
                failToStart(startId, "La captura anterior ya no mantiene su operación activa.", handoffLease)
                return
            }
        } else if (!captureLease.compareAndSet(null, admittedLease)) {
            admittedLease.close()
            failToStart(startId, "Ya existe una operación de captura activa.", releaseLease = null)
            return
        }

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: SecurityException) {
            failToStart(startId, "Android no permitió iniciar la captura del micrófono.")
            return
        } catch (error: IllegalStateException) {
            failToStart(startId, "No se pudo iniciar el servicio de captura en primer plano.")
            return
        }

        val stopRequested = CompletableDeferred<Unit>()
        stopSignal = stopRequested
        pendingStartId = null
        pendingLanguages = null
        isFinishingCapture = false
        RecordingUiStateStore.publish(RecordingUiState(phase = RecordingPhase.STARTING))
        val generation = captureGeneration.incrementAndGet()
        val job = serviceScope.launch {
            captureUntilStopped(startId, stopRequested, languages.first, languages.second)
        }
        captureJob = job
        job.invokeOnCompletion {
            if (captureGeneration.get() == generation) releaseCaptureLease(admittedLease)
        }
    }

    private suspend fun CoroutineScope.captureUntilStopped(
        sessionStartId: Int,
        stopRequested: CompletableDeferred<Unit>,
        sourceLanguage: Language,
        targetLanguage: Language,
    ) = coroutineScope {
        val captureScope = this
        var source: AudioSource? = null
        var frameConsumer: PcmFrameConsumer? = null
        var frameCollector: Job? = null
        var silenceCollector: Job? = null
        var elapsedTicker: Job? = null
        var terminalState = RecordingUiState()
        var repository: com.voztranslate.app.domain.SessionRepository? = null
        var session: RecordingSession? = null
        var sessionWasStored = false
        var writer: PcmAudioWriter? = null
        var speechEngine: ManagedSpeechEngine? = null
        var translationEngine: ManagedTranslationEngine? = null
        var translationCoordinator: SessionTranslationCoordinator? = null
        var speechFrames: Channel<AudioFrame>? = null
        var speechCollector: Job? = null
        var speechFailure: CompletableDeferred<Throwable?>? = null
        var sourceStarted = false
        var sourceStartFailed = false
        var pipelineCancelled = false
        var stopSignalWon = false

        try {
            val sessionId = UUID.randomUUID().toString()
            // Model loading is complete before AudioRecord is opened. A missing model is an
            // explicit preparation error; capture never downloads or opens a second microphone.
            val loadedSpeechEngine = RecordingSpeechEngineFactoryProvider.factory.load(
                this@MicrophoneRecordingService,
                sourceLanguage,
            )
            speechEngine = loadedSpeechEngine
            // Translation model loading happens after ASR and before opening AudioRecord. The
            // selected source/target languages stay fixed for the lifetime of this session.
            val loadedTranslationEngine = RecordingTranslationEngineFactoryProvider.factory.load(
                this@MicrophoneRecordingService,
                sourceLanguage,
                targetLanguage,
            )
            translationEngine = loadedTranslationEngine
            val startedAtMillis = System.currentTimeMillis()
            loadedSpeechEngine.setSessionStartedAtMillis(startedAtMillis)
            val recordingsDirectory = File(filesDir, RECORDINGS_DIRECTORY)
            val audioFile = File(recordingsDirectory, "$sessionId.wav")
            val newSession = RecordingSession(
                id = sessionId,
                startedAtMillis = startedAtMillis,
                endedAtMillis = null,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                audioPath = audioFile.absolutePath,
                status = SessionStatus.IN_PROGRESS,
            )
            val sessionRepository = SessionRepositoryProvider.get(this@MicrophoneRecordingService)
            repository = sessionRepository
            sessionRepository.startSession(newSession)
            session = newSession
            sessionWasStored = true
            ActiveTranscriptStateStore.begin(sessionId)
            val transcriptTranslator = SessionTranslationCoordinator(
                scope = captureScope,
                engine = loadedTranslationEngine,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                persistAndPublish = { segment ->
                    sessionRepository.saveSegment(sessionId, segment)
                    ActiveTranscriptStateStore.upsert(sessionId, segment)
                    if (segment.translatedText != null) {
                        RecordingUiStateStore.update { current -> current.copy(translationMessage = null) }
                    }
                },
                loadPendingFinalTranslations = { limit ->
                    sessionRepository.pendingFinalTranslations(sessionId, limit)
                },
                countPendingFinalTranslations = {
                    sessionRepository.pendingFinalTranslationCount(sessionId)
                },
                onBacklogChanged = { count ->
                    RecordingUiStateStore.update { current -> current.copy(pendingTranslationCount = count) }
                },
                onTranslationError = { error ->
                    RecordingUiStateStore.update { current ->
                        current.copy(translationMessage = userFacingError(error))
                    }
                },
            )
            translationCoordinator = transcriptTranslator
            val audioWriter = PrivatePcmWavWriter.create(recordingsDirectory, sessionId)
            writer = audioWriter

            val audioSource = AudioRecordAudioSource()
            source = audioSource
            val speechInput = Channel<AudioFrame>(capacity = SPEECH_FRAME_BUFFER_CAPACITY)
            speechFrames = speechInput
            val speechFailed = CompletableDeferred<Throwable?>()
            speechFailure = speechFailed
            val transcriptionCollector = launch {
                try {
                    loadedSpeechEngine.transcribe(speechInput.receiveAsFlow(), sourceLanguage).collect { segment ->
                        transcriptTranslator.accept(segment)
                    }
                    speechFailed.complete(null)
                } catch (error: Throwable) {
                    speechFailed.complete(error)
                    speechInput.cancel(error.asCancellationException("La transcripción terminó con error."))
                }
            }
            speechCollector = transcriptionCollector
            transcriptionCollector.completeSignalOnCompletion(speechFailed)
            val consumer = PcmFrameConsumer(audioSource, audioWriter, speechInput)
            frameConsumer = consumer
            frameCollector = consumer.start(captureScope)
            silenceCollector = launch {
                audioSource.isClientSilenced.collect { silenced ->
                    RecordingUiStateStore.update { current ->
                        if (current.phase == RecordingPhase.STARTING || current.phase == RecordingPhase.RECORDING) {
                            current.copy(isClientSilenced = silenced)
                        } else {
                            current
                        }
                    }
                }
            }

            val sourceStartCommitted = withContext(Dispatchers.Main.immediate) {
                if (stopRequested.isCompleted) {
                    stopSignalWon = true
                    false
                } else {
                    true
                }
            }
            if (sourceStartCommitted) {
                try {
                    audioSource.start()
                    sourceStarted = true
                } catch (error: Throwable) {
                    sourceStartFailed = true
                    throw error
                }
            }

            // Serialize STOP with the state transition. No suspension occurs between checking the
            // stop signal, publishing RECORDING, and registering the ticker on the capture scope.
            val recordingCommitted = withContext(Dispatchers.Main.immediate) {
                if (stopRequested.isCompleted) {
                    stopSignalWon = true
                    false
                } else {
                    val startedAt = SystemClock.elapsedRealtime()
                    RecordingUiStateStore.update { current ->
                        current.copy(
                            phase = RecordingPhase.RECORDING,
                            elapsedSeconds = 0,
                            isClientSilenced = audioSource.isClientSilenced.value,
                            message = null,
                        )
                    }
                    elapsedTicker = captureScope.launch {
                        while (true) {
                            delay(ELAPSED_UPDATE_MS)
                            val elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000L
                            RecordingUiStateStore.update { current ->
                                if (current.phase == RecordingPhase.RECORDING) {
                                    current.copy(elapsedSeconds = elapsedSeconds)
                                } else {
                                    current
                                }
                            }
                        }
                    }
                    true
                }
            }

            if (recordingCommitted) {
                select<Unit> {
                    stopRequested.onAwait {
                        stopSignalWon = true
                    }
                    consumer.streamFailure.onAwait { error ->
                        throw error ?: IOException("La captura terminó inesperadamente.")
                    }
                    speechFailed.onAwait { error ->
                        throw error ?: IOException("El flujo de transcripción terminó inesperadamente.")
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    RecordingUiStateStore.update { current -> current.copy(phase = RecordingPhase.STOPPING) }
                }
            }
        } catch (cancelled: CancellationException) {
            pipelineCancelled = true
            terminalState = if (isDestroyed) {
                RecordingUiState()
            } else {
                RecordingUiState(
                    phase = RecordingPhase.ERROR,
                    message = "La captura fue interrumpida antes de detenerse.",
                )
            }
        } catch (error: Throwable) {
            terminalState = RecordingUiState(
                phase = RecordingPhase.ERROR,
                message = userFacingError(error),
            )
        } finally {
            isFinishingCapture = true
            withContext(NonCancellable) {
                elapsedTicker?.cancelAndJoin()
                silenceCollector?.cancelAndJoin()

                val stoppedBeforeSourceStart = sessionWasStored && !sourceStarted && stopSignalWon
                val shouldDiscardAudio = sourceStartFailed || stoppedBeforeSourceStart ||
                    (sessionWasStored && !sourceStarted && !stopSignalWon && terminalState.phase == RecordingPhase.ERROR)
                val abortPipeline = pipelineCancelled || sourceStartFailed || shouldDiscardAudio
                val sourceCleanupError = runCatching {
                    if (abortPipeline) source?.abort() else source?.stop()
                }.exceptionOrNull()

                if (abortPipeline) frameCollector?.cancelAndJoin() else frameCollector?.join()

                if (abortPipeline) {
                    speechFrames?.cancel(CancellationException("La sesión terminó antes de iniciar el flujo PCM."))
                }
                speechCollector?.join()
                val speechProcessingError = speechFailure?.await()
                val translationCleanupError = runCatching {
                    translationCoordinator?.closeAndJoin()
                }.exceptionOrNull()
                val translationEngineCleanupError = runCatching { translationEngine?.close() }.exceptionOrNull()
                val speechCleanupError = runCatching { speechEngine?.close() }.exceptionOrNull()

                // The consumer can fail while source.stop() is joining a producer blocked in
                // send(), including when STOP won the select. Read its result only after drain.
                val writerFailure = frameConsumer?.writerFailure
                val writerFailed = writerFailure != null
                var writerPrefixDiscarded = false
                var writerFileUnverified = false
                var writerFinalizationError: Throwable? = null
                var writerCleanupError: Throwable? = null
                val currentWriter = writer
                if (currentWriter != null) {
                    if (shouldDiscardAudio) {
                        writerCleanupError = runCatching { currentWriter.abortAndDelete() }.exceptionOrNull()
                    } else {
                        val initialFinishError = runCatching {
                            if (writerFailed) currentWriter.finishPreservingPrefix() else currentWriter.finish()
                        }.exceptionOrNull()

                        if (initialFinishError != null) {
                            writerFinalizationError = initialFinishError
                            // A failed header update or fd.sync must not discard already written
                            // PCM. Retry the writer's truncation/header repair, then use the file
                            // recovery path if the writer could not complete its own close.
                            val prefixRepaired = if (writerFailed) {
                                // finishPreservingPrefix was already attempted above and closes
                                // the writer even on failure, so repair the file directly.
                                PrivatePcmWavWriter.recoverInterruptedFile(currentWriter.file)
                            } else {
                                val preserveError = runCatching {
                                    currentWriter.finishPreservingPrefix()
                                }.exceptionOrNull()
                                preserveError == null || PrivatePcmWavWriter.recoverInterruptedFile(currentWriter.file)
                            }
                            if (!prefixRepaired) {
                                val payloadPresence = PrivatePcmWavWriter.inspectPcmPayload(currentWriter.file)
                                if (payloadPresence != PcmPayloadPresence.ABSENT) {
                                    // Recovery can fail because of transient I/O even when PCM is
                                    // present. Keep those bytes, but do not present the file as a
                                    // verified WAV.
                                    writerFileUnverified = true
                                } else {
                                    writerPrefixDiscarded = true
                                    writerCleanupError = runCatching {
                                        currentWriter.abortAndDelete()
                                    }.exceptionOrNull()
                                }
                            }
                        }

                        if (PrivatePcmWavWriter.inspectPcmPayload(currentWriter.file) == PcmPayloadPresence.ABSENT) {
                            if (writerFailed || writerFinalizationError != null) {
                                writerPrefixDiscarded = true
                                val deleteError = runCatching { currentWriter.abortAndDelete() }.exceptionOrNull()
                                if (writerCleanupError == null) writerCleanupError = deleteError
                            }
                        }
                    }
                }

                val cleanupError = writerFinalizationError ?: writerCleanupError ?: sourceCleanupError
                    ?: translationCleanupError ?: translationEngineCleanupError
                if (writerFailed && terminalState.phase != RecordingPhase.ERROR) {
                    terminalState = RecordingUiState(
                        phase = RecordingPhase.ERROR,
                        message = userFacingError(requireNotNull(writerFailure)),
                    )
                }
                if (cleanupError != null && terminalState.phase != RecordingPhase.ERROR) {
                    terminalState = RecordingUiState(
                        phase = RecordingPhase.ERROR,
                        message = userFacingError(cleanupError),
                    )
                } else if (cleanupError != null && terminalState.message == null) {
                    terminalState = terminalState.copy(message = userFacingError(cleanupError))
                }
                if (writerFileUnverified) {
                    val verificationMessage =
                        "Se conservaron bytes de audio, pero no se pudo verificar el archivo como WAV reproducible."
                    terminalState = if (terminalState.phase == RecordingPhase.ERROR) {
                        terminalState.copy(
                            message = listOfNotNull(terminalState.message, verificationMessage).joinToString(" "),
                        )
                    } else {
                        RecordingUiState(phase = RecordingPhase.ERROR, message = verificationMessage)
                    }
                }
                if ((speechProcessingError != null || speechCleanupError != null || translationCleanupError != null ||
                        translationEngineCleanupError != null) &&
                    !pipelineCancelled && !shouldDiscardAudio && terminalState.phase != RecordingPhase.ERROR
                ) {
                    terminalState = RecordingUiState(
                        phase = RecordingPhase.ERROR,
                        message = userFacingError(
                            speechProcessingError ?: speechCleanupError ?: translationCleanupError
                            ?: requireNotNull(translationEngineCleanupError),
                        ),
                    )
                }

                terminalState = terminalState.copy(
                    translationMessage = RecordingUiStateStore.state.value.translationMessage,
                    pendingTranslationCount = RecordingUiStateStore.state.value.pendingTranslationCount,
                )

                if (sessionWasStored) {
                    val finalStatus = when {
                        shouldDiscardAudio || writerCleanupError != null || writerPrefixDiscarded -> SessionStatus.FAILED
                        writerFailed || writerFinalizationError != null ||
                            speechProcessingError != null || speechCleanupError != null ||
                            translationCleanupError != null || translationEngineCleanupError != null -> SessionStatus.INTERRUPTED
                        terminalState.phase == RecordingPhase.ERROR || pipelineCancelled || sourceCleanupError != null ->
                            SessionStatus.INTERRUPTED
                        stopSignalWon -> SessionStatus.COMPLETED
                        else -> SessionStatus.INTERRUPTED
                    }
                    runCatching {
                        repository?.finishSession(
                            sessionId = requireNotNull(session).id,
                            endedAtMillis = System.currentTimeMillis(),
                            status = finalStatus,
                        )
                    }.onFailure { error ->
                        if (terminalState.phase != RecordingPhase.ERROR) {
                            terminalState = RecordingUiState(
                                phase = RecordingPhase.ERROR,
                                message = userFacingError(error),
                            )
                        }
                    }
                }

                withContext(Dispatchers.Main.immediate) {
                    finishCapture(sessionStartId, terminalState)
                }
            }
        }
    }

    /** Runs on the service main thread, ordered with onStartCommand. */
    private fun finishCapture(sessionStartId: Int, terminalState: RecordingUiState) {
        captureJob = null
        stopSignal = null
        isFinishingCapture = false

        if (isDestroyed) {
            releaseCaptureLease()
            pendingStartId = null
            pendingLanguages = null
            RecordingUiStateStore.publish(terminalState)
            return
        }

        val queuedStartId = pendingStartId
        val queuedLanguages = pendingLanguages
        pendingStartId = null
        pendingLanguages = null
        if (queuedStartId != null) {
            // Keep CAPTURE held across the handoff so STORAGE cannot slip between two sessions.
            startCapture(
                queuedStartId,
                queuedLanguages ?: DEFAULT_LANGUAGES,
                handoffLease = captureLease.get(),
            )
            return
        }

        releaseCaptureLease()
        RecordingUiStateStore.publish(terminalState)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(maxOf(latestStartId, sessionStartId))
    }

    private fun failToStart(
        startId: Int,
        message: String,
        releaseLease: SessionOperationGate.Lease? = captureLease.get(),
    ) {
        releaseCaptureLease(releaseLease)
        RecordingUiStateStore.publish(
            RecordingUiState(phase = RecordingPhase.ERROR, message = message),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun releaseCaptureLease(expected: SessionOperationGate.Lease? = captureLease.get()) {
        if (expected != null && captureLease.compareAndSet(expected, null)) expected.close()
    }

    private fun readLanguages(intent: Intent): Pair<Language, Language> {
        val source = intent.getStringExtra(EXTRA_SOURCE_LANGUAGE)
            ?.let { runCatching { Language.valueOf(it) }.getOrNull() }
            ?: Language.ENGLISH
        val target = intent.getStringExtra(EXTRA_TARGET_LANGUAGE)
            ?.let { runCatching { Language.valueOf(it) }.getOrNull() }
            ?.takeIf { it != source }
            ?: Language.SPANISH.takeIf { source != Language.SPANISH }
            ?: Language.ENGLISH
        return source to target
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, MicrophoneRecordingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_notification_text))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    getString(R.string.stop_recording),
                    stopPendingIntent,
                ).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.recording_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun userFacingError(error: Throwable): String = when (error) {
        is SecurityException -> "No hay permiso para acceder al micrófono."
        is IOException -> error.message ?: "La captura o la transcripción se interrumpió."
        else -> error.message?.let { "No se pudo iniciar el procesamiento local: $it" }
            ?: "No se pudo iniciar la captura o transcripción local."
    }

    private fun Throwable.asCancellationException(message: String): CancellationException =
        (this as? CancellationException) ?: CancellationException(message).also { it.initCause(this) }

    companion object {
        private const val ACTION_START = "com.voztranslate.app.recording.START"
        private const val ACTION_STOP = "com.voztranslate.app.recording.STOP"
        private const val EXTRA_SOURCE_LANGUAGE = "com.voztranslate.app.recording.SOURCE_LANGUAGE"
        private const val EXTRA_TARGET_LANGUAGE = "com.voztranslate.app.recording.TARGET_LANGUAGE"
        private const val RECORDINGS_DIRECTORY = "recordings"
        private const val NOTIFICATION_CHANNEL_ID = "microphone_capture"
        private const val NOTIFICATION_ID = 101
        private const val OPEN_APP_REQUEST_CODE = 102
        private const val STOP_REQUEST_CODE = 103
        private const val ELAPSED_UPDATE_MS = 1_000L
        private const val SPEECH_FRAME_BUFFER_CAPACITY = 8
        private val DEFAULT_LANGUAGES = Language.ENGLISH to Language.SPANISH

        fun start(context: Context, sourceLanguage: Language, targetLanguage: Language) {
            try {
                val intent = Intent(context, MicrophoneRecordingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SOURCE_LANGUAGE, sourceLanguage.name)
                    .putExtra(EXTRA_TARGET_LANGUAGE, targetLanguage.name)
                context.startForegroundService(
                    intent,
                )
            } catch (error: SecurityException) {
                RecordingUiStateStore.publish(
                    RecordingUiState(
                        phase = RecordingPhase.ERROR,
                        message = "Android no permitió iniciar la captura del micrófono.",
                    ),
                )
            } catch (error: IllegalStateException) {
                RecordingUiStateStore.publish(
                    RecordingUiState(
                        phase = RecordingPhase.ERROR,
                        message = "Abre la aplicación y vuelve a iniciar la captura.",
                    ),
                )
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, MicrophoneRecordingService::class.java).setAction(ACTION_STOP),
                )
            } catch (error: IllegalStateException) {
                RecordingUiStateStore.publish(
                    RecordingUiState(
                        phase = RecordingPhase.ERROR,
                        message = "No se pudo detener el servicio de captura.",
                    ),
                )
            }
        }
    }
}
