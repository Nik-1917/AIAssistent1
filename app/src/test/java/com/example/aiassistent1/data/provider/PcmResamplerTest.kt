package com.example.aiassistent1.data.provider

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PcmResamplerTest {
    @Test fun durationAndDcLevelArePreserved() {
        val output = PcmResampler.to16k(FloatArray(22_050) { .25f }, 22_050)
        assertEquals(16_000, output.size)
        assertTrue(output.all { abs(it - .25f) < .0001f })
    }
    @Test fun frequenciesAboveNewNyquistAreSuppressed() {
        val input = FloatArray(48_000) { sin(2 * PI * 12_000 * it / 48_000).toFloat() }
        val output = PcmResampler.to16k(input, 48_000)
        val rms = sqrt(output.drop(64).dropLast(64).map { it.toDouble() * it }.average())
        assertTrue("Aliased energy: $rms", rms < .02)
    }
    @Test fun unchangedSampleRateDoesNotAllocateOrChangeSamples() {
        val input = floatArrayOf(0f, .5f, -.5f)
        assertSame(input, PcmResampler.to16k(input, 16_000))
        assertTrue(PcmResampler.to16k(FloatArray(0), 22_050).isEmpty())
    }
}
