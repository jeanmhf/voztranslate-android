package com.voztranslate.app.ai

import com.voztranslate.app.domain.Language
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class M2m100TranslationEngineTest {
    @Test
    fun usesPinnedFairseqLanguageTokenIdsForEnglishSpanishAndMandarin() {
        assertEquals(128_022, M2m100TokenIds.forLanguage(Language.ENGLISH))
        assertEquals(128_023, M2m100TokenIds.forLanguage(Language.SPANISH))
        assertEquals(128_102, M2m100TokenIds.forLanguage(Language.MANDARIN))
        assertEquals(128_004 + 18, M2m100TokenIds.forLanguage(Language.ENGLISH))
        assertEquals(128_004 + 19, M2m100TokenIds.forLanguage(Language.SPANISH))
        assertEquals(128_004 + 98, M2m100TokenIds.forLanguage(Language.MANDARIN))
    }

    @Test
    fun sendsExpectedRequestForAllSixLanguageDirections() = runBlocking {
        val port = FakeInferencePort()
        val engine = M2m100TranslationEngine(port, Dispatchers.Unconfined)
        val languages = listOf(Language.ENGLISH, Language.SPANISH, Language.MANDARIN)
        val pinnedTokenIds = mapOf(
            Language.ENGLISH to 128_022,
            Language.SPANISH to 128_023,
            Language.MANDARIN to 128_102,
        )

        languages.forEach { source ->
            languages.filterNot { it == source }.forEach { target ->
                val result = engine.translate("hello, world", source, target)
                val request = port.requests.last()

                assertEquals("translated: hello, world", result.text)
                assertEquals(source, result.sourceLanguage)
                assertEquals(target, result.targetLanguage)
                assertEquals("hello, world", request.text)
                assertEquals(pinnedTokenIds.getValue(source), request.sourceLanguageTokenId)
                assertEquals(pinnedTokenIds.getValue(target), request.targetLanguageTokenId)
                assertEquals(M2m100TokenIds.EOS, request.decoderStartTokenId)
                assertEquals(request.targetLanguageTokenId, request.forcedFirstGeneratedTokenId)
                assertEquals(M2m100TokenIds.EOS, request.eosTokenId)
                assertEquals(M2M100_DEFAULT_MAX_LENGTH, request.maxLength)
            }
        }

        assertEquals(6, port.requests.size)
        engine.close()
        assertEquals(1, port.closeCount)
    }

    @Test
    fun identicalLanguageReturnsInputWithoutInvokingInference() = runBlocking {
        val port = FakeInferencePort()
        val engine = M2m100TranslationEngine(port, Dispatchers.Unconfined)

        val result = engine.translate("保持原文", Language.MANDARIN, Language.MANDARIN)

        assertEquals("保持原文", result.text)
        assertEquals(Language.MANDARIN, result.sourceLanguage)
        assertEquals(Language.MANDARIN, result.targetLanguage)
        assertTrue(port.requests.isEmpty())
        engine.close()
    }

    @Test
    fun rejectsBlankTextAndDoesNotCallInferencePort() {
        val port = FakeInferencePort()
        val engine = M2m100TranslationEngine(port, Dispatchers.Unconfined)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.translate(" \n\t", Language.ENGLISH, Language.SPANISH) }
        }

        assertTrue(port.requests.isEmpty())
        engine.close()
    }

    @Test
    fun closedEngineRejectsDifferentLanguageInference() {
        val port = FakeInferencePort()
        val engine = M2m100TranslationEngine(port, Dispatchers.Unconfined)
        engine.close()

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { engine.translate("hello", Language.ENGLISH, Language.SPANISH) }
        }

        assertTrue(error.message.orEmpty().contains("cerrado", ignoreCase = true))
        assertEquals(1, port.closeCount)
        assertTrue(port.requests.isEmpty())
    }

    @Test
    fun siblingIOExceptionSetsCancellationFlagWhileInferencePortIsBlocked() = runBlocking {
        val port = BlockingInferencePort()
        val engine = M2m100TranslationEngine(port, Dispatchers.IO)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failure = IOException("sibling translation failure")
        val outcome = CompletableDeferred<Throwable?>()
        val operation = scope.launch {
            try {
                coroutineScope {
                    launch {
                        port.entered.await()
                        throw failure
                    }
                    engine.translate("hello", Language.ENGLISH, Language.SPANISH)
                }
                outcome.complete(null)
            } catch (error: Throwable) {
                outcome.complete(error)
            }
        }

        try {
            val cancellation = awaitCancellationFlag(port)
            assertTrue("child failure must signal the blocked port before it returns", cancellation.get())
            assertFalse("the fake port should still be blocked", port.releaseRequested.get())

            port.release()
            val observed = withTimeout(5_000) { outcome.await() }
            assertEquals(IOException::class.java, observed?.javaClass)
            assertEquals(failure.message, observed?.message)
            assertTrue("the sibling IOException should remain in the cause chain", observed.containsIdentity(failure))
            withTimeout(5_000) { operation.join() }
        } finally {
            port.release()
            operation.cancelAndJoin()
            scope.cancel()
            engine.close()
        }
        assertEquals(1, port.closeCount)
    }

    @Test
    fun explicitCallerCancellationStillSignalsBlockedInferencePort() = runBlocking {
        val port = BlockingInferencePort()
        val engine = M2m100TranslationEngine(port, Dispatchers.IO)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val outcome = CompletableDeferred<Throwable?>()
        val operation = scope.launch {
            try {
                engine.translate("hello", Language.ENGLISH, Language.SPANISH)
                outcome.complete(null)
            } catch (error: Throwable) {
                outcome.complete(error)
            }
        }

        try {
            withTimeout(5_000) { port.entered.await() }
            val cancellation = requireNotNull(port.cancellationToken.get())
            operation.cancel()
            awaitCancellationFlag(port)
            assertTrue(cancellation.get())
            assertFalse("explicit cancellation must be signalled before releasing inference", port.releaseRequested.get())

            port.release()
            val observed = withTimeout(5_000) { outcome.await() }
            assertTrue(observed is CancellationException)
            withTimeout(5_000) { operation.join() }
        } finally {
            port.release()
            operation.cancelAndJoin()
            scope.cancel()
            engine.close()
        }
        assertEquals(1, port.closeCount)
    }

    private suspend fun awaitCancellationFlag(port: BlockingInferencePort): AtomicBoolean = withTimeout(5_000) {
        while (true) {
            val flag = port.cancellationToken.get()
            if (flag != null && flag.get()) return@withTimeout flag
            delay(1)
        }
        error("Timed out waiting for inference cancellation flag")
    }

    private fun Throwable?.containsIdentity(expected: Throwable): Boolean {
        var current = this
        while (current != null) {
            if (current === expected) return true
            current = current.cause
        }
        return false
    }

    private class FakeInferencePort : M2m100InferencePort {
        val requests = mutableListOf<M2m100InferenceRequest>()
        var closeCount: Int = 0
            private set

        override fun translate(request: M2m100InferenceRequest): String {
            requests += request
            return "translated: ${request.text}"
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class BlockingInferencePort : M2m100InferencePort {
        val entered = CompletableDeferred<Unit>()
        val cancellationToken = AtomicReference<AtomicBoolean?>()
        val releaseRequested = AtomicBoolean(false)
        var closeCount = 0
            private set
        private val releaseLatch = CountDownLatch(1)

        override fun translate(request: M2m100InferenceRequest): String =
            error("M2M100 engine must pass the cancellation token to the inference port")

        override fun translate(request: M2m100InferenceRequest, cancelled: AtomicBoolean): String {
            cancellationToken.set(cancelled)
            entered.complete(Unit)
            if (!releaseLatch.await(5, TimeUnit.SECONDS)) {
                throw IOException("Timed out waiting for the test to release the fake inference port")
            }
            return "translated: ${request.text}"
        }

        fun release() {
            releaseRequested.set(true)
            releaseLatch.countDown()
        }

        override fun close() {
            closeCount += 1
        }
    }
}
