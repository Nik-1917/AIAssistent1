package com.example.aiassistent1.data.provider

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class MicrophoneCoordinatorTest {
    @Test fun waitingSessionAcquiresAfterPreviousSessionReleases() = runTest {
        val first = Any()
        val second = Any()
        try {
            MicrophoneCoordinator.acquire(first)
            val next = async { MicrophoneCoordinator.awaitAcquire(second) }
            delay(50)
            assertFalse(next.isCompleted)
            MicrophoneCoordinator.release(first)
            next.await()
            assertTrue(runCatching { MicrophoneCoordinator.acquire(first) }.isFailure)
        } finally {
            MicrophoneCoordinator.release(first)
            MicrophoneCoordinator.release(second)
        }
    }

    @Test fun cancellingWaiterDoesNotReleaseActiveSession() = runTest {
        val owner = Any()
        val waiter = Any()
        try {
            MicrophoneCoordinator.acquire(owner)
            val pending = launch { MicrophoneCoordinator.awaitAcquire(waiter) }
            delay(50)
            pending.cancelAndJoin()
            assertTrue(runCatching { MicrophoneCoordinator.acquire(waiter) }.isFailure)
            MicrophoneCoordinator.release(owner)
            MicrophoneCoordinator.awaitAcquire(waiter)
        } finally {
            MicrophoneCoordinator.release(owner)
            MicrophoneCoordinator.release(waiter)
        }
    }

    @Test fun busyTimeoutIsReportedWithoutCancellingCaller() = runTest {
        val owner = Any()
        val waiter = Any()
        try {
            MicrophoneCoordinator.acquire(owner)
            val error = runCatching { MicrophoneCoordinator.awaitAcquire(waiter) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(error?.message.orEmpty().contains("Микрофон занят"))
            assertTrue(runCatching { MicrophoneCoordinator.acquire(waiter) }.isFailure)
            MicrophoneCoordinator.release(owner)
            MicrophoneCoordinator.awaitAcquire(waiter)
        } finally {
            MicrophoneCoordinator.release(owner)
            MicrophoneCoordinator.release(waiter)
        }
    }

    @Test fun callerTimeoutRemainsCancellation() = runTest {
        val owner = Any()
        val waiter = Any()
        try {
            MicrophoneCoordinator.acquire(owner)
            val result = withTimeoutOrNull(100) {
                MicrophoneCoordinator.awaitAcquire(waiter)
                true
            }
            assertNull(result)
            MicrophoneCoordinator.release(owner)
            MicrophoneCoordinator.awaitAcquire(waiter)
        } finally {
            MicrophoneCoordinator.release(owner)
            MicrophoneCoordinator.release(waiter)
        }
    }
}
