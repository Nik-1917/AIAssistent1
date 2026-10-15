package com.example.aiassistent1.data.provider

/** Stable frame-to-monotonic mapping, refreshed after an underrun or route change. */
internal class PlaybackSampleClock {
    private var anchorNs: Long? = null
    private var previousPosition: Long? = null
    private var previousTime: Long? = null

    fun observe(position: Long, timeNs: Long): Boolean {
        val previous = previousPosition
        val previousNs = previousTime
        previousPosition = position
        previousTime = timeNs
        if (previous == null || previousNs == null || position <= previous || timeNs <= previousNs) return false
        val expected = (position - previous) * PlaybackEchoReference.NANOS_PER_SAMPLE
        val actual = timeNs - previousNs
        if (kotlin.math.abs(actual - expected) > 20_000_000L) return false
        anchorNs = timeNs - position * PlaybackEchoReference.NANOS_PER_SAMPLE
        return true
    }

    fun reset() { anchorNs = null; previousPosition = null; previousTime = null }

    fun timeNs(position: Long, playedPosition: Long, nowNs: Long): Long = anchorNs?.let {
        it + position * PlaybackEchoReference.NANOS_PER_SAMPLE
    } ?: (nowNs + (position - playedPosition).coerceAtLeast(0) * PlaybackEchoReference.NANOS_PER_SAMPLE + 30_000_000L)
}
