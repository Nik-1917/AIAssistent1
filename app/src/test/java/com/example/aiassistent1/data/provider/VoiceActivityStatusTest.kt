package com.example.aiassistent1.data.provider

import com.example.aiassistent1.domain.model.AudioSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceActivityStatusTest {
    @Test fun rejectedSoundReturnsToWaitingAfterOneSecondWithoutMoreSpeech() = runTest {
        val status = VoiceActivityStatus(backgroundScope)
        status.begin(1)
        status.update(1, AudioSessionState.Waiting)
        status.update(1, AudioSessionState.Rejected)
        assertEquals(AudioSessionState.Rejected, status.state.value)

        advanceTimeBy(999)
        runCurrent()
        assertEquals(AudioSessionState.Rejected, status.state.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(AudioSessionState.Waiting, status.state.value)
    }

    @Test fun repeatedRejectedSoundsDoNotExtendTheFirstDeadline() = runTest {
        val status = VoiceActivityStatus(backgroundScope)
        status.begin(1)
        status.update(1, AudioSessionState.Waiting)
        status.update(1, AudioSessionState.Rejected)

        repeat(3) {
            advanceTimeBy(300)
            status.update(1, AudioSessionState.Rejected)
        }
        advanceTimeBy(99)
        status.update(1, AudioSessionState.Rejected)
        assertEquals(AudioSessionState.Rejected, status.state.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(AudioSessionState.Waiting, status.state.value)
    }

    @Test fun acceptedActivationImmediatelyReplacesRejectionAndSurvivesItsTimer() = runTest {
        val status = VoiceActivityStatus(backgroundScope)
        status.begin(1)
        status.update(1, AudioSessionState.Waiting)
        status.update(1, AudioSessionState.Rejected)
        advanceTimeBy(400)

        status.update(1, AudioSessionState.Listening)
        assertEquals(AudioSessionState.Listening, status.state.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(AudioSessionState.Listening, status.state.value)
    }

    @Test fun microphoneStopStaysIdleDespiteTimeoutAndLateRecognition() = runTest {
        val status = VoiceActivityStatus(backgroundScope)
        status.begin(1)
        status.update(1, AudioSessionState.Waiting)
        status.update(1, AudioSessionState.Rejected)
        advanceTimeBy(400)

        status.end(1)
        assertEquals(AudioSessionState.Idle, status.state.value)
        status.update(1, AudioSessionState.Listening)
        status.update(1, AudioSessionState.Rejected)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(AudioSessionState.Idle, status.state.value)
    }

    @Test fun replacedCaptureKeepsItsOwnRejectionDeadlineAndIgnoresOldCallbacks() = runTest {
        val status = VoiceActivityStatus(backgroundScope)
        status.begin(1)
        status.update(1, AudioSessionState.Waiting)
        status.update(1, AudioSessionState.Rejected)
        advanceTimeBy(400)

        status.begin(2)
        assertEquals(AudioSessionState.Idle, status.state.value)
        status.update(2, AudioSessionState.Listening)
        status.update(2, AudioSessionState.Rejected)
        status.end(1)
        status.update(1, AudioSessionState.Waiting)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(AudioSessionState.Rejected, status.state.value)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(AudioSessionState.Listening, status.state.value)
    }

    @Test fun rejectionAfterActivationHasItsOwnDeadlineAndRestoresListening() = runTest {
        val status = VoiceActivityStatus(backgroundScope)
        status.begin(1)
        status.update(1, AudioSessionState.Waiting)
        status.update(1, AudioSessionState.Rejected)
        advanceTimeBy(400)

        status.update(1, AudioSessionState.Listening)
        status.update(1, AudioSessionState.Rejected)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(AudioSessionState.Rejected, status.state.value)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(AudioSessionState.Listening, status.state.value)
    }

    @Test fun anotherSoundAfterTimeoutCanShowANewOneSecondNotice() = runTest {
        val status = VoiceActivityStatus(backgroundScope)
        status.begin(1)
        status.update(1, AudioSessionState.Waiting)
        status.update(1, AudioSessionState.Rejected)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(AudioSessionState.Waiting, status.state.value)

        advanceTimeBy(500)
        status.update(1, AudioSessionState.Rejected)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(AudioSessionState.Rejected, status.state.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(AudioSessionState.Waiting, status.state.value)
    }
}
