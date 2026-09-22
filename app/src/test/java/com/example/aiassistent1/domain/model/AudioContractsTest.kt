package com.example.aiassistent1.domain.model

import org.junit.Assert.*
import org.junit.Test

class AudioContractsTest {
    @Test fun speakerVerificationActuallyUsesBothEmbeddings() {
        val a = FloatArray(32) { if (it == 0) 1f else 0f }
        val b = FloatArray(32) { if (it == 1) 1f else 0f }
        assertTrue(VoiceEmbedding.matches(a, a.copyOf(), .5f))
        assertFalse(VoiceEmbedding.matches(a, b, .5f))
        assertFalse(VoiceEmbedding.matches(a, FloatArray(31), .5f))
    }
    @Test fun invalidProfilesAreAlwaysRejected() {
        val valid = FloatArray(32) { 1f }
        for (invalid in listOf(FloatArray(0), FloatArray(32), FloatArray(32) { Float.NaN },
            FloatArray(32) { Float.POSITIVE_INFINITY })) {
            assertFalse(VoiceEmbedding.matches(valid, invalid, .5f))
        }
    }
    @Test fun activationRequiresWholeNameAtStart() {
        assertEquals("создай встречу", WakeWordTokens.removePrefix("Ассистент, создай встречу", "Ассистент"))
        assertEquals("", WakeWordTokens.removePrefix(" ассистент!", "Ассистент"))
        assertNull(WakeWordTokens.removePrefix("Ассистентский режим", "Ассистент"))
        assertNull(WakeWordTokens.removePrefix("Я сказал ассистент", "Ассистент"))
    }
    @Test fun kwsDoesNotUseRawNameAsTokenList() {
        assertEquals("▁ASSIST ANT", WakeWordTokens.encode("assistant", listOf("▁ASSIST", "ANT")))
        assertTrue(runCatching { WakeWordTokens.encode("Ассистент", listOf("A", "B")) }.isFailure)
    }
    @Test fun textLimitCountsUtf8Bytes() {
        assertEquals(12L, TranscriptLimits.bytes("Привет"))
        assertEquals(4L, TranscriptLimits.bytes("😀"))
        assertEquals(10_800_000L, TranscriptLimits.MAX_DURATION_MS)
    }
}
