package com.example.aiassistent1.presentation.ui

import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelProfile
import org.junit.Assert.*
import org.junit.Test

class ModelContextSettingsStateTest {
    @Test fun `metadata arriving after dialog creation updates both profile ranges`() {
        for (profile in ModelProfile.entries) {
            val state = ModelSettingsState(profile.defaults)
            state.acceptPersisted(profile.defaults.copy(trainedContextLength = 32768))
            assertEquals(32768, state.params.contextLimits.maximum)
            assertEquals(profile.defaults.contextSize, state.params.contextSize)
            state.update { it.withMaxTokens(16384) }
            assertEquals(32768, state.params.contextSize)
            assertEquals(16384, state.params.maxTokens)
        }
    }

    @Test fun `smaller new ceiling bounds pending save and newer local changes`() {
        val initial = ModelProfile.CHAT.defaults.copy(trainedContextLength = 32768)
        val state = ModelSettingsState(initial)
        val writes = mutableListOf<GenerationParams>()
        state.update { it.withContextSize(16384) }
        state.flush { writes += it }
        state.update { it.withContextSize(32768).copy(temperature = 0.9f) }
        state.acceptPersisted(writes.single().copy(trainedContextLength = 4096).normalizedForSettings())
        assertEquals(4096, state.params.contextSize)
        assertEquals(2048, state.params.maxTokens)
        assertEquals(0.9f, state.params.temperature)
        state.flush { writes += it }
        assertEquals(2, writes.size)
        state.acceptPersisted(writes.last())
        state.flush { writes += it }
        assertEquals(2, writes.size)
    }
}
