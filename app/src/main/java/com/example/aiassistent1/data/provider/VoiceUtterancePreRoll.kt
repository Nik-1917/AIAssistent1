package com.example.aiassistent1.data.provider

/** Keeps only processed microphone audio preceding VAD onset; never duplicates the VAD segment. */
internal class VoiceUtterancePreRoll {
    private val history = FloatArray(9_600)
    private var position = 0L
    private var prefix = FloatArray(0)
    private var prefixStart = 0L

    fun append(samples: FloatArray) {
        for (sample in samples) history[(position++ % history.size).toInt()] = sample
    }

    fun speechStarted() {
        val size = minOf(position, history.size.toLong()).toInt()
        prefixStart = position - size
        prefix = FloatArray(size) { history[((prefixStart + it) % history.size).toInt()] }
    }

    fun complete(startSample: Long, samples: FloatArray): FloatArray {
        val first = maxOf(prefixStart, startSample - 4_000).coerceAtLeast(0)
        val end = minOf(startSample, prefixStart + prefix.size)
        val count = (end - first).coerceAtLeast(0).toInt()
        val result = if (count == 0) samples else FloatArray(count + samples.size).also {
            prefix.copyInto(it, 0, (first - prefixStart).toInt(), (end - prefixStart).toInt())
            samples.copyInto(it, count)
        }
        prefix.fill(0f)
        prefix = FloatArray(0)
        return result
    }

    fun reset() { history.fill(0f); prefix.fill(0f); prefix = FloatArray(0); position = 0; prefixStart = 0 }
}
