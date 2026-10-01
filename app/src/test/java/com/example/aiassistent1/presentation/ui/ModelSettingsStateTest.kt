package com.example.aiassistent1.presentation.ui

import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSettingsStateTest {
    @Test
    fun `continuous dragging saves the latest pair each second without restarting the timer`() = runTest {
        val state = ModelSettingsState(ModelProfile.CHAT.defaults)
        val writes = mutableListOf<GenerationParams>()
        val timer = backgroundScope.launch { state.saveWhileActive { writes += it } }
        runCurrent()
        state.update { it.withContextSize(4096) }
        assertEquals(2048, state.params.maxTokens)
        advanceTimeBy(700)
        state.update { it.withMaxTokens(3072) }
        assertEquals(6144, state.params.contextSize)
        advanceTimeBy(299)
        runCurrent()
        assertTrue(writes.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(state.params), writes)

        state.update { it.copy(temperature = 0.9f) }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, writes.size)
        assertEquals(state.params, writes.last())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, writes.size)
        timer.cancelAndJoin()
        assertEquals(2, writes.size)
    }

    @Test
    fun `stopping before the tick flushes once and reopening starts a new interval`() = runTest {
        val state = ModelSettingsState(ModelProfile.CHAT.defaults)
        val writes = mutableListOf<GenerationParams>()
        val timer = backgroundScope.launch { state.saveWhileActive { writes += it } }
        runCurrent()
        state.update { it.withMaxTokens(4096) }
        advanceTimeBy(200)
        timer.cancelAndJoin()
        state.flush { writes += it } // Dialog disposal after timer cancellation.
        assertEquals(listOf(state.params), writes)
        state.acceptPersisted(writes.single())

        state.update { it.withContextSize(1024) }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, writes.size)
        val reopened = backgroundScope.launch { state.saveWhileActive { writes += it } }
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, writes.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, writes.size)
        assertEquals(512, writes.last().maxTokens)
        reopened.cancelAndJoin()
    }

    @Test
    fun `unchanged settings and changes reverted before the tick do not write`() = runTest {
        val state = ModelSettingsState(ModelProfile.CHAT.defaults)
        val writes = mutableListOf<GenerationParams>()
        val timer = backgroundScope.launch { state.saveWhileActive { writes += it } }
        runCurrent()
        advanceTimeBy(2_000)
        state.update { it.withContextSize(8192) }
        state.update { it.withContextSize(2048) }
        advanceTimeBy(2_000)
        runCurrent()
        timer.cancelAndJoin()
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `delayed saved values never replace newer edits or a pending submission`() {
        val initial = ModelProfile.CHAT.defaults
        val state = ModelSettingsState(initial)
        val writes = mutableListOf<GenerationParams>()
        state.update { it.withContextSize(4096) }
        state.flush { writes += it }
        state.update { it.withMaxTokens(3072) }
        state.acceptPersisted(initial)
        state.acceptPersisted(writes.single())
        assertEquals(6144, state.params.contextSize)
        state.flush { writes += it }
        state.acceptPersisted(writes.first())
        assertEquals(writes.last(), state.params)
        state.acceptPersisted(writes.last())
        state.flush { writes += it }
        assertEquals(2, writes.size)

        val external = ModelProfile.CHAT.defaults.withContextSize(1024)
        state.acceptPersisted(external)
        assertEquals(external, state.params)
    }

    @Test
    fun `returning to persisted values still saves when an older edit is in flight`() {
        val initial = ModelProfile.CHAT.defaults
        val state = ModelSettingsState(initial)
        val writes = mutableListOf<GenerationParams>()
        state.update { it.withContextSize(8192) }
        state.flush { writes += it }
        state.update { initial }
        state.flush { writes += it }
        assertEquals(2, writes.size)
        assertEquals(initial, writes.last())
    }

    @Test
    fun `disposal flush does not depend on the periodic coroutine having started`() {
        val state = ModelSettingsState(ModelProfile.CHAT.defaults)
        val writes = mutableListOf<GenerationParams>()
        state.update { it.withMaxTokens(256) }
        state.flush { writes += it }
        state.flush { writes += it }
        assertEquals(listOf(ModelProfile.CHAT.defaults.copy(contextSize = 512, maxTokens = 256)), writes)
    }
}
