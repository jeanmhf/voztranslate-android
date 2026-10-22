package com.voztranslate.app.ai

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M2m100WorkSelectionTest {
    @Test
    fun selectsRequestedWorkWhenWorkManagerAcceptedTheNewRequest() {
        val retained = id("a1")
        val requested = id("b2")

        val selected = selectM2m100WorkId(
            requestedId = requested,
            knownPendingIds = setOf(retained),
            candidates = listOf(
                candidate(retained, M2m100WorkCandidate.State.RUNNING),
                candidate(requested, M2m100WorkCandidate.State.ENQUEUED),
            ),
        )

        assertEquals(requested, selected)
    }

    @Test
    fun usesKnownRetainedWorkIfNewRequestWasCancelledAndRetainedWorkAlreadyFinished() {
        val retained = id("a1")
        val requested = id("b2")
        val unrelatedActive = id("c3")

        val selected = selectM2m100WorkId(
            requestedId = requested,
            knownPendingIds = setOf(retained),
            candidates = listOf(
                candidate(requested, M2m100WorkCandidate.State.CANCELLED),
                candidate(retained, M2m100WorkCandidate.State.SUCCEEDED),
                candidate(unrelatedActive, M2m100WorkCandidate.State.RUNNING),
            ),
        )

        assertEquals("the terminal retained ID must not be replaced by unrelated work", retained, selected)
    }

    @Test
    fun returnsNullWhenThereAreNoWorkCandidates() {
        val selected = selectM2m100WorkId(
            requestedId = null,
            knownPendingIds = emptySet(),
            candidates = emptyList(),
        )

        assertNull(selected)
    }

    private fun id(suffix: String) = UUID.fromString("00000000-0000-0000-0000-0000000000$suffix")

    private fun candidate(id: UUID, state: M2m100WorkCandidate.State) = M2m100WorkCandidate(id, state)
}
