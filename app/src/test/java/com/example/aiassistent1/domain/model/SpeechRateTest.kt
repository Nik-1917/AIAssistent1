package com.example.aiassistent1.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechRateTest {
    @Test
    fun `uses 0_95 as the default speech speed`() {
        assertEquals(0.95f, SpeechRate.DEFAULT)
    }

    @Test
    fun `limits speech speed to the supported range`() {
        assertEquals(0.5f, SpeechRate.normalize(0.1f))
        assertEquals(SpeechRate.MAXIMUM, SpeechRate.normalize(3.0f))
        assertEquals(0.75f, SpeechRate.normalize(0.75f))
    }
}
