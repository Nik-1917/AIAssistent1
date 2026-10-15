package com.example.aiassistent1.data.provider

import android.media.AudioRecord
import android.media.AudioTimestamp

/** Both microphone and loopback positions are mapped onto the same monotonic clock. */
internal class RecordingSampleClock {
    private val timestamp = AudioTimestamp()
    private var readPosition = 0L
    private var anchorNs: Long? = null

    fun firstSampleTimeNs(recorder: AudioRecord, sampleCount: Int): Long {
        val position = readPosition
        readPosition += sampleCount
        if (recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
            anchorNs = timestamp.nanoTime - timestamp.framePosition * PlaybackEchoReference.NANOS_PER_SAMPLE
        }
        val anchor = anchorNs ?: (System.nanoTime() - readPosition * PlaybackEchoReference.NANOS_PER_SAMPLE)
            .also { anchorNs = it }
        return anchor + position * PlaybackEchoReference.NANOS_PER_SAMPLE
    }
}
