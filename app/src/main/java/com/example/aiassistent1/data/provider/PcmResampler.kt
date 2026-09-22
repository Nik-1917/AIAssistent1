package com.example.aiassistent1.data.provider

import kotlin.math.*

/** Windowed sinc with a low-pass cutoff avoids aliasing when reducing TTS sample rate. */
object PcmResampler {
    fun to16k(input: FloatArray, sampleRate: Int): FloatArray {
        require(sampleRate in 8_000..192_000)
        if (sampleRate == 16_000 || input.isEmpty()) return input
        val ratio = sampleRate / 16000.0
        val cutoff = min(1.0, 1.0 / ratio) * 0.92
        return FloatArray((input.size / ratio).toInt()) { index ->
            val position = index * ratio
            val center = position.toInt()
            var sum = 0.0
            var weight = 0.0
            for (n in center - 16..center + 16) {
                val distance = position - n
                if (abs(distance) >= 16) continue
                val x = PI * distance * cutoff
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(x) / x
                val w = sinc * (0.5 + 0.5 * cos(PI * distance / 16))
                sum += input[n.coerceIn(0, input.lastIndex)] * w
                weight += w
            }
            (sum / weight).toFloat().coerceIn(-1f, 1f)
        }
    }
}
