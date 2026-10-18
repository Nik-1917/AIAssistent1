package com.example.aiassistent1.data.provider

/** Maps VAD positions to the timestamps of the processed blocks it actually received. */
internal class VoiceCaptureTimeline(private val capacitySamples: Long = 16_000L * 64) {
    data class Range(val startNs: Long, val endNs: Long)
    private data class Block(val firstSample: Long, val count: Int, val timeNs: Long) {
        val endSample: Long get() = firstSample + count
    }
    private val blocks = ArrayDeque<Block>()
    private var nextSample = 0L

    fun append(count: Int, timeNs: Long) {
        require(count > 0)
        blocks.addLast(Block(nextSample, count, timeNs))
        nextSample += count
        while (blocks.size > 1 && blocks.first().endSample <= nextSample - capacitySamples) blocks.removeFirst()
    }

    fun range(firstSample: Long, count: Int): Range? {
        if (firstSample < 0 || count <= 0 || blocks.isEmpty() || firstSample < blocks.first().firstSample ||
            firstSample + count > nextSample) return null
        val endSample = firstSample + count
        var startNs = Long.MAX_VALUE
        var endNs = Long.MIN_VALUE
        for (block in blocks) {
            if (block.endSample <= firstSample) continue
            if (block.firstSample >= endSample) break
            val first = maxOf(firstSample, block.firstSample)
            val end = minOf(endSample, block.endSample)
            startNs = minOf(startNs, block.timeNs + (first - block.firstSample) * PlaybackEchoReference.NANOS_PER_SAMPLE)
            endNs = maxOf(endNs, block.timeNs + (end - block.firstSample) * PlaybackEchoReference.NANOS_PER_SAMPLE)
        }
        return if (startNs == Long.MAX_VALUE) null else Range(startNs, endNs)
    }

    fun reset() { blocks.clear(); nextSample = 0L }
}
