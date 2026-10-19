package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.model.AudioPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageSendFeedbackTest {
    @Test fun waitsForRecorderReleaseAndSoundCompletionBeforeContinuingRequest() = runTest {
        val recorderReleased = CompletableDeferred<Unit>()
        val soundCompleted = CompletableDeferred<Unit>()
        var sounds = 0
        var requestContinued = false
        val feedback = MessageSendFeedback(
            preferences = { AudioPreferences() },
            awaitInputStopped = { recorderReleased.await() },
            playSound = { sounds++; soundCompleted.await() },
        )
        val request = async { feedback.play(); requestContinued = true }
        runCurrent()
        assertEquals(0, sounds)
        assertFalse(requestContinued)
        recorderReleased.complete(Unit)
        runCurrent()
        assertEquals(1, sounds)
        assertFalse(requestContinued)
        soundCompleted.complete(Unit)
        request.await()
        assertTrue(requestContinued)
        assertEquals(1, sounds)
    }

    @Test fun disabledFeedbackNeitherTouchesRecorderNorPlaysSound() = runTest {
        MessageSendFeedback(
            preferences = { AudioPreferences(messageSentSoundEnabled = false) },
            awaitInputStopped = { error("Recorder must not be touched") },
            playSound = { error("Sound must not play") },
            onFailure = { throw AssertionError(it) },
        ).play()
    }

    @Test fun disablingWhileRecorderStopsSuppressesPendingSound() = runTest {
        var preferences = AudioPreferences()
        var sounds = 0
        MessageSendFeedback(
            preferences = { preferences },
            awaitInputStopped = { preferences = preferences.copy(messageSentSoundEnabled = false) },
            playSound = { sounds++ },
        ).play()
        assertEquals(0, sounds)
    }

    @Test fun slowRecorderTimesOutWithoutPlayingSoundOrRejectingRequest() = runTest {
        var sounds = 0
        MessageSendFeedback(
            preferences = { AudioPreferences() },
            awaitInputStopped = { awaitCancellation() },
            playSound = { sounds++ },
        ).play()
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(0, sounds)
    }

    @Test fun playerTimeoutReleasesSoundAndAllowsRequestToContinue() = runTest {
        var released = false
        MessageSendFeedback(
            preferences = { AudioPreferences() }, awaitInputStopped = {},
            playSound = { try { awaitCancellation() } finally { released = true } },
        ).play()
        assertEquals(1_000L, testScheduler.currentTime)
        assertTrue(released)
    }

    @Test fun requestCancellationStopsSoundWithoutContinuingRequest() = runTest {
        var released = false
        var requestContinued = false
        val feedback = MessageSendFeedback(
            preferences = { AudioPreferences() }, awaitInputStopped = {},
            playSound = { try { awaitCancellation() } finally { released = true } },
        )
        val request = async { feedback.play(); requestContinued = true }
        runCurrent()
        request.cancelAndJoin()
        assertTrue(released)
        assertFalse(requestContinued)
        assertTrue(request.isCancelled)
    }

    @Test fun cancellationDuringRecorderReleaseNeverStartsSound() = runTest {
        var sounds = 0
        val feedback = MessageSendFeedback(
            preferences = { AudioPreferences() }, awaitInputStopped = { awaitCancellation() },
            playSound = { sounds++ },
        )
        val request = async { feedback.play() }
        runCurrent()
        request.cancelAndJoin()
        assertEquals(0, sounds)
    }

    @Test fun settingsCaptureAndPlaybackFailuresAreOptionalFeedbackFailures() = runTest {
        for (stage in 0..2) {
            val expected = IllegalStateException("feedback stage $stage failed")
            var failure: Exception? = null
            MessageSendFeedback(
                preferences = { if (stage == 0) throw expected else AudioPreferences() },
                awaitInputStopped = { if (stage == 1) throw expected },
                playSound = { if (stage == 2) throw expected },
                onFailure = { failure = it },
            ).play()
            // A coroutine boundary may recover an exception's stack into another instance.
            assertEquals(expected.javaClass, failure?.javaClass)
            assertEquals(expected.message, failure?.message)
        }
    }
}
