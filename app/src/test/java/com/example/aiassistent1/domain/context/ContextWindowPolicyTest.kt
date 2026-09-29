package com.example.aiassistent1.domain.context

import com.example.aiassistent1.domain.model.GenerationParams
import org.junit.Assert.*
import org.junit.Test

class ContextWindowPolicyTest {
    private val settings = GenerationParams(contextSize = 1024, maxContextSize = 8192)

    @Test fun `small message uses exact full prompt plus answer and 32 only once`() {
        val budget = ContextWindowPolicy.plan(90, settings, 32768, userMessageTokens = 30)
        assertEquals(634, budget.requiredTokens)
        assertEquals(1024, budget.contextSize)
        assertEquals(32, budget.reserveTokens)
        assertEquals(30, budget.userMessageTokens)
        assertEquals(90, budget.promptTokens)
    }

    @Test fun `322 is inclusive and 323 keeps the normal reserve`() {
        val small = ContextWindowPolicy.plan(450, settings, 32768, userMessageTokens = 322)
        val regular = ContextWindowPolicy.plan(450, settings, 32768, userMessageTokens = 323)
        assertEquals(1024, small.contextSize)
        assertEquals(32, small.reserveTokens)
        assertEquals(2048, regular.contextSize)
        assertEquals(128, regular.reserveTokens)
    }

    @Test fun `small message does not bypass full history or any step boundary`() {
        for ((prompt, size) in listOf(480 to 1024, 992 to 2048, 2016 to 4096, 4064 to 8192)) {
            val budget = ContextWindowPolicy.plan(prompt, settings, 32768, userMessageTokens = 30)
            assertEquals(size, budget.requiredTokens)
            assertEquals(size, budget.contextSize)
            assertEquals(size / 2, budget.responseTokens)
            if (size < 8192) {
                assertEquals(size * 2, ContextWindowPolicy.plan(prompt + 1, settings, 32768, 30).contextSize)
            } else assertThrows(ContextCapacityException::class.java) {
                ContextWindowPolicy.plan(prompt + 1, settings, 32768, 30)
            }
        }
    }

    @Test fun `unknown message size stays conservative and invalid size is rejected`() {
        assertEquals(128, ContextWindowPolicy.plan(100, settings, 32768).reserveTokens)
        assertThrows(IllegalArgumentException::class.java) {
            ContextWindowPolicy.plan(100, settings, 32768, userMessageTokens = -1)
        }
    }

    @Test fun `minimum includes a full answer and safety reserve`() {
        val budget = ContextWindowPolicy.plan(384, settings, 32768)
        assertEquals(1024, budget.contextSize)
        assertEquals(1024, budget.requiredTokens)
        assertEquals(512, budget.responseTokens)
        assertEquals(128, budget.reserveTokens)
        assertEquals(2048, ContextWindowPolicy.plan(385, settings, 32768).contextSize)
    }

    @Test fun `every step reserves half for the answer and grows one token past its boundary`() {
        val boundaries = listOf(384 to 1024, 896 to 2048, 1920 to 4096, 3968 to 8192)
        for ((prompt, size) in boundaries) {
            val budget = ContextWindowPolicy.plan(prompt, settings, 32768)
            assertEquals(size, budget.contextSize)
            assertEquals(size / 2, budget.responseTokens)
            assertEquals(size, budget.requiredTokens)
            if (size < 8192) {
                val next = ContextWindowPolicy.plan(prompt + 1, settings, 32768)
                assertEquals(size * 2, next.contextSize)
                assertEquals(size, next.responseTokens)
            } else assertThrows(ContextCapacityException::class.java) {
                ContextWindowPolicy.plan(prompt + 1, settings, 32768)
            }
        }
        assertEquals(2048, ContextWindowPolicy.plan(512, settings, 32768).contextSize)
    }

    @Test fun `working context grows and returns to persisted minimum`() {
        assertEquals(1024, ContextWindowPolicy.plan(200, settings, 32768).contextSize)
        assertEquals(8192, ContextWindowPolicy.plan(3200, settings, 32768).contextSize)
        assertEquals(1024, ContextWindowPolicy.plan(200, settings, 32768).contextSize)
        assertEquals(1024, settings.contextSize)
    }

    @Test fun `manual context and model capability both cap allocation`() {
        assertThrows(ContextCapacityException::class.java) {
            ContextWindowPolicy.plan(3200, settings.copy(autoContextEnabled = false), 32768)
        }
        assertThrows(ContextCapacityException::class.java) {
            ContextWindowPolicy.plan(3200, settings, 2048)
        }
        assertThrows(ContextCapacityException::class.java) {
            ContextWindowPolicy.plan(10, settings.copy(contextSize = 2048), 1024)
        }
    }

    @Test fun `model limit between steps never permits the larger step`() {
        assertEquals(4096, ContextWindowPolicy.plan(1920, settings, 6144).contextSize)
        val failure = assertThrows(ContextCapacityException::class.java) {
            ContextWindowPolicy.plan(1921, settings, 6144)
        }
        assertEquals(4096, failure.limit)
    }

    @Test fun `internal summary keeps its explicit output limit`() {
        val summary = settings.copy(contextSize = 4096, fixedResponseTokens = 512)
        val budget = ContextWindowPolicy.plan(3200, summary, 32768)
        assertEquals(4096, budget.contextSize)
        assertEquals(512, budget.responseTokens)
        assertEquals(512, summary.copy(contextSize = 8192).maxTokens)
    }

    @Test fun `requests above cap are rejected without reducing answer budget`() {
        val failure = assertThrows(ContextCapacityException::class.java) {
            ContextWindowPolicy.plan(8000, settings, 32768)
        }
        assertEquals(12224L, failure.required)
        assertEquals(8192, failure.limit)
        assertEquals(512, settings.maxTokens)
    }

    @Test fun `huge token count cannot overflow into a small accepted request`() {
        val error = assertThrows(ContextCapacityException::class.java) {
            ContextWindowPolicy.plan(Int.MAX_VALUE, settings, Int.MAX_VALUE)
        }
        assertTrue(error.required > Int.MAX_VALUE)
    }

    @Test fun `bad saved values normalize to bounded coherent settings`() {
        val normalized = settings.copy(contextSize = 992, maxContextSize = -1, maxMessageLength = Int.MAX_VALUE).normalized()
        assertEquals(1024, normalized.contextSize)
        assertEquals(1024, normalized.maxContextSize)
        assertEquals(12000, normalized.maxMessageLength)
        assertEquals(512, normalized.maxTokens)
        assertEquals(4096, settings.copy(contextSize = 8192).normalized().maxTokens)
        assertEquals(4096, settings.copy(contextSize = 3072).normalized().contextSize)
    }
}
