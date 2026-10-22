package com.voztranslate.app.recording

import com.voztranslate.app.domain.Language
import com.voztranslate.app.domain.PendingTranslationBatch
import com.voztranslate.app.domain.TranscriptSegment
import com.voztranslate.app.domain.TranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orders ASR and translation revisions for one session. Changed partials are coalesced per segment
 * and throttled to at most one inference start every two seconds. Final revisions are already
 * durable in Room before a conflated wake signal is sent to a single serial worker.
 */
internal class SessionTranslationCoordinator(
    private val scope: CoroutineScope,
    private val engine: TranslationEngine,
    private val sourceLanguage: Language,
    private val targetLanguage: Language,
    private val persistAndPublish: suspend (TranscriptSegment) -> Unit,
    private val loadPendingFinalTranslations: suspend (Int) -> PendingTranslationBatch,
    private val countPendingFinalTranslations: suspend () -> Int = {
        loadPendingFinalTranslations(1).totalCount
    },
    private val onBacklogChanged: (Int) -> Unit,
    private val onTranslationError: (Throwable) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    pendingBatchSize: Int = PENDING_BATCH_SIZE,
) {
    init {
        require(pendingBatchSize > 0) { "El lote de traducciones pendientes debe tener capacidad positiva." }
    }

    private val pendingBatchSize = pendingBatchSize
    private val mutex = Mutex()
    private val backlogMutex = Mutex()
    private val segments = mutableMapOf<String, SegmentState>()
    private val finalWakeups = Channel<Unit>(capacity = Channel.CONFLATED)
    private var closed = false
    private val finalWorker = scope.launch {
        for (ignored in finalWakeups) drainFinalTranslations()
    }

    /** Persist and publish the ASR revision before scheduling its translation. */
    suspend fun accept(asrSegment: TranscriptSegment) {
        val isFinal = mutex.withLock {
            check(!closed) { "El coordinador de traducción de la sesión ya está cerrado." }
            val prior = segments[asrSegment.id]
            if (prior != null && asrSegment.revision <= prior.sourceRevision) return

            val sourceChanged = prior?.segment?.sourceText != asrSegment.sourceText
            val generation = (prior?.generation ?: -1L) + 1L
            val revision = prior?.segment?.revision?.let { maxOf(it + 1L, asrSegment.revision) }
                ?: maxOf(0L, asrSegment.revision)
            val normalized = asrSegment.copy(
                revision = revision,
                // A final ASR revision must remain visibly pending until its final translation is
                // committed; the durable Room query uses this null to recover backlog safely.
                translatedText = if (sourceChanged || asrSegment.isFinal) null else prior.segment.translatedText,
            )
            val next = SegmentState(
                sourceRevision = asrSegment.revision,
                generation = generation,
                segment = normalized,
                lastPartialStartedAtMillis = prior?.lastPartialStartedAtMillis,
            )

            prior?.partialJob?.cancel()
            persistAndPublish(normalized)
            segments[asrSegment.id] = next

            if (!asrSegment.isFinal && sourceChanged) schedulePartialTranslationLocked(next)
            asrSegment.isFinal
        }

        if (isFinal) {
            // Update the visible backlog from the durable store on every final ASR save. This is
            // independent of the translation worker, which may be held inside a slow inference.
            publishCurrentBacklog()

            // The transcript row is the durable queue. This conflated signal is only a wakeup, so
            // slow inference cannot suspend the ASR collector or propagate pressure into PCM/WAV.
            val signal = finalWakeups.trySend(Unit)
            if (signal.isFailure) reportTranslationError(
                IllegalStateException("La revisión final quedó guardada, pero no se pudo notificar al worker de traducción."),
            )
        }
    }

    /** Cancel provisional work and drain all durable final rows before normal engine shutdown. */
    suspend fun closeAndJoin() {
        val partials = mutex.withLock {
            if (closed) return
            closed = true
            val jobs = segments.values.mapNotNull { it.partialJob }
            jobs.forEach(Job::cancel)
            finalWakeups.trySend(Unit)
            finalWakeups.close()
            jobs
        }
        partials.forEach { it.cancelAndJoin() }
        finalWorker.join()
    }

    private suspend fun drainFinalTranslations() {
        while (true) {
            val batch = try {
                loadPendingFinalTranslations(pendingBatchSize)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                reportTranslationError(error)
                return
            }
            publishCurrentBacklog()
            val candidate = batch.segments.firstOrNull() ?: return
            when (attemptFinalTranslation(candidate)) {
                TranslationAttempt.SAVED, TranslationAttempt.STALE -> Unit
                TranslationAttempt.FAILED -> return // Keep the durable row pending; retry on a later wake.
            }
        }
    }

    /** Serialize count reads with callback delivery so a slower stale query cannot overwrite a newer count. */
    private suspend fun publishCurrentBacklog() {
        try {
            backlogMutex.withLock {
                onBacklogChanged(countPendingFinalTranslations())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            reportTranslationError(error)
        }
    }

    private suspend fun attemptFinalTranslation(candidate: TranscriptSegment): TranslationAttempt {
        val request = mutex.withLock {
            val current = segments[candidate.id] ?: return@withLock null
            if (current.segment.revision != candidate.revision ||
                current.segment.sourceText != candidate.sourceText ||
                !current.segment.isFinal || current.segment.translatedText != null
            ) return@withLock null
            TranslationRequest(candidate.id, current.generation, current.sourceRevision, candidate.sourceText)
        } ?: return TranslationAttempt.STALE
        return attemptTranslation(request)
    }

    private fun schedulePartialTranslationLocked(state: SegmentState) {
        val request = TranslationRequest(
            segmentId = state.segment.id,
            generation = state.generation,
            sourceRevision = state.sourceRevision,
            text = state.segment.sourceText,
        )
        lateinit var scheduled: Job
        scheduled = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val waitMillis = mutex.withLock {
                    val current = currentState(request) ?: return@withLock null
                    val lastStart = current.lastPartialStartedAtMillis
                    if (lastStart == null) 0L
                    else (PARTIAL_INTERVAL_MILLIS - (nowMillis() - lastStart)).coerceAtLeast(0L)
                } ?: return@launch
                if (waitMillis > 0) delay(waitMillis)

                val mayRun = mutex.withLock {
                    val current = currentState(request) ?: return@withLock false
                    current.lastPartialStartedAtMillis = nowMillis()
                    true
                }
                if (mayRun) attemptTranslation(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                mutex.withLock {
                    val current = segments[request.segmentId]
                    if (current?.partialJob === scheduled) current.partialJob = null
                }
            }
        }
        state.partialJob = scheduled
        scheduled.start()
    }

    private suspend fun attemptTranslation(request: TranslationRequest): TranslationAttempt {
        val mayRun = mutex.withLock { currentState(request) != null }
        if (!mayRun) return TranslationAttempt.STALE

        val result = try {
            engine.translate(request.text, sourceLanguage, targetLanguage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (mutex.withLock { currentState(request) != null }) reportTranslationError(error)
            return TranslationAttempt.FAILED
        }

        return try {
            mutex.withLock {
                val current = currentState(request) ?: return@withLock TranslationAttempt.STALE
                val translated = current.segment.copy(
                    revision = current.segment.revision + 1L,
                    translatedText = result.text,
                )
                persistAndPublish(translated)
                current.segment = translated
                TranslationAttempt.SAVED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (mutex.withLock { currentState(request) != null }) reportTranslationError(error)
            TranslationAttempt.FAILED
        }
    }

    private fun currentState(request: TranslationRequest): SegmentState? = segments[request.segmentId]?.takeIf {
        it.generation == request.generation &&
            it.sourceRevision == request.sourceRevision &&
            it.segment.sourceText == request.text
    }

    private fun reportTranslationError(error: Throwable) {
        runCatching { onTranslationError(error) }
    }

    private class SegmentState(
        val sourceRevision: Long,
        val generation: Long,
        var segment: TranscriptSegment,
        var lastPartialStartedAtMillis: Long?,
        var partialJob: Job? = null,
    )

    private data class TranslationRequest(
        val segmentId: String,
        val generation: Long,
        val sourceRevision: Long,
        val text: String,
    )

    private enum class TranslationAttempt { SAVED, STALE, FAILED }

    private companion object {
        const val PARTIAL_INTERVAL_MILLIS = 2_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val PENDING_BATCH_SIZE = 16
    }
}
