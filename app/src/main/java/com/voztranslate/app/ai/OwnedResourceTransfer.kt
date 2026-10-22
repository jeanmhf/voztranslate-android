package com.voztranslate.app.ai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Creates a closeable resource on [dispatcher] and closes it if cancellation prevents ownership
 * from being transferred back to the caller. [beforeTransfer] is an internal test seam for the
 * cancellation window after creation and before the dispatcher handoff completes.
 */
internal suspend fun <T : AutoCloseable> createOwnedResourceOnDispatcher(
    dispatcher: CoroutineDispatcher,
    create: suspend () -> T,
    beforeTransfer: suspend (T) -> Unit = {},
): T {
    val pendingTransfer = AtomicReference<T?>(null)
    try {
        val resource = withContext(dispatcher) {
            create().also { created ->
                pendingTransfer.set(created)
                beforeTransfer(created)
            }
        }
        pendingTransfer.compareAndSet(resource, null)
        return resource
    } catch (error: Throwable) {
        runCatching { pendingTransfer.getAndSet(null)?.close() }
        throw error
    }
}
