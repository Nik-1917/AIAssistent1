package com.example.aiassistent1.data.provider

import org.junit.Assert.*
import org.junit.Test

class PlaybackSampleClockTest {
    @Test fun fallbackRequiresAdvancingHardwareHeadAndResetsBetweenChunks() {
        val clock = PlaybackSampleClock()
        clock.observeHead(0); clock.observeHead(0)
        assertFalse(clock.hasAdvancingHead)
        clock.observeHead(160)
        assertFalse(clock.hasAdvancingHead)
        clock.observeHead(320)
        assertTrue(clock.hasAdvancingHead)
        assertFalse(clock.isStable)
        assertNull(clock.timestampTimeNs(320))
        clock.reset()
        assertFalse(clock.hasAdvancingHead)
        clock.observeHead(320); clock.observeHead(320)
        assertFalse(clock.hasAdvancingHead)
    }

    @Test fun hardwareHeadRewindInvalidatesFallbackProgress() {
        val clock = PlaybackSampleClock()
        clock.observeHead(0); clock.observeHead(160); clock.observeHead(320)
        clock.observeHead(0)
        assertFalse(clock.hasAdvancingHead)
    }
    @Test fun initialAndFrozenTimestampsCannotEstablishTheRenderClock() {
        val clock = PlaybackSampleClock()
        assertFalse(clock.observe(0, 1_000_000_000))
        assertFalse(clock.observe(0, 1_010_000_000))
        assertTrue(clock.observe(160, 1_020_000_000))
        assertEquals(1_030_000_000, clock.timeNs(320, 160, 4_000_000_000))
    }

    @Test fun sentenceUnderrunNeedsANewAdvancingClock() {
        val clock = PlaybackSampleClock()
        clock.observe(160, 1_000_000_000)
        assertTrue(clock.observe(320, 1_010_000_000))
        clock.reset()
        assertFalse(clock.observe(320, 3_000_000_000))
        assertTrue(clock.observe(480, 3_010_000_000))
        assertEquals(3_020_000_000, clock.timeNs(640, 480, 5_000_000_000))
    }

    @Test fun unreliableTimestampDoesNotReplaceLastStableMapping() {
        val clock = PlaybackSampleClock()
        clock.observe(160, 1_000_000_000)
        assertTrue(clock.observe(320, 1_010_000_000))
        assertFalse(clock.observe(480, 4_000_000_000))
        assertEquals(1_030_000_000, clock.timeNs(640, 480, 5_000_000_000))
    }
}
