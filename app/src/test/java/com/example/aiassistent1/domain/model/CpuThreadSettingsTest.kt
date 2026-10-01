package com.example.aiassistent1.domain.model

import org.junit.Assert.*
import org.junit.Test

class CpuThreadSettingsTest {
    @Test
    fun `automatic selection preserves the existing algorithm and handles unavailable counts`() {
        val cases = listOf(
            Int.MIN_VALUE to 1, 0 to 1, 1 to 1, 2 to 2, 4 to 4,
            5 to 4, 8 to 4, 9 to 6, 12 to 6, Int.MAX_VALUE to 6,
        )
        for ((processors, expected) in cases) {
            assertEquals(expected, CpuThreadSettings.automaticThreadCount(processors))
            assertEquals(expected, GenerationParams(cpuThreadsAuto = true, cpuThreads = Int.MAX_VALUE)
                .effectiveCpuThreads(processors))
        }
    }

    @Test
    fun `manual values are bounded and survive an automatic mode round trip`() {
        val manual = GenerationParams(cpuThreadsAuto = false, cpuThreads = 3)
        assertEquals(3, manual.effectiveCpuThreads(8))
        val automatic = manual.copy(cpuThreadsAuto = true)
        assertEquals(4, automatic.effectiveCpuThreads(8))
        assertEquals(3, automatic.cpuThreads)
        assertEquals(3, automatic.copy(cpuThreadsAuto = false).effectiveCpuThreads(8))
        assertEquals(2, manual.effectiveCpuThreads(2))
        assertEquals(1, manual.effectiveCpuThreads(0))
        assertEquals(1, manual.copy(cpuThreads = Int.MIN_VALUE).effectiveCpuThreads(8))
        assertEquals(8, manual.copy(cpuThreads = Int.MAX_VALUE).effectiveCpuThreads(8))
    }

    @Test
    fun `profile defaults and saved values agree with the device slider range`() {
        val available = CpuThreadSettings.availableProcessors
        assertTrue(available >= 1)
        for (profile in ModelProfile.entries) {
            assertTrue(profile.defaults.cpuThreadsAuto)
            assertEquals(CpuThreadSettings.automaticThreadCount(), profile.defaults.cpuThreads)
            assertEquals(profile.defaults.cpuThreads, profile.defaults.effectiveCpuThreads())
            assertEquals(profile.defaults, profile.defaults.normalizedForSettings())
            val low = profile.defaults.copy(cpuThreadsAuto = false, cpuThreads = Int.MIN_VALUE).normalizedForSettings()
            assertEquals(1, low.cpuThreads)
            assertEquals(1, low.effectiveCpuThreads())
            val high = profile.defaults.copy(cpuThreadsAuto = false, cpuThreads = Int.MAX_VALUE).normalizedForSettings()
            assertEquals(available, high.cpuThreads)
            assertEquals(available, high.effectiveCpuThreads())
            assertEquals(profile.defaults.copy(cpuThreadsAuto = false, cpuThreads = available), high)
        }
    }
}
