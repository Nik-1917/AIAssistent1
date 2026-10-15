package com.example.aiassistent1.data.provider

/** A bounded render mix. Local TTS and playback capture have independent clocks.
 * Samples belong to their estimated speaker time, rather than the time a writer queued them.
 */
internal class PlaybackEchoReference(private val capacity: Int = SAMPLE_RATE * 3) {
    enum class Source { ASSISTANT, OTHER_APPS }
    private class Lane(capacity: Int) {
        val samples = FloatArray(capacity)
        val positions = LongArray(capacity) { Long.MIN_VALUE }
    }
    private val lanes = Source.entries.associateWith { Lane(capacity) }

    @Synchronized fun write(source: Source, samples: FloatArray, firstSampleTimeNs: Long) {
        val lane = lanes.getValue(source)
        val first = Math.floorDiv(firstSampleTimeNs, NANOS_PER_SAMPLE)
        for (i in (samples.size - capacity).coerceAtLeast(0) until samples.size) {
            val position = first + i
            val index = Math.floorMod(position, capacity.toLong()).toInt()
            lane.samples[index] = samples[i].takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
            lane.positions[index] = position
        }
    }

    @Synchronized fun read(firstSampleTimeNs: Long, destination: FloatArray) {
        val first = Math.floorDiv(firstSampleTimeNs, NANOS_PER_SAMPLE)
        for (i in destination.indices) {
            val position = first + i
            val index = Math.floorMod(position, capacity.toLong()).toInt()
            var mixed = 0f
            for (lane in lanes.values) if (lane.positions[index] == position) mixed += lane.samples[index]
            destination[i] = mixed.coerceIn(-1f, 1f)
        }
    }

    /** Flushed AudioTrack data must not masquerade as sound still coming out of the speaker. */
    @Synchronized fun discardFrom(source: Source, timeNs: Long) {
        val first = Math.floorDiv(timeNs, NANOS_PER_SAMPLE)
        val lane = lanes.getValue(source)
        for (i in lane.positions.indices) if (lane.positions[i] >= first) lane.positions[i] = Long.MIN_VALUE
    }

    @Synchronized fun clear(source: Source) { lanes.getValue(source).positions.fill(Long.MIN_VALUE) }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val NANOS_PER_SAMPLE = 1_000_000_000L / SAMPLE_RATE
    }
}
