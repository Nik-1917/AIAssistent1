package com.example.aiassistent1.domain.model

import org.junit.Assert.*
import org.junit.Test

class MetricKwsTest {
    private val config = MetricKwsConfig("test", "a".repeat(64), "test-feature", 2, 0.8f)

    @Test fun normalizationAndCosine() {
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), KeywordEmbedding.normalize(floatArrayOf(3f, 4f)), 0.00001f)
        assertEquals(1f, KeywordEmbedding.cosine(floatArrayOf(3f, 4f), floatArrayOf(6f, 8f))!!, 0.00001f)
        assertEquals(-1f, KeywordEmbedding.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f))!!, 0f)
        assertEquals(0f, KeywordEmbedding.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))!!, 0f)
    }

    @Test fun invalidEmbeddingsFailClosed() {
        for (bad in listOf(floatArrayOf(), floatArrayOf(0f, 0f), floatArrayOf(Float.NaN, 1f),
            floatArrayOf(Float.POSITIVE_INFINITY, 1f), FloatArray(4097) { 1f })) {
            assertFalse(KeywordEmbedding.isValid(bad))
            assertNull(KeywordEmbedding.cosine(bad, bad))
            assertTrue(runCatching { KeywordEmbedding.normalize(bad) }.isFailure)
        }
        assertNull(KeywordEmbedding.cosine(floatArrayOf(1f), floatArrayOf(1f, 1f)))
    }

    @Test fun largeAndSubnormalVectorsDoNotOverflow() {
        for (v in listOf(Float.MAX_VALUE, Float.MIN_VALUE)) {
            val values = floatArrayOf(v, v)
            assertEquals(1f, KeywordEmbedding.cosine(values, values)!!, 0.00001f)
            assertTrue(KeywordEmbedding.normalize(values).all(Float::isFinite))
        }
    }

    @Test fun thresholdBoundaries() {
        assertTrue(KeywordEmbedding.matches(0.8f, 0.8f))
        assertFalse(KeywordEmbedding.matches(0.799f, 0.8f))
        assertFalse(KeywordEmbedding.matches(Float.NaN, 0.8f))
        assertFalse(KeywordEmbedding.matches(1f, Float.NaN))
        assertFalse(KeywordEmbedding.matches(1f, 2f))
        assertFalse(KeywordEmbedding.matches(null, 0.8f))
    }

    @Test fun audioRejectsSilenceClippingInvalidAndUnboundedSegments() {
        val inputs = listOf(FloatArray(0), FloatArray(7999) { .1f }, FloatArray(128001) { .1f },
            FloatArray(16000), FloatArray(16000) { 1f }, FloatArray(16000) { Float.NaN },
            FloatArray(16000) { Float.POSITIVE_INFINITY })
        inputs.forEach { assertTrue(runCatching { MetricKwsAudio.validate(it, config) }.isFailure) }
        MetricKwsAudio.validate(FloatArray(16000) { if (it % 2 == 0) .1f else -.1f }, config)
    }

    @Test fun malformedModelMetadataIsRejected() {
        assertTrue(runCatching { config.copy(modelSha256 = "unknown") }.isFailure)
        assertTrue(runCatching { config.copy(keywordThreshold = Float.NaN) }.isFailure)
        assertTrue(runCatching { config.copy(embeddingSize = Int.MAX_VALUE) }.isFailure)
        assertTrue(runCatching { config.copy(sampleRate = 8000) }.isFailure)
        assertTrue(runCatching { config.copy(minSamples = 100) }.isFailure)
    }
}
