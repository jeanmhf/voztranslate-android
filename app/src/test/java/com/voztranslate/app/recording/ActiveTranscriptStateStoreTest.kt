package com.voztranslate.app.recording

import com.voztranslate.app.domain.Language
import com.voztranslate.app.domain.TranscriptSegment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveTranscriptStateStoreTest {
    @After
    fun resetGlobalState() {
        ActiveTranscriptStateStore.begin(CLEANUP_SESSION_ID)
        ActiveTranscriptStateStore.clearIfSessionDeleted(CLEANUP_SESSION_ID)
    }

    @Test
    fun clearsVisibleTranscriptWhenItsSessionWasDeleted() {
        ActiveTranscriptStateStore.begin("deleted-session")
        ActiveTranscriptStateStore.upsert("deleted-session", segment("segment-1", "hello"))

        ActiveTranscriptStateStore.clearIfSessionDeleted("deleted-session")

        assertNull(ActiveTranscriptStateStore.state.value.sessionId)
        assertEquals(emptyList<TranscriptSegment>(), ActiveTranscriptStateStore.state.value.segments)
    }

    @Test
    fun deletionResultForOldIdCannotClearTranscriptOfNewSession() {
        ActiveTranscriptStateStore.begin("old-session")
        ActiveTranscriptStateStore.upsert("old-session", segment("old-segment", "old text"))

        // Models a new capture becoming visible before the asynchronous delete result arrives.
        ActiveTranscriptStateStore.begin("new-session")
        val currentSegment = segment("new-segment", "new text")
        ActiveTranscriptStateStore.upsert("new-session", currentSegment)
        ActiveTranscriptStateStore.clearIfSessionDeleted("old-session")

        assertEquals("new-session", ActiveTranscriptStateStore.state.value.sessionId)
        assertEquals(listOf(currentSegment), ActiveTranscriptStateStore.state.value.segments)
    }

    private fun segment(id: String, text: String) = TranscriptSegment(
        id = id,
        revision = 1L,
        startedAtMillis = 10L,
        endedAtMillis = 20L,
        sourceLanguage = Language.ENGLISH,
        sourceText = text,
        translatedText = null,
        speakerId = "speaker-1",
        isFinal = true,
    )

    private companion object {
        const val CLEANUP_SESSION_ID = "__test_cleanup__"
    }
}
