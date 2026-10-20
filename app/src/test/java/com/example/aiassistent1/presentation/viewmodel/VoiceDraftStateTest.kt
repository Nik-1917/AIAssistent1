package com.example.aiassistent1.presentation.viewmodel

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.example.aiassistent1.domain.interfaces.VoiceDraftRepository
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceDraftStateTest {
    @Test fun `restoration shows exact saved text without automatically enabling the microphone`() {
        val text = "  Текст\n🙂 с пробелами  ".repeat(180)
        val restored = VoiceDraftState().opened().restored(text)
        assertEquals(text, restored.text)
        assertTrue(restored.isVisible)
        assertFalse(restored.isRecording)
        assertTrue(restored.isLimitReached)
        assertFalse(VoiceDraftState().restored("").isVisible)
    }
    @Test fun `recognized phrases update the counter including the joining space`() {
        var state = VoiceDraftState().opened().appendTranscript("  Купить молоко  ")
        assertEquals(13, state.characterCount)
        state = state.appendTranscript("  и хлеб  ")
        assertEquals("Купить молоко и хлеб", state.text)
        assertEquals(state.text.length, state.characterCount)
        assertTrue(state.isRecording)
        assertTrue(state.isVisible)
    }

    @Test fun `2999 characters can be opened for recording while 3000 and above cannot`() {
        assertTrue(VoiceDraftState(text = "а".repeat(2999)).opened().isRecording)
        for (size in listOf(3000, 3001)) {
            val full = VoiceDraftState(text = "а".repeat(size)).opened()
            assertTrue(full.isVisible)
            assertFalse(full.isRecording)
            assertTrue(full.isLimitReached)
            assertEquals(size, full.characterCount)
        }
    }

    @Test fun `exactly reaching the limit collapses and stops while preserving all text`() {
        val prefix = "а".repeat(2998)
        val full = VoiceDraftState(text = prefix).opened().appendTranscript("б")

        assertEquals("$prefix б", full.text)
        assertEquals(3000, full.characterCount)
        assertTrue(full.isLimitReached)
        assertFalse(full.isRecording)
        assertFalse(full.isVisible)
        assertEquals(full.text, full.opened().text)
        assertFalse(full.opened().isRecording)
    }

    @Test fun `a long final phrase is bounded and does not remain recording`() {
        val full = VoiceDraftState(text = "а".repeat(2990)).opened().appendTranscript("б".repeat(100))
        assertEquals("а".repeat(2990) + " " + "б".repeat(9), full.text)
        assertEquals(3000, full.characterCount)
        assertFalse(full.isRecording)
        assertFalse(full.isVisible)
    }

    @Test fun `joining space fills the final free character without leaving the microphone running`() {
        val full = VoiceDraftState(text = "а".repeat(2999)).opened().appendTranscript("б")
        assertEquals("а".repeat(2999) + " ", full.text)
        assertEquals(3000, full.characterCount)
        assertFalse(full.isRecording)
        assertFalse(full.opened().isRecording)
    }

    @Test fun `oversized first recognition is also bounded and collapsed`() {
        val full = VoiceDraftState().opened().appendTranscript("а".repeat(4000))
        assertEquals("а".repeat(3000), full.text)
        assertTrue(full.isLimitReached)
        assertFalse(full.isRecording)
        assertFalse(full.isVisible)
    }

    @Test fun `empty recognition and late events after collapse do not change text`() {
        val recording = VoiceDraftState(text = "Сохранить текст").opened()
        assertEquals(recording, recording.appendTranscript("  \n "))
        val collapsed = recording.copy(isVisible = false, isRecording = false)
        assertEquals(collapsed, collapsed.appendTranscript("Запоздалая фраза"))
    }

    @Test fun `noise preference survives appending collapse and reopening within the draft`() {
        val state = VoiceDraftState(noiseSuppressionEnabled = false).opened().appendTranscript("а".repeat(3000))
        assertFalse(state.noiseSuppressionEnabled)
        assertFalse(state.opened().noiseSuppressionEnabled)
        assertTrue(VoiceDraftState().noiseSuppressionEnabled)
    }

    @Test fun `automatically collapsed full text survives persistence and remains blocked after restore`() = runTest {
        var stored = ""
        val repository = object : VoiceDraftRepository {
            override suspend fun loadDraft() = stored
            override suspend fun saveDraft(text: String) { stored = text }
            override suspend fun clearDraft() { stored = "" }
        }
        val persistence = VoiceDraftPersistence(repository, backgroundScope) { fail(it) }
        val full = VoiceDraftState().opened().appendTranscript("а".repeat(3500))
        persistence.save(full.text)
        runCurrent()

        val restoredText = VoiceDraftPersistence(repository, backgroundScope) { fail(it) }.restore()!!
        val reopened = VoiceDraftState(text = restoredText).opened()
        assertEquals(full.text, reopened.text)
        assertEquals(3000, reopened.characterCount)
        assertFalse(reopened.isRecording)
        assertTrue(reopened.isVisible)
    }
}
