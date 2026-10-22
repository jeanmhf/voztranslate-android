package com.voztranslate.app.recording

import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobCompletionSignalTest {
    @Test
    fun cancellationBeforeCoroutineBodyStartsStillCompletesFailureSignal() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val signal = CompletableDeferred<Throwable?>()
        var bodyStarted = false
        val job = scope.launch {
            bodyStarted = true
            signal.complete(null)
        }
        job.completeSignalOnCompletion(signal)

        job.cancel(CancellationException("cancelled before coroutine start"))
        dispatcher.runAll()
        withTimeout(5_000) { job.join() }

        val completion = withTimeout(5_000) { signal.await() }
        assertFalse("the cancelled coroutine body must not run", bodyStarted)
        assertNotNull("completion signal must contain the cancellation", completion)
        assertTrue(completion is CancellationException)
        scope.cancel()
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            synchronized(pending) { pending.addLast(block) }
        }

        fun runAll() {
            while (true) {
                val next = synchronized(pending) { pending.pollFirst() } ?: return
                next.run()
            }
        }
    }
}
