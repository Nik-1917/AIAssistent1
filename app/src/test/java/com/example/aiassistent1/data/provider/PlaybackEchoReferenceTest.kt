package com.example.aiassistent1.data.provider

import com.example.aiassistent1.data.provider.PlaybackEchoReference.Source.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackEchoReferenceTest {
    private val step = PlaybackEchoReference.NANOS_PER_SAMPLE
    @Test fun independentlyTimedSourcesAreMixedAtSpeakerTimeRatherThanConcatenated() {
        val reference = PlaybackEchoReference(16)
        reference.write(ASSISTANT, floatArrayOf(.1f, .2f, .3f), 100 * step)
        reference.write(OTHER_APPS, floatArrayOf(.4f, .5f, .6f), 101 * step)
        val frame = FloatArray(5)
        reference.read(99 * step, frame)
        assertArrayEquals(floatArrayOf(0f, .1f, .6f, .8f, .6f), frame, .00001f)
    }
    @Test fun flushingSpeakerRemovesFutureReferenceAndRetainsThePlayedEchoTail() {
        val reference = PlaybackEchoReference(16)
        reference.write(ASSISTANT, FloatArray(8) { .1f }, 100 * step)
        reference.write(OTHER_APPS, FloatArray(8) { .2f }, 100 * step)
        reference.discardFrom(ASSISTANT, 104 * step)
        val frame = FloatArray(8)
        reference.read(100 * step, frame)
        assertArrayEquals(FloatArray(8) { if (it < 4) .3f else .2f }, frame, .00001f)
        reference.clear(OTHER_APPS)
        reference.read(104 * step, frame)
        assertTrue(frame.all { it == 0f })
    }
    @Test fun silenceAndRingWrapCannotReplayOldReference() {
        val reference = PlaybackEchoReference(4)
        reference.write(ASSISTANT, floatArrayOf(.1f, .2f, .3f, .4f), 100 * step)
        reference.write(ASSISTANT, floatArrayOf(.5f), 104 * step)
        val frame = FloatArray(5)
        reference.read(100 * step, frame)
        assertArrayEquals(floatArrayOf(0f, .2f, .3f, .4f, .5f), frame, .00001f)
        reference.read(108 * step, frame)
        assertTrue(frame.all { it == 0f })
    }
}
