package com.example.aiassistent1.data.provider

/** Stable frame-to-monotonic mapping, refreshed after an underrun or route change. */
internal class PlaybackSampleClock {
    private var anchorNs: Long? = null
    private var previousPosition: Long? = null
    private var previousTime: Long? = null
    private var previousHead: Long? = null
    private var headAdvances = 0
    val isStable: Boolean get() = anchorNs != null
    val hasAdvancingHead: Boolean get() = headAdvances >= 2

    /** A route without timestamps still has to demonstrate real output progress. */
    fun observeHead(position: Long) {
        val previous = previousHead
        if (previous != null) {
            if (position < previous) headAdvances = 0
            else if (position > previous) headAdvances++
        }
        previousHead = position
    }

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

    fun reset() {
        anchorNs = null; previousPosition = null; previousTime = null
        previousHead = null; headAdvances = 0
    }

    fun timestampTimeNs(position: Long): Long? = anchorNs?.let {
        it + position * PlaybackEchoReference.NANOS_PER_SAMPLE
    }

    fun timeNs(position: Long, playedPosition: Long, nowNs: Long): Long = timestampTimeNs(position)
        ?: (nowNs + (position - playedPosition).coerceAtLeast(0) * PlaybackEchoReference.NANOS_PER_SAMPLE + 30_000_000L)
}
