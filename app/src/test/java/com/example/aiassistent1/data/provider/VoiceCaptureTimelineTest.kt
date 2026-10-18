package com.example.aiassistent1.data.provider

import org.junit.Assert.*
import org.junit.Test

class VoiceCaptureTimelineTest {
    private val second = 1_000_000_000L
    private val sample = PlaybackEchoReference.NANOS_PER_SAMPLE

    @Test fun hardwareTimestampCorrectionDoesNotKeepTheInitialFallbackOrigin() {
        val timeline = VoiceCaptureTimeline()
        timeline.append(160, second)
        timeline.append(160, 2 * second)
        assertEquals(VoiceCaptureTimeline.Range(2 * second, 2 * second + 160 * sample), timeline.range(160, 160))
    }

    @Test fun utteranceWithPreRollSpansTheActualTimestampsOfBothProcessedBlocks() {
        val timeline = VoiceCaptureTimeline()
        timeline.append(160, second)
        timeline.append(160, second + 160 * sample)
        assertEquals(VoiceCaptureTimeline.Range(second + 80 * sample, second + 240 * sample), timeline.range(80, 160))
    }

    @Test fun backwardClockAdjustmentStillProducesAnOrderedCaptureRange() {
        val timeline = VoiceCaptureTimeline()
        timeline.append(160, 2 * second)
        timeline.append(160, second)
        assertEquals(VoiceCaptureTimeline.Range(second, 2 * second + 160 * sample), timeline.range(0, 320))
    }

    @Test fun resetAfterActivationFeedbackCannotAttachAnEarlierPlaybackReference() {
        val timeline = VoiceCaptureTimeline()
        timeline.append(160, second)
        timeline.reset()
        assertNull(timeline.range(0, 160))
        timeline.append(160, 5 * second)
        assertEquals(VoiceCaptureTimeline.Range(5 * second, 5 * second + 160 * sample), timeline.range(0, 160))
    }

    @Test fun prunedOrNotYetCapturedAudioCannotBeAssignedAnInventedTime() {
        val timeline = VoiceCaptureTimeline(capacitySamples = 320)
        repeat(5) { timeline.append(160, second + it * 160 * sample) }
        assertNull(timeline.range(0, 160))
        assertNull(timeline.range(800, 1))
        assertNull(timeline.range(-1, 160))
        assertNull(timeline.range(640, 0))
        assertEquals(VoiceCaptureTimeline.Range(second + 640 * sample, second + 800 * sample), timeline.range(640, 160))
    }

    @Test fun correctedCaptureRangeRetainsTheNarratorReferenceThatWasActuallyHeard() {
        val timeline = VoiceCaptureTimeline()
        timeline.append(160, second)
        timeline.append(16_000, 3 * second)
        val guard = AssistantSpeechEchoGuard(clock = { 5 * second })
        val id = guard.prepare("Солнечная система", FloatArray(16_000))
        guard.queued(id, 3 * second, 16_000)
        val range = checkNotNull(timeline.range(160, 16_000))
        assertTrue(guard.snapshot(range.startNs, range.endNs).overlapsNarrator)
        // The old fixed origin would assign this utterance to the silent interval before TTS.
        assertFalse(guard.snapshot(second + 160 * sample, 2 * second + 160 * sample).overlapsNarrator)
    }
}
