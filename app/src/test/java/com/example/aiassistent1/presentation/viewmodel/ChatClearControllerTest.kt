package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.model.VoiceActivationMode
import com.example.aiassistent1.domain.model.AudioPreferences
import com.example.aiassistent1.domain.model.WakeWordEngine
import com.example.aiassistent1.domain.usecase.VoicePlaybackInputPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatClearControllerTest {
    private class Fixture(voice: Boolean = true) {
        val conversation = ConversationVoiceSession().apply { moveTo("general") }
        val clear = ChatClearController(conversation)
        var current = ChatClearController.Current("general", "model.gguf", voice)
        val results = mutableListOf<ChatClearController.Completion>()
        fun begin() = checkNotNull(clear.begin(current.chatId, current.modelName, current.voiceMode))
        suspend fun execute(request: ChatClearController.Request, generation: Job? = null,
            delete: suspend () -> Unit = {}) = clear.execute(request, generation, delete, { current }, results::add)
    }

    @Test fun previouslyEnabledVoiceResumesInEverySelectedActivationMode() = runTest {
        for (mode in VoiceActivationMode.entries) {
            val f = Fixture()
            f.execute(f.begin())
            assertTrue(f.results.single().resumeListening)
            val selected = AudioPreferences(wakeWordEnabled = mode != VoiceActivationMode.DIRECT,
                wakeWordEngine = if (mode == VoiceActivationMode.PERSONAL_WORD) WakeWordEngine.METRIC_KWS else WakeWordEngine.LEGACY_ASR)
            assertEquals(if (mode == VoiceActivationMode.DIRECT) VoicePlaybackInputPolicy.Capture.DIRECT
                else VoicePlaybackInputPolicy.Capture.ACTIVATION,
                VoicePlaybackInputPolicy.capture(selected, playbackActive = false))
            assertFalse(f.clear.isClearing("general"))
        }
    }

    @Test fun voiceThatWasOffCannotBeAutomaticallyEnabledByCompletion() = runTest {
        for (voiceAtCompletion in listOf(false, true)) {
            val f = Fixture(voice = false)
            val request = f.begin()
            f.current = f.current.copy(voiceMode = voiceAtCompletion)
            f.execute(request)
            assertFalse(f.results.single().resumeListening)
        }
    }

    @Test fun deleteAndResumeWaitForOldFinalWriteAndStorageCompletion() = runTest {
        val f = Fixture()
        val finalWrite = CompletableDeferred<Unit>()
        val deleted = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val generation = launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { finalWrite.await(); events += "old_final_write" } }
        }
        runCurrent()
        generation.cancel()
        val clearing = async {
            f.execute(f.begin(), generation) { events += "delete_started"; deleted.await(); events += "deleted" }
        }
        runCurrent()
        assertTrue(events.isEmpty())
        assertTrue(f.results.isEmpty())
        finalWrite.complete(Unit)
        runCurrent()
        assertEquals(listOf("old_final_write", "delete_started"), events)
        assertTrue(f.results.isEmpty())
        assertTrue(f.clear.isClearing("general"))
        deleted.complete(Unit)
        clearing.await()
        assertEquals(listOf("old_final_write", "delete_started", "deleted"), events)
        assertTrue(f.results.single().resumeListening)
    }

    @Test fun microphoneOffRevokesResumeEvenIfAStaleStateBecomesTrueAgain() = runTest {
        val f = Fixture()
        val deleted = CompletableDeferred<Unit>()
        val request = f.begin()
        val clearing = async { f.execute(request) { deleted.await() } }
        runCurrent()
        f.clear.cancelVoiceResume()
        f.current = f.current.copy(voiceMode = false)
        f.current = f.current.copy(voiceMode = true)
        deleted.complete(Unit)
        clearing.await()
        assertFalse(f.results.single().resumeListening)
        assertFalse(f.clear.isClearing("general"))
    }

    @Test fun currentVoiceOffAloneAlsoPreventsResume() = runTest {
        val f = Fixture()
        val request = f.begin()
        f.current = f.current.copy(voiceMode = false)
        f.execute(request)
        assertFalse(f.results.single().resumeListening)
    }

    @Test fun leavingAndReturningCannotReviveOldResumeOrLeaveTheChatBusy() = runTest {
        val f = Fixture()
        val request = f.begin()
        f.conversation.moveTo("calendar")
        f.conversation.moveTo("general")
        f.execute(request)
        assertFalse(f.results.single().currentConversation)
        assertFalse(f.results.single().resumeListening)
        assertFalse(f.clear.isClearing("general"))
    }

    @Test fun anotherPageOfTheSameCalendarConversationKeepsResumeValid() = runTest {
        val f = Fixture()
        f.conversation.moveTo("calendar")
        f.current = f.current.copy(chatId = "calendar")
        val request = f.begin()
        assertFalse(f.conversation.moveTo("calendar"))
        f.execute(request)
        assertTrue(f.results.single().resumeListening)
    }

    @Test fun modelChangeCannotResumeThePreviousCapture() = runTest {
        val f = Fixture()
        val request = f.begin()
        f.current = f.current.copy(modelName = "other.gguf")
        f.execute(request)
        assertFalse(f.results.single().resumeListening)
    }

    @Test fun duplicateClearDoesNotCreateAnotherOwnerAndOldInputIsInvalidImmediately() = runTest {
        val f = Fixture()
        val input = f.conversation.ticket("general")
        val request = f.begin()
        assertFalse(f.conversation.accepts(input, "general"))
        assertNull(f.clear.begin("general", "model.gguf", true))
        f.execute(request)
        assertEquals(1, f.results.size)
    }

    @Test fun clearsOfDifferentChatsKeepSeparateOwnership() = runTest {
        val f = Fixture()
        val first = f.begin()
        f.conversation.moveTo("calendar")
        f.current = f.current.copy(chatId = "calendar")
        val second = f.begin()
        f.execute(first)
        assertFalse(f.results.single().currentConversation)
        assertFalse(f.clear.isClearing("general"))
        assertTrue(f.clear.isClearing("calendar"))
        f.execute(second)
        assertTrue(f.results.last().resumeListening)
        assertFalse(f.clear.isClearing("calendar"))
    }

    @Test fun storageErrorReleasesBusyStateAndDoesNotSilentlyTurnVoiceOff() = runTest {
        val f = Fixture()
        f.execute(f.begin()) { error("storage failed") }
        assertEquals("storage failed", f.results.single().error?.message)
        assertTrue(f.results.single().resumeListening)
        assertFalse(f.clear.isClearing("general"))
    }

    @Test fun cancelledOwnerCannotCallCompletionOrResume() = runTest {
        val f = Fixture()
        val deleted = CompletableDeferred<Unit>()
        val clearing = async { f.execute(f.begin()) { deleted.await() } }
        runCurrent()
        clearing.cancelAndJoin()
        assertTrue(f.results.isEmpty())
        assertFalse(f.clear.isClearing("general"))
    }

    @Test fun staleVisibleConversationCannotBeginDeletion() {
        val f = Fixture()
        f.conversation.moveTo("calendar")
        assertNull(f.clear.begin("general", "model.gguf", true))
        assertFalse(f.clear.isClearing("general"))
    }
}
