package com.voztranslate.app.ai

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedResourceTransferTest {
    @Test
    fun closesCreatedResourceWhenCancellationWinsBeforeOwnershipTransfer() = runBlocking {
        val createdResource = CompletableDeferred<CountingResource>()
        val transfer = async {
            createOwnedResourceOnDispatcher(
                dispatcher = kotlinx.coroutines.Dispatchers.Default,
                create = { CountingResource() },
                beforeTransfer = { resource ->
                    // This callback runs after the helper has retained the resource for cleanup.
                    createdResource.complete(resource)
                    awaitCancellation()
                },
            )
        }

        val resource = withTimeout(5_000) { createdResource.await() }
        transfer.cancelAndJoin()

        assertTrue(transfer.isCancelled)
        assertEquals("the helper must close a resource it could not transfer", 1, resource.closeCount.get())
    }

    @Test
    fun successfulTransferLeavesResourceOpenForTheNewOwner() = runBlocking {
        val resource = createOwnedResourceOnDispatcher(
            dispatcher = kotlinx.coroutines.Dispatchers.Default,
            create = { CountingResource() },
        )

        assertEquals(0, resource.closeCount.get())
        resource.close()
        assertEquals(1, resource.closeCount.get())
    }

    private class CountingResource : Closeable {
        val closeCount = AtomicInteger()

        override fun close() {
            closeCount.incrementAndGet()
        }
    }
}
