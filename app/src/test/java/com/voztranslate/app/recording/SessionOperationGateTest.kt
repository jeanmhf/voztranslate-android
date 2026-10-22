package com.voztranslate.app.recording

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionOperationGateTest {
    @Test
    fun captureLeaseExcludesStorageUntilCaptureCleanupReleasesIt() {
        val capture = SessionOperationGate.tryAcquireCapture()
        assertNotNull("capture admission should acquire the shared gate", capture)

        try {
            // The service acquires this lease before STARTING and retains it through cleanup.
            assertNull("storage deletion must not enter during capture/STARTING", SessionOperationGate.tryAcquireStorage())
            assertNull("a second capture must not enter while the first owns the gate", SessionOperationGate.tryAcquireCapture())
        } finally {
            capture?.close()
        }

        val storage = SessionOperationGate.tryAcquireStorage()
        assertNotNull("storage should enter after capture cleanup", storage)
        try {
            assertNull("capture must not enter while storage owns the gate", SessionOperationGate.tryAcquireCapture())
        } finally {
            storage?.close()
        }
    }

    @Test
    fun closingLeaseTwiceDoesNotReleaseTheNextOwnersLease() {
        val capture = SessionOperationGate.tryAcquireCapture()
        assertNotNull(capture)
        capture!!.close()

        val storage = SessionOperationGate.tryAcquireStorage()
        assertNotNull(storage)
        try {
            capture.close()
            assertNull("a stale idempotent close must not release the current owner", SessionOperationGate.tryAcquireCapture())
            assertNull("storage remains exclusive until its own lease closes", SessionOperationGate.tryAcquireStorage())
        } finally {
            storage?.close()
        }
    }
}
