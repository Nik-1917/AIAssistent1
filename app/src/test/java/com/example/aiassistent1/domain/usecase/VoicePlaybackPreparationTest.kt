package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.VoiceInputSessionChangedException
import com.example.aiassistent1.domain.model.AudioPreferences
import com.example.aiassistent1.domain.model.WakeWordEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePlaybackPreparationTest {
    private class Capture {
        var preferences = AudioPreferences()
        var listening = true
        var session = 1L
        val requested = mutableListOf<Long>()
        val ready = mutableMapOf<Long, CompletableDeferred<Unit>>()
        var pauses = 0
        var pause: suspend () -> Unit = {}
        var conversation: () -> Unit = {}
        val preparation = VoicePlaybackPreparation(
            preferences = { preferences }, activationListening = { listening },
            pauseCapture = { pauses++; pause() },
            requestCapture = { requested += session; session },
            awaitCapture = { ready.getOrPut(it) { CompletableDeferred() }.await() },
            isCurrentCapture = { it == session }, ensureConversation = { conversation() },
        )
    }

    @Test fun directInputWaitsForRecorderReleaseAndNeverStartsActivationCapture() = runTest {
        val capture = Capture()
        val released = CompletableDeferred<Unit>()
        capture.pause = { released.await() }
        val playback = async { capture.preparation.await() }
        runCurrent()
        assertFalse(playback.isCompleted)
        assertEquals(1, capture.pauses)
        assertTrue(capture.requested.isEmpty())
        released.complete(Unit)
        playback.await()
    }

    @Test fun bothActivationModesWaitForTheExactLiveCapture() = runTest {
        for (engine in listOf(WakeWordEngine.LEGACY_ASR, WakeWordEngine.METRIC_KWS)) {
            val capture = Capture().apply { preferences = AudioPreferences(wakeWordEnabled = true, wakeWordEngine = engine, allowVoiceInterruption = true) }
            val playback = async { capture.preparation.await() }
            runCurrent()
            assertFalse(playback.isCompleted)
            assertEquals(listOf(1L), capture.requested)
            assertEquals(0, capture.pauses)
            capture.ready.getValue(1).complete(Unit)
            playback.await()
        }
    }

    @Test fun bothActivationModesWithSwitchOffWaitForRecorderRelease() = runTest {
        for (engine in listOf(WakeWordEngine.LEGACY_ASR, WakeWordEngine.METRIC_KWS)) {
            val capture = Capture().apply { preferences = AudioPreferences(wakeWordEnabled = true, wakeWordEngine = engine) }
            val released = CompletableDeferred<Unit>()
            capture.pause = { released.await() }
            val playback = async { capture.preparation.await() }
            runCurrent()
            assertFalse(playback.isCompleted)
            assertTrue(capture.requested.isEmpty())
            released.complete(Unit)
            playback.await()
        }
    }

    @Test fun switchDisabledDuringReadinessMustCloseCaptureBeforeSound() = runTest {
        for (engine in listOf(WakeWordEngine.LEGACY_ASR, WakeWordEngine.METRIC_KWS)) {
            val capture = Capture().apply {
                preferences = AudioPreferences(wakeWordEnabled = true, wakeWordEngine = engine, allowVoiceInterruption = true)
            }
            val released = CompletableDeferred<Unit>()
            capture.pause = { released.await() }
            val playback = async { capture.preparation.await() }
            runCurrent()
            // An old readiness signal must fail even before the session observer catches up.
            capture.preferences = capture.preferences.copy(allowVoiceInterruption = false)
            capture.ready.getValue(1).complete(Unit)
            runCurrent()
            assertFalse(playback.isCompleted)
            assertEquals(1, capture.pauses)
            released.complete(Unit)
            playback.await()
        }
    }

    @Test fun switchEnabledDuringReleaseMustWaitForReadyActivationCapture() = runTest {
        for (engine in listOf(WakeWordEngine.LEGACY_ASR, WakeWordEngine.METRIC_KWS)) {
            val capture = Capture().apply { preferences = AudioPreferences(wakeWordEnabled = true, wakeWordEngine = engine) }
            val released = CompletableDeferred<Unit>()
            capture.pause = { released.await() }
            val playback = async { capture.preparation.await() }
            runCurrent()
            capture.preferences = capture.preferences.copy(allowVoiceInterruption = true)
            released.complete(Unit)
            runCurrent()
            assertFalse(playback.isCompleted)
            assertEquals(listOf(1L), capture.requested)
            capture.ready.getValue(1).complete(Unit)
            playback.await()
        }
    }

    @Test fun switchingToDirectWhileWaitingRequiresReleaseBeforeSound() = runTest {
        val capture = Capture().apply { preferences = AudioPreferences(wakeWordEnabled = true, allowVoiceInterruption = true) }
        val released = CompletableDeferred<Unit>()
        capture.pause = { released.await() }
        val playback = async { capture.preparation.await() }
        runCurrent()
        capture.preferences = AudioPreferences()
        capture.ready.getValue(1).completeExceptionally(VoiceInputSessionChangedException())
        runCurrent()
        assertFalse(playback.isCompleted)
        assertEquals(1, capture.pauses)
        released.complete(Unit)
        playback.await()
    }

    @Test fun activationSelectedDuringReleaseCannotStartSoundWithoutReadyCapture() = runTest {
        val capture = Capture()
        val released = CompletableDeferred<Unit>()
        capture.pause = { released.await() }
        val playback = async { capture.preparation.await() }
        runCurrent()
        capture.preferences = AudioPreferences(wakeWordEnabled = true, allowVoiceInterruption = true)
        released.complete(Unit)
        runCurrent()
        assertFalse(playback.isCompleted)
        assertEquals(listOf(1L), capture.requested)
        capture.ready.getValue(1).complete(Unit)
        playback.await()
    }

    @Test fun oldNameCaptureCannotAuthorizePersonalWordAfterPolicyChange() = runTest {
        val capture = Capture().apply { preferences = AudioPreferences(wakeWordEnabled = true, allowVoiceInterruption = true) }
        val playback = async { capture.preparation.await() }
        runCurrent()
        capture.preferences = capture.preferences.copy(wakeWordEngine = WakeWordEngine.METRIC_KWS, policyRevision = 1)
        capture.session = 2
        capture.ready.getValue(1).complete(Unit)
        runCurrent()
        assertFalse(playback.isCompleted)
        assertEquals(listOf(1L, 2L), capture.requested)
        capture.ready.getValue(2).complete(Unit)
        playback.await()
    }

    @Test fun disablingVoiceModeDuringPreparationClosesCapture() = runTest {
        val capture = Capture().apply { preferences = AudioPreferences(wakeWordEnabled = true, allowVoiceInterruption = true) }
        val playback = async { capture.preparation.await() }
        runCurrent()
        capture.listening = false
        capture.ready.getValue(1).complete(Unit)
        playback.await()
        assertEquals(1, capture.pauses)
    }

    @Test fun failedRecorderReleaseCannotAuthorizeSound() = runTest {
        val capture = Capture().apply { pause = { error("release failed") } }
        val result = runCatching { capture.preparation.await() }
        assertEquals("release failed", result.exceptionOrNull()?.message)
        assertTrue(capture.requested.isEmpty())
    }

    @Test fun cancelledPlaybackDoesNotCancelSharedCaptureReadiness() = runTest {
        val capture = Capture().apply { preferences = AudioPreferences(wakeWordEnabled = true, allowVoiceInterruption = true) }
        val playback = async { capture.preparation.await() }
        runCurrent()
        playback.cancelAndJoin()
        assertFalse(capture.ready.getValue(1).isCancelled)
        capture.ready.getValue(1).complete(Unit)
        capture.preparation.await()
    }

    @Test fun conversationChangeAfterCaptureReadinessStillCancelsSound() = runTest {
        val capture = Capture().apply { preferences = AudioPreferences(wakeWordEnabled = true, allowVoiceInterruption = true) }
        val playback = async { capture.preparation.await() }
        runCurrent()
        capture.conversation = { throw CancellationException("conversation changed") }
        capture.ready.getValue(1).complete(Unit)
        runCurrent()
        assertTrue(runCatching { playback.await() }.exceptionOrNull() is CancellationException)
    }
}
