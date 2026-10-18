package com.example.aiassistent1.data.provider

import android.media.AudioRecord
import android.media.AudioTimestamp

/** Both microphone and loopback positions are mapped onto the same monotonic clock. */
internal class RecordingSampleClock {
    private val timestamp = AudioTimestamp()
    private var readPosition = 0L
    private var anchorNs: Long? = null
    private val clock = PlaybackSampleClock()
    private var firstReadNs: Long? = null
    private var latestReadNs = 0L
    private var reads = 0
    // Some routes never expose timestamps. Confirm a running capture before using its read clock.
    val isReady: Boolean get() = clock.isStable ||
        (reads >= 2 && latestReadNs - (firstReadNs ?: latestReadNs) >= 500_000_000L)
    val timing: String get() = if (clock.isStable) "timestamp" else "read_clock"

    fun firstSampleTimeNs(recorder: AudioRecord, sampleCount: Int): Long {
        val position = readPosition
        readPosition += sampleCount
        latestReadNs = System.nanoTime()
        if (firstReadNs == null) firstReadNs = latestReadNs
        reads++
        if (recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
            clock.observe(timestamp.framePosition, timestamp.nanoTime)
        }
        clock.timestampTimeNs(position)?.let { return it }
        val anchor = anchorNs ?: (latestReadNs - readPosition * PlaybackEchoReference.NANOS_PER_SAMPLE)
            .also { anchorNs = it }
        return anchor + position * PlaybackEchoReference.NANOS_PER_SAMPLE
    }
}
