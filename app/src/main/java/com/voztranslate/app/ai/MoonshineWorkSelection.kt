package com.voztranslate.app.ai

import java.util.UUID

/** Minimal WorkInfo projection so KEEP selection can be tested without WorkManager or Android. */
internal data class MoonshineWorkCandidate(
    val id: UUID,
    val state: State,
) {
    enum class State {
        ENQUEUED,
        RUNNING,
        BLOCKED,
        SUCCEEDED,
        FAILED,
        CANCELLED,
    }

    val isFinished: Boolean
        get() = state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED
}

/**
 * Picks the request retained by KEEP or the request it enqueued. `knownPendingIds` are IDs seen
 * before enqueue; WorkManager may keep one of those and finish it before observation starts.
 */
internal fun selectMoonshineWorkId(
    requestedId: UUID?,
    knownPendingIds: Set<UUID>,
    candidates: List<MoonshineWorkCandidate>,
): UUID? {
    val requested = requestedId?.let { id -> candidates.firstOrNull { it.id == id } }
    // KEEP may persist the new request as CANCELLED when it ignores it in favor of an older
    // unfinished request. Do not mistake that bookkeeping row for the effective work item.
    if (requested != null && requested.state != MoonshineWorkCandidate.State.CANCELLED) {
        return requested.id
    }

    // A previously unfinished ID is the one KEEP must preserve, even if it becomes terminal
    // before this query and an unrelated unique-work record is still active.
    return candidates.firstOrNull { it.id in knownPendingIds }?.id
        ?: candidates.firstOrNull { !it.isFinished }?.id
        ?: requested?.id
        ?: candidates.lastOrNull()?.id
}

