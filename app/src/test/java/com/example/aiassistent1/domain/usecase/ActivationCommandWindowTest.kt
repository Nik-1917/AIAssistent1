package com.example.aiassistent1.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivationCommandWindowTest {
    @Test fun silenceExpiresAtFiveSecondsExactlyOnceWithoutAnotherAudioSegment() = runTest {
        val window = ActivationCommandWindow { testScheduler.currentTime }
        val events = Channel<String>(12)
        var cancellations = 0
        window.open()
        val waiting = async {
            assertNull(window.receive(events))
            if (window.expireIfSilent()) cancellations++
        }
        runCurrent()
        advanceTimeBy(4_999)
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertEquals(0, cancellations)
        advanceTimeBy(1)
        waiting.await()
        assertEquals(1, cancellations)
        assertNull(window.openedAt)
        assertFalse(window.expireIfSilent())
        // Idle means waiting for another keyword, with no repeated cancellation tone.
        val idle = async { window.receive(events) }
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(idle.isCompleted)
        events.send("new keyword")
        assertEquals("new keyword", idle.await()?.getOrNull())
        assertEquals(1, cancellations)
    }

    @Test fun speechStartedBeforeDeadlineCanFinishAndBeRecognizedLater() = runTest {
        val window = ActivationCommandWindow { testScheduler.currentTime }
        val events = Channel<String>(12)
        window.open()
        advanceTimeBy(4_999)
        window.speechStarted(testScheduler.currentTime)
        val waiting = async { window.receive(events) }
        advanceTimeBy(8_000)
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertFalse(window.expireIfSilent())
        assertTrue(window.accepts(4_999))
        events.send("complete command")
        assertEquals("complete command", waiting.await()?.getOrNull())
        window.reset()
        assertFalse(window.expireIfSilent())
    }

    @Test fun queuedTimelyStartIsDeliveredBeforeOverdueTimeout() = runTest {
        val window = ActivationCommandWindow { testScheduler.currentTime }
        val events = Channel<Long>(12)
        window.open()
        events.send(4_900)
        advanceTimeBy(7_000)
        val startedAt = window.receive(events)?.getOrNull()!!
        window.speechStarted(startedAt)
        assertEquals(4_900L, startedAt)
        assertFalse(window.expireIfSilent())
        assertTrue(window.accepts(startedAt))
    }

    @Test fun timelyStartDeliveredOnTimerBoundaryIsNotLostDuringReceiverDispatch() = runTest {
        val window = ActivationCommandWindow { testScheduler.currentTime }
        val events = Channel<Long>(12)
        window.open()
        // The capture event is delivered at the deadline, before the waiting consumer resumes.
        val delivery = launch { delay(5_000); events.send(4_999) }
        runCurrent()
        val waiting = async { window.receive(events) }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        delivery.join()
        assertEquals(4_999L, waiting.await()?.getOrNull())
    }

    @Test fun queuedNextStartIsNotLostWhenPreviousSpeechEndsAfterDeadline() = runTest {
        val window = ActivationCommandWindow { testScheduler.currentTime }
        val events = Channel<Long>(12)
        window.open()
        window.speechStarted(1_000)
        events.send(4_800)
        advanceTimeBy(6_000)
        window.speechFinished(1_000)
        window.speechStarted(window.receive(events)?.getOrNull()!!)
        assertFalse(window.expireIfSilent())
        assertTrue(window.accepts(4_800))
    }

    @Test fun emptyOrRejectedSpeechReleasesTheOriginalDeadline() = runTest {
        val window = ActivationCommandWindow { testScheduler.currentTime }
        val events = Channel<String>()
        window.open()
        window.speechStarted(1_000)
        advanceTimeBy(2_000)
        window.speechFinished(1_000)
        val waiting = async { window.receive(events) }
        advanceTimeBy(2_999)
        runCurrent()
        assertFalse(waiting.isCompleted)
        advanceTimeBy(1)
        assertNull(waiting.await())
        assertTrue(window.expireIfSilent())
    }

    @Test fun emptySpeechCompletedAfterDeadlineCanExpireImmediately() = runTest {
        val window = ActivationCommandWindow { testScheduler.currentTime }
        window.open()
        window.speechStarted(4_000)
        advanceTimeBy(7_000)
        assertFalse(window.expireIfSilent())
        window.speechFinished(4_000)
        assertTrue(window.expireIfSilent())
    }

    @Test fun speechAtOrAfterDeadlineAndBeforeFeedbackCannotExtendWindow() = runTest {
        val window = ActivationCommandWindow { testScheduler.currentTime }
        advanceTimeBy(2_000) // Activation feedback has just finished.
        window.open()
        assertFalse(window.accepts(1_999))
        assertTrue(window.accepts(2_000))
        assertTrue(window.accepts(6_999))
        assertFalse(window.accepts(7_000))
        window.speechStarted(7_000)
        advanceTimeBy(5_000)
        assertTrue(window.expireIfSilent())
    }

    @Test fun oldSpeechEndCannotClearAnotherTimelyUtterance() = runTest {
        val window = ActivationCommandWindow { testScheduler.currentTime }
        window.open()
        window.speechStarted(1_000)
        window.speechStarted(4_000)
        window.speechFinished(1_000)
        advanceTimeBy(6_000)
        assertFalse(window.expireIfSilent())
        window.speechFinished(4_000)
        assertTrue(window.expireIfSilent())
    }

    @Test fun newActivationGetsItsOwnFiveSecondsAndRejectsPreviousCapture() = runTest {
        val window = ActivationCommandWindow { testScheduler.currentTime }
        window.open()
        advanceTimeBy(5_000)
        assertTrue(window.expireIfSilent())
        advanceTimeBy(220) // Cancellation signal, then a new activation is accepted.
        window.open()
        assertFalse(window.accepts(4_000))
        assertTrue(window.accepts(5_220))
        advanceTimeBy(4_999)
        assertFalse(window.expireIfSilent())
        advanceTimeBy(1)
        assertTrue(window.expireIfSilent())
    }

    @Test fun successfulCommandOrPolicyResetDoesNotProduceCancellation() = runTest {
        val window = ActivationCommandWindow { testScheduler.currentTime }
        window.open()
        window.speechStarted(1_000)
        window.reset()
        advanceTimeBy(8_000)
        assertFalse(window.expireIfSilent())
        assertFalse(window.accepts(1_000))
    }

    @Test fun stoppingCaptureCancelsTheWaitWithoutTimeoutFeedback() = runTest {
        val window = ActivationCommandWindow { testScheduler.currentTime }
        val events = Channel<String>()
        var cancellations = 0
        window.open()
        val waiting = async {
            if (window.receive(events) == null && window.expireIfSilent()) cancellations++
        }
        runCurrent()
        advanceTimeBy(4_000)
        waiting.cancelAndJoin()
        advanceTimeBy(10_000)
        assertEquals(0, cancellations)
    }

    @Test fun closedCaptureIsDistinctFromSilenceTimeout() = runTest {
        val window = ActivationCommandWindow { testScheduler.currentTime }
        val events = Channel<String>()
        window.open()
        events.close()
        val result = window.receive(events)
        assertNotNull(result)
        assertTrue(result!!.isClosed)
        assertEquals(0L, testScheduler.currentTime)
    }
}
