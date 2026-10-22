package com.voztranslate.app.recording

import com.voztranslate.app.domain.Language
import com.voztranslate.app.domain.PendingTranslationBatch
import com.voztranslate.app.domain.TranscriptSegment
import com.voztranslate.app.domain.TranslationEngine
import com.voztranslate.app.domain.TranslationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SessionTranslationCoordinatorTest {
    @Test
    fun partialsAreCoalescedThrottledAndRevisionsIncreaseAcrossAsrAndTranslation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clockIndex = AtomicInteger()
        val logicalNow = AtomicLong(-1L)
        val clockValues = longArrayOf(100L, 1_999L, 2_100L)
        val engine = ControlledTranslationEngine { logicalNow.get() }
        val persisted = Channel<TranscriptSegment>(Channel.UNLIMITED)
        val errors = Channel<Throwable>(Channel.UNLIMITED)
        val coordinator = coordinator(
            scope = scope,
            engine = engine,
            persisted = persisted,
            errors = errors,
            nowMillis = {
                clockValues[minOf(clockIndex.getAndIncrement(), clockValues.lastIndex)].also(logicalNow::set)
            },
        )

        try {
            coordinator.accept(segment(id = "same", revision = 0L, text = "hello"))
            val firstInput = persisted.receiveWithin()
            val firstCall = engine.receiveCall()
            assertEquals("hello", firstCall.text)
            assertEquals(Language.ENGLISH, firstCall.sourceLanguage)
            assertEquals(Language.SPANISH, firstCall.targetLanguage)
            firstCall.reply("hola")
            val firstTranslation = persisted.receiveWithin()
            assertEquals(1L, firstTranslation.revision)

            coordinator.accept(segment(id = "same", revision = 1L, text = "hello world"))
            val secondInput = persisted.receiveWithin()
            val secondCall = engine.receiveCall()
            assertEquals("hello world", secondCall.text)
            assertEquals(2_000L, secondCall.logicalStartMillis - firstCall.logicalStartMillis)
            assertTrue(
                "partial translation started before the 2-second throttle elapsed",
                secondCall.startedAtNanos - firstCall.startedAtNanos >= 80_000_000L,
            )
            secondCall.reply("hola mundo")
            val secondTranslation = persisted.receiveWithin()

            assertEquals(listOf(0L, 1L, 2L, 3L), listOf(
                firstInput.revision,
                firstTranslation.revision,
                secondInput.revision,
                secondTranslation.revision,
            ))
            assertEquals("hola mundo", secondTranslation.translatedText)
            assertTrue(errors.tryReceive().isFailure)
            assertTrue("only the latest partial text should be translated", engine.calls.tryReceive().isFailure)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun finalRevisionReplacesPartialImmediatelyAndDiscardsItsLateResult() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ControlledTranslationEngine(nonCancellableCallIndexes = setOf(0))
        val persisted = Channel<TranscriptSegment>(Channel.UNLIMITED)
        val errors = Channel<Throwable>(Channel.UNLIMITED)
        val store = InMemoryPendingTranslationStore()
        val coordinator = coordinator(scope, engine, persisted, errors, store)

        try {
            coordinator.accept(segment(id = "final", revision = 4L, text = "draft"))
            val partialInput = persisted.receiveWithin()
            val staleCall = engine.receiveCall()
            val finalStartedAt = System.nanoTime()

            coordinator.accept(segment(id = "final", revision = 5L, text = "finished", isFinal = true))
            val finalInput = persisted.receiveWithin()
            assertEquals(5L, finalInput.revision)
            assertNull(finalInput.translatedText)

            // A final request must bypass the partial two-second throttle.
            val finalCall = engine.receiveCall(timeoutMillis = 1_500L)
            assertEquals("finished", finalCall.text)
            assertTrue(finalCall.startedAtNanos - finalStartedAt < 1_500_000_000L)

            // Let a deliberately non-cooperative old partial return after it was superseded.
            staleCall.reply("stale translation")
            staleCall.awaitFinished()
            assertTrue(staleCall.cancelled.isCompleted)
            finalCall.reply("traducción definitiva")
            val finalTranslation = persisted.receiveWithin()

            assertEquals(6L, finalTranslation.revision)
            assertEquals("traducción definitiva", finalTranslation.translatedText)
            assertTrue(persisted.tryReceive().isFailure)
            assertTrue(errors.tryReceive().isFailure)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun closeCancelsPartialsAndWaitsForAcceptedFinalTranslationsToDrain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ControlledTranslationEngine()
        val persisted = Channel<TranscriptSegment>(Channel.UNLIMITED)
        val errors = Channel<Throwable>(Channel.UNLIMITED)
        val store = InMemoryPendingTranslationStore()
        val coordinator = coordinator(scope, engine, persisted, errors, store)

        try {
            coordinator.accept(segment(id = "partial", revision = 0L, text = "provisional"))
            persisted.receiveWithin()
            val partialCall = engine.receiveCall()

            coordinator.accept(segment(id = "final", revision = 0L, text = "complete", isFinal = true))
            persisted.receiveWithin()
            val finalCall = engine.receiveCall()

            val closing = scope.launch { coordinator.closeAndJoin() }
            partialCall.awaitCancelled()
            assertFalse("closeAndJoin returned before the final translation settled", closing.isCompleted)

            finalCall.reply("completo")
            withTimeout(TEST_TIMEOUT_MILLIS) { closing.join() }
            val translation = persisted.receiveWithin()

            assertEquals("final", translation.id)
            assertEquals("completo", translation.translatedText)
            assertEquals(1L, translation.revision)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun engineFailureIsReportedWithoutStoppingLaterSegmentTranslations() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ControlledTranslationEngine()
        val persisted = Channel<TranscriptSegment>(Channel.UNLIMITED)
        val errors = Channel<Throwable>(Channel.UNLIMITED)
        val coordinator = coordinator(scope, engine, persisted, errors, InMemoryPendingTranslationStore())

        try {
            coordinator.accept(segment(id = "broken", revision = 0L, text = "will fail"))
            persisted.receiveWithin()
            val failedCall = engine.receiveCall()
            failedCall.fail(IOException("native translator unavailable"))

            val reported = errors.receiveWithin()
            assertTrue(reported is IOException)
            assertEquals("native translator unavailable", reported.message)
            assertTrue(scope.isActive)

            coordinator.accept(segment(id = "next", revision = 0L, text = "still running"))
            persisted.receiveWithin()
            val nextCall = engine.receiveCall()
            nextCall.reply("sigue funcionando")
            val translated = persisted.receiveWithin()

            assertEquals("next", translated.id)
            assertEquals("sigue funcionando", translated.translatedText)
            assertNotEquals("the failed segment must not receive a translation", "broken", translated.id)
            withTimeout(TEST_TIMEOUT_MILLIS) { coordinator.closeAndJoin() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun finalAcceptsDoNotBackpressurePcmAndDurableRowsDrainInOrderAcrossBatches() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = ControlledTranslationEngine()
        val persisted = Channel<TranscriptSegment>(Channel.UNLIMITED)
        val errors = Channel<Throwable>(Channel.UNLIMITED)
        val backlogChanges = Channel<Int>(Channel.UNLIMITED)
        val store = InMemoryPendingTranslationStore()
        val coordinator = coordinator(
            scope = scope,
            engine = engine,
            persisted = persisted,
            errors = errors,
            store = store,
            backlogChanges = backlogChanges,
            pendingBatchSize = 16,
        )
        val ids = (0 until 20).map { "final-${it.toString().padStart(2, '0')}" }

        try {
            var simulatedPcmFramesConsumed = 0
            coordinator.accept(segment(id = ids.first(), revision = 0L, text = "phrase-0", isFinal = true))
            val firstAsrRevision = persisted.receiveWithin()
            assertEquals(ids.first(), firstAsrRevision.id)
            assertEquals(1, backlogChanges.receiveWithin())
            val firstCall = engine.receiveCall()
            simulatedPcmFramesConsumed += 1

            withTimeout(1_000L) {
                ids.drop(1).forEachIndexed { offset, id ->
                    val index = offset + 1
                    coordinator.accept(segment(id = id, revision = 0L, text = "phrase-$index", isFinal = true))
                    simulatedPcmFramesConsumed += 1
                }
            }

            assertEquals("all final revisions should be persisted before translation starts", 20, store.pendingCount())
            assertEquals("each non-blocking accept lets the PCM consumer progress", 20, simulatedPcmFramesConsumed)
            assertTrue("the serial worker must remain on the held first inference", engine.calls.tryReceive().isFailure)
            var reportedWhileTranslatorBlocked: Int? = null
            while (true) {
                val count = backlogChanges.tryReceive().getOrNull() ?: break
                reportedWhileTranslatorBlocked = count
            }
            ids.drop(1).forEachIndexed { offset, id ->
                val index = offset + 1
                val asrRevision = persisted.receiveWithin()
                assertEquals(id, asrRevision.id)
                assertEquals(0L, asrRevision.revision)
                assertEquals("phrase-$index", asrRevision.sourceText)
                assertNull(asrRevision.translatedText)
            }

            firstCall.reply("translation-0")
            val firstTranslation = persisted.receiveWithin()
            assertEquals(ids.first(), firstTranslation.id)
            assertEquals(1L, firstTranslation.revision)
            assertEquals("translation-0", firstTranslation.translatedText)
            assertEquals(19, backlogChanges.receiveWithin())

            for (index in 1 until ids.size) {
                val call = engine.receiveCall()
                val id = ids[index]
                assertEquals("phrase-$index", call.text)
                assertEquals(Language.ENGLISH, call.sourceLanguage)
                assertEquals(Language.SPANISH, call.targetLanguage)
                call.reply("translation-$index")

                val translated = persisted.receiveWithin()
                assertEquals(id, translated.id)
                assertEquals(1L, translated.revision)
                assertEquals("translation-$index", translated.translatedText)
                assertTrue(translated.isFinal)
                assertEquals(19 - index, backlogChanges.receiveWithin())
            }

            assertEquals(0, store.pendingCount())
            assertEquals(ids, store.allSegments().map { it.id })
            assertTrue(store.requestedLimits().all { it == 16 })
            assertTrue(errors.tryReceive().isFailure)
            assertEquals(
                "pendingTranslationCount should include all new durable finals while inference is blocked",
                20,
                reportedWhileTranslatorBlocked,
            )
            assertEquals(0, backlogChanges.receiveWithin())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedFinalRemainsDurableAndALaterWakeRetriesItBeforeNewerRows() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = ControlledTranslationEngine()
        val persisted = Channel<TranscriptSegment>(Channel.UNLIMITED)
        val errors = Channel<Throwable>(Channel.UNLIMITED)
        val backlogChanges = Channel<Int>(Channel.UNLIMITED)
        val store = InMemoryPendingTranslationStore()
        val coordinator = coordinator(scope, engine, persisted, errors, store, backlogChanges)

        try {
            coordinator.accept(segment(id = "oldest", revision = 0L, text = "first", isFinal = true))
            persisted.receiveWithin()
            val firstAttempt = engine.receiveCall()
            firstAttempt.fail(IOException("temporary translation failure"))

            val failure = errors.receiveWithin()
            assertTrue(failure is IOException)
            assertEquals(1, store.pendingCount())
            assertEquals(1, backlogChanges.receiveWithin())
            assertEquals(1, backlogChanges.receiveWithin())

            coordinator.accept(
                segment(
                    id = "newer",
                    revision = 0L,
                    text = "second",
                    isFinal = true,
                    startedAtMillis = 200L,
                ),
            )
            persisted.receiveWithin()
            assertEquals(2, store.pendingCount())

            assertEquals(2, backlogChanges.receiveWithin())
            assertEquals(2, backlogChanges.receiveWithin())
            val retry = engine.receiveCall()
            assertEquals("first", retry.text)
            retry.reply("primero")
            assertEquals("oldest", persisted.receiveWithin().id)

            val second = engine.receiveCall()
            assertEquals("second", second.text)
            second.reply("segundo")
            assertEquals("newer", persisted.receiveWithin().id)
            assertEquals(0, store.pendingCount())
            withTimeout(TEST_TIMEOUT_MILLIS) { coordinator.closeAndJoin() }
        } finally {
            scope.cancel()
        }
    }

    private fun coordinator(
        scope: CoroutineScope,
        engine: ControlledTranslationEngine,
        persisted: Channel<TranscriptSegment>,
        errors: Channel<Throwable>,
        store: InMemoryPendingTranslationStore = InMemoryPendingTranslationStore(),
        backlogChanges: Channel<Int>? = null,
        pendingBatchSize: Int = 16,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
        countPendingFinalTranslations: suspend () -> Int = { store.pendingCount() },
    ) = SessionTranslationCoordinator(
        scope = scope,
        engine = engine,
        sourceLanguage = Language.ENGLISH,
        targetLanguage = Language.SPANISH,
        persistAndPublish = {
            store.upsert(it)
            persisted.send(it)
        },
        loadPendingFinalTranslations = { limit -> store.load(limit) },
        countPendingFinalTranslations = countPendingFinalTranslations,
        onBacklogChanged = { backlogChanges?.trySend(it) },
        onTranslationError = { errors.trySend(it) },
        nowMillis = nowMillis,
        pendingBatchSize = pendingBatchSize,
    )

    private fun segment(
        id: String,
        revision: Long,
        text: String,
        isFinal: Boolean = false,
        startedAtMillis: Long = 100L,
    ) = TranscriptSegment(
        id = id,
        revision = revision,
        startedAtMillis = startedAtMillis,
        endedAtMillis = if (isFinal) startedAtMillis + 100L else null,
        sourceLanguage = Language.ENGLISH,
        sourceText = text,
        translatedText = null,
        speakerId = "speaker-1",
        isFinal = isFinal,
    )

    private suspend fun <T> Channel<T>.receiveWithin(): T =
        withTimeout(TEST_TIMEOUT_MILLIS) { receive() }

    private suspend fun ControlledTranslationEngine.receiveCall(timeoutMillis: Long = TEST_TIMEOUT_MILLIS): Call =
        withTimeout(timeoutMillis) { calls.receive() }

    private suspend fun Call.awaitFinished() {
        withTimeout(TEST_TIMEOUT_MILLIS) { finished.await() }
    }

    private suspend fun Call.awaitCancelled() {
        withTimeout(TEST_TIMEOUT_MILLIS) { cancelled.await() }
    }

    private class ControlledTranslationEngine(
        private val nonCancellableCallIndexes: Set<Int> = emptySet(),
        private val logicalClock: () -> Long = { -1L },
    ) : TranslationEngine {
        val calls = Channel<Call>(Channel.UNLIMITED)
        private val nextIndex = AtomicInteger()

        override suspend fun translate(
            text: String,
            sourceLanguage: Language,
            targetLanguage: Language,
        ): TranslationResult {
            val call = Call(
                index = nextIndex.getAndIncrement(),
                text = text,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                startedAtNanos = System.nanoTime(),
                logicalStartMillis = logicalClock(),
            )
            calls.send(call)
            try {
                val result = if (call.index in nonCancellableCallIndexes) {
                    withContext(NonCancellable) { call.response.await() }
                } else {
                    call.response.await()
                }
                return TranslationResult(result, sourceLanguage, targetLanguage)
            } finally {
                if (!currentCoroutineContext().isActive) call.cancelled.complete(Unit)
                call.finished.complete(Unit)
            }
        }
    }

    private class InMemoryPendingTranslationStore {
        private val rows = ConcurrentHashMap<String, TranscriptSegment>()
        private val loads = mutableListOf<Int>()

        @Synchronized
        fun upsert(segment: TranscriptSegment) {
            rows[segment.id] = segment
        }

        @Synchronized
        fun load(limit: Int): PendingTranslationBatch {
            loads += limit
            val pending = rows.values
                .filter { it.isFinal && it.translatedText == null }
                .sortedWith(compareBy<TranscriptSegment>({ it.startedAtMillis }, { it.id }))
            return PendingTranslationBatch(pending.take(limit), pending.size)
        }

        @Synchronized
        fun pendingCount(): Int = rows.values.count { it.isFinal && it.translatedText == null }

        @Synchronized
        fun allSegments(): List<TranscriptSegment> = rows.values.sortedBy { it.id }

        @Synchronized
        fun requestedLimits(): List<Int> = loads.toList()
    }

    private class Call(
        val index: Int,
        val text: String,
        val sourceLanguage: Language,
        val targetLanguage: Language,
        val startedAtNanos: Long,
        val logicalStartMillis: Long,
    ) {
        val response = CompletableDeferred<String>()
        val cancelled = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()

        fun reply(text: String) {
            response.complete(text)
        }

        fun fail(error: Throwable) {
            response.completeExceptionally(error)
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 3_000L
    }
}
