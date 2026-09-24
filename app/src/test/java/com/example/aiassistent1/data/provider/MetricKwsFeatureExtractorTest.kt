package com.example.aiassistent1.data.provider

import org.junit.Assert.*
import org.junit.Test

class MetricKwsFeatureExtractorTest {
    private val extractor = MetricKwsFeatureExtractor()

    @Test fun rejectsUnsupportedSampleRatesAndDurations() {
        for (rate in listOf(0, 8000, 44100, 48000)) {
            assertThrows(IllegalArgumentException::class.java) { extractor.extract(FloatArray(16000), rate) }
        }
        for (size in listOf(0, 7999, 128001)) {
            assertThrows(IllegalArgumentException::class.java) { extractor.extract(FloatArray(size)) }
        }
    }

    @Test fun rejectsNonfiniteAndUnnormalizedPcm() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.001f, -1.001f)) {
            val samples = FloatArray(16000).apply { this[lastIndex] = value }
            assertThrows(IllegalArgumentException::class.java) { extractor.extract(samples) }
        }
    }

    @Test fun outputsDoNotShareBuffersOrRetainPreviousUtterance() {
        val silence = extractor.extract(FloatArray(16000))
        val saved = silence.copyOf()
        val signal = FloatArray(16000) { if (it % 19 == 0) .5f else 0f }
        extractor.extract(signal).fill(Float.NaN)
        assertArrayEquals(saved, silence, 0f)
        assertArrayEquals(saved, extractor.extract(FloatArray(16000)), 0f)
    }
}
