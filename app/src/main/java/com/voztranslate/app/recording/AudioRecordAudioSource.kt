package com.voztranslate.app.recording

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import com.voztranslate.app.domain.AudioFrame
import com.voztranslate.app.domain.AudioSource
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Captures mono 16 kHz PCM16 frames. A single collector must consume [frames] for each instance. */
class AudioRecordAudioSource : AudioSource {
    private val frameChannel = Channel<AudioFrame>(capacity = CHANNEL_CAPACITY)
    private val reading = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableIsClientSilenced = MutableStateFlow(false)
    val isClientSilenced: StateFlow<Boolean> = mutableIsClientSilenced.asStateFlow()
    private val silenceLock = Any()

    @Volatile
    private var audioRecord: AudioRecord? = null

    private var hasStarted = false
    private var silenceCallbackActive = false
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var readJob: Job? = null

    override fun frames(): Flow<AudioFrame> = frameChannel.receiveAsFlow()

    override suspend fun start() = withContext(Dispatchers.IO) {
        check(!hasStarted) { "La fuente de audio solo puede iniciarse una vez." }
        hasStarted = true

        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            throw IOException("El dispositivo no admite captura PCM mono a 16 kHz.")
        }

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBufferBytes * 2, SAMPLES_PER_FRAME * 2 * 2))
            .build()

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("Android no pudo inicializar el micrófono.")
        }

        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val configuration = configs.firstOrNull { it.clientAudioSessionId == recorder.audioSessionId }
                updateClientSilenced(configuration?.isClientSilenced == true)
            }
        }

        try {
            synchronized(silenceLock) { silenceCallbackActive = true }
            recorder.registerAudioRecordingCallback(
                Executor { command -> command.run() },
                callback,
            )
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IOException("Android no inició la captura del micrófono.")
            }
            // Register before start to avoid missing a silence transition, then inspect the
            // current state because the first callback may arrive before recording is active.
            updateClientSilenced(recorder.activeRecordingConfiguration?.isClientSilenced == true)
        } catch (error: Throwable) {
            synchronized(silenceLock) {
                silenceCallbackActive = false
                mutableIsClientSilenced.value = false
            }
            runCatching { recorder.unregisterAudioRecordingCallback(callback) }
            recorder.release()
            throw error
        }

        audioRecord = recorder
        recordingCallback = callback
        reading.set(true)
        readJob = scope.launch { readFrames(recorder) }
    }

    override suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
        val recorder = audioRecord
        reading.set(false)
        synchronized(silenceLock) {
            silenceCallbackActive = false
            mutableIsClientSilenced.value = false
        }
        var stopError: Throwable? = null
        if (recorder != null) {
            recordingCallback?.let { callback ->
                runCatching { recorder.unregisterAudioRecordingCallback(callback) }
            }
            stopError = if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.stop() }.exceptionOrNull()
            } else {
                null
            }
            if (stopError == null) {
                readJob?.join()
                runCatching { recorder.release() }
            } else {
                // If stop cannot unblock a blocking read, release first; release invalidates the
                // native recorder and lets the reader leave before we finish cleanup.
                frameChannel.cancel(CancellationException("La fuente de audio no pudo detenerse limpiamente."))
                runCatching { recorder.release() }
                readJob?.cancelAndJoin()
            }
        }
        readJob = null
        audioRecord = null
        recordingCallback = null
        frameChannel.close()
        scope.cancel()
        if (stopError != null) {
            throw IOException("Android no pudo detener la captura de audio limpiamente.", stopError)
        }
    }

    override suspend fun abort() = withContext(NonCancellable + Dispatchers.IO) {
        reading.set(false)
        frameChannel.cancel(CancellationException("El consumidor canceló el flujo PCM."))
        synchronized(silenceLock) {
            silenceCallbackActive = false
            mutableIsClientSilenced.value = false
        }
        val recorder = audioRecord
        if (recorder != null) {
            recordingCallback?.let { callback ->
                runCatching { recorder.unregisterAudioRecordingCallback(callback) }
            }
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.stop() }
            }
            runCatching { recorder.release() }
        }
        readJob?.cancelAndJoin()
        readJob = null
        audioRecord = null
        recordingCallback = null
        scope.cancel()
    }

    override fun cancelPendingFrames(cause: Throwable) {
        val cancellation = CancellationException("Se canceló la entrega de frames PCM.")
        runCatching { cancellation.initCause(cause) }
        frameChannel.cancel(cancellation)
    }

    private fun updateClientSilenced(isSilenced: Boolean) {
        synchronized(silenceLock) {
            if (silenceCallbackActive) mutableIsClientSilenced.value = isSilenced
        }
    }

    private suspend fun readFrames(recorder: AudioRecord) {
        try {
            val samples = ShortArray(SAMPLES_PER_FRAME)
            var nextSequenceNumber = 0L
            while (reading.get()) {
                val readCount = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                when {
                    readCount > 0 -> {
                        val frame = AudioFrame(
                            samples = samples.copyOf(readCount),
                            sampleRateHz = SAMPLE_RATE_HZ,
                            capturedAtNanos = System.nanoTime(),
                            sequenceNumber = nextSequenceNumber,
                        )
                        // A slow disk consumer applies bounded backpressure. Frames are never
                        // silently discarded because the handoff buffer is temporarily full.
                        frameChannel.send(frame)
                        nextSequenceNumber += 1
                    }

                    readCount == AudioRecord.ERROR_DEAD_OBJECT ->
                        throw IOException("Android interrumpió la captura del micrófono.")

                    readCount < 0 ->
                        throw IOException("Error de AudioRecord: $readCount")
                }
            }
        } catch (error: Throwable) {
            if (reading.get()) frameChannel.close(error)
        } finally {
            reading.set(false)
            frameChannel.close()
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        private const val SAMPLES_PER_FRAME = SAMPLE_RATE_HZ / 10
        private const val CHANNEL_CAPACITY = 32
    }
}
