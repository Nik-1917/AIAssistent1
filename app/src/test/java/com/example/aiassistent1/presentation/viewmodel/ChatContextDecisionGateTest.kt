package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatContextDecisionGateTest {
    private fun pressure() = ChatContextPressure("request", emptyList(), ContextCapacityReason.MEMORY)

    @Test fun `cancelled generation clears dialog and a stale click cannot resolve the next request`() = runTest {
        val gate = ChatContextDecisionGate()
        val first = pressure()
        val oldJob = async { gate.await(first) }
        runCurrent()
        assertEquals(first, gate.pressure.value)
        oldJob.cancelAndJoin()
        assertNull(gate.pressure.value)
        val second = pressure()
        val nextJob = async { gate.await(second) }
        runCurrent()
        gate.resolve(first.id, ChatContextChoice.Automatic(true))
        assertFalse(nextJob.isCompleted)
        gate.resolve(second.id, ChatContextChoice.Manual(setOf("old")))
        assertEquals(ChatContextChoice.Manual(setOf("old")), nextJob.await())
        assertNull(gate.pressure.value)
    }

    @Test fun `double click produces a single choice and dismiss is explicit cancellation`() = runTest {
        val gate = ChatContextDecisionGate()
        val state = pressure()
        val waiting = async { gate.await(state) }
        runCurrent()
        gate.resolve(state.id, ChatContextChoice.Cancel)
        gate.resolve(state.id, ChatContextChoice.Automatic())
        assertEquals(ChatContextChoice.Cancel, waiting.await())
        assertNull(gate.pressure.value)
    }
}
