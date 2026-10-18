package com.example.aiassistent1.data.provider

import com.example.aiassistent1.domain.interfaces.VoiceInputSessionChangedException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoiceCaptureReadinessTest {
    @Test fun captureMustConfirmReadinessBeforeAnyWaiterCanProceed() = runTest {
        val gate = VoiceCaptureReadiness()
        gate.begin(1)
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.await(1) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.await(1) }
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertFalse(gate.ready(2))
        assertTrue(gate.ready(1))
        first.await(); second.await()
        assertFalse(gate.ready(1))
    }

    @Test fun replacedCaptureCannotAuthorizeTheNewSession() = runTest {
        val gate = VoiceCaptureReadiness()
        gate.begin(1)
        val old = async(start = CoroutineStart.UNDISPATCHED) { runCatching { gate.await(1) } }
        gate.begin(2)
        assertTrue(old.await().exceptionOrNull() is VoiceInputSessionChangedException)
        assertFalse(gate.ready(1))
        val current = async(start = CoroutineStart.UNDISPATCHED) { gate.await(2) }
        gate.end(1)
        assertFalse(current.isCompleted)
        gate.ready(2)
        current.await()
    }

    @Test fun stopInvalidatesEvenAnAlreadyReadySession() = runTest {
        val gate = VoiceCaptureReadiness()
        gate.begin(1); gate.ready(1); gate.await(1)
        gate.end(1)
        assertTrue(runCatching { gate.await(1) }.exceptionOrNull() is VoiceInputSessionChangedException)
        assertFalse(gate.ready(1))
    }

    @Test fun initializationFailureSurvivesCleanupAndWakesWaiters() = runTest {
        val gate = VoiceCaptureReadiness()
        gate.begin(1)
        val wait = async(start = CoroutineStart.UNDISPATCHED) { runCatching { gate.await(1) } }
        val failure = IllegalStateException("capture failed")
        gate.end(1, failure)
        gate.end(1)
        // Coroutine stack-trace recovery may copy an exception; preserve its type and cause text.
        for (actual in listOf(wait.await().exceptionOrNull(), runCatching { gate.await(1) }.exceptionOrNull())) {
            assertEquals(failure.javaClass, actual?.javaClass)
            assertEquals("capture failed", actual?.message)
        }
    }

    @Test fun cancellingOnePlaybackWaitDoesNotCancelMicrophoneReadiness() = runTest {
        val gate = VoiceCaptureReadiness()
        gate.begin(1)
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { gate.await(1) }
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        assertTrue(gate.ready(1))
        gate.await(1)
    }

    @Test fun readinessCompletedJustBeforeStopStillCannotEscapeInvalidation() = runTest {
        val gate = VoiceCaptureReadiness()
        gate.begin(1)
        val wait = async(start = CoroutineStart.UNDISPATCHED) { runCatching { gate.await(1) } }
        gate.ready(1)
        gate.end(1)
        assertTrue(wait.await().exceptionOrNull() is VoiceInputSessionChangedException)
    }
}
