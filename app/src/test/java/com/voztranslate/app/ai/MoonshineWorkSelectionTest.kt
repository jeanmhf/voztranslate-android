package com.voztranslate.app.ai

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoonshineWorkSelectionTest {
    @Test
    fun selectsKeepRetainedWorkEvenIfItFinishedBeforeFirstObservationSnapshot() {
        val retainedId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val newRequestId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
        val unrelatedActiveId = UUID.fromString("00000000-0000-0000-0000-0000000000c3")

        MoonshineWorkCandidate.State.values()
            .filter { state -> state == MoonshineWorkCandidate.State.SUCCEEDED || state == MoonshineWorkCandidate.State.FAILED || state == MoonshineWorkCandidate.State.CANCELLED }
            .forEach { terminalState ->
                val effectiveId = selectMoonshineWorkId(
                    requestedId = newRequestId,
                    knownPendingIds = setOf(retainedId),
                    candidates = listOf(
                        MoonshineWorkCandidate(retainedId, terminalState),
                        MoonshineWorkCandidate(unrelatedActiveId, MoonshineWorkCandidate.State.RUNNING),
                    ),
                )

                assertEquals("retained job at $terminalState", retainedId, effectiveId)
            }
    }

    @Test
    fun prefersKnownKeepRetainedWorkOverCancelledRequestAndUnrelatedRunningWork() {
        val retainedId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val newRequestId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
        val unrelatedActiveId = UUID.fromString("00000000-0000-0000-0000-0000000000c3")

        val effectiveId = selectMoonshineWorkId(
            requestedId = newRequestId,
            knownPendingIds = setOf(retainedId),
            candidates = listOf(
                MoonshineWorkCandidate(newRequestId, MoonshineWorkCandidate.State.CANCELLED),
                MoonshineWorkCandidate(retainedId, MoonshineWorkCandidate.State.SUCCEEDED),
                MoonshineWorkCandidate(unrelatedActiveId, MoonshineWorkCandidate.State.RUNNING),
            ),
        )

        assertEquals("KEEP retained A even though request B is present as CANCELLED", retainedId, effectiveId)
    }

    @Test
    fun prefersTheNewRequestWhenWorkManagerActuallyEnqueuedIt() {
        val retainedId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val newRequestId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

        val effectiveId = selectMoonshineWorkId(
            requestedId = newRequestId,
            knownPendingIds = setOf(retainedId),
            candidates = listOf(
                MoonshineWorkCandidate(retainedId, MoonshineWorkCandidate.State.RUNNING),
                MoonshineWorkCandidate(newRequestId, MoonshineWorkCandidate.State.ENQUEUED),
            ),
        )

        assertEquals(newRequestId, effectiveId)
    }

    @Test
    fun returnsNullWhenNoRequestedKnownOrActiveWorkIsAvailable() {
        val requestId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

        val effectiveId = selectMoonshineWorkId(
            requestedId = requestId,
            knownPendingIds = emptySet(),
            candidates = emptyList(),
        )

        assertNull(effectiveId)
    }
}
