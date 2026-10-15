package com.example.aiassistent1.data.provider

import org.junit.Assert.*
import org.junit.Test

class VoiceUtterancePreRollTest {
    @Test fun preservesTheQuarterSecondBeforeVadWithoutDuplicatingSpeech() {
        val buffer = VoiceUtterancePreRoll()
        buffer.append(FloatArray(12_000) { it.toFloat() })
        buffer.speechStarted()
        // VAD returns the utterance later, even after the short history has rolled over.
        buffer.append(FloatArray(40_000))
        val result = buffer.complete(10_000, floatArrayOf(10_000f, 10_001f))
        assertEquals(4_002, result.size)
        assertEquals(6_000f, result.first(), 0f)
        assertEquals(9_999f, result[3_999], 0f)
        assertEquals(10_000f, result[4_000], 0f)
    }
    @Test fun resetCannotAttachPreviousSpeechToANewActivationSession() {
        val buffer = VoiceUtterancePreRoll()
        buffer.append(FloatArray(1_000) { 1f }); buffer.speechStarted(); buffer.reset()
        val speech = floatArrayOf(.1f, .2f)
        assertArrayEquals(speech, buffer.complete(0, speech), 0f)
    }
}
