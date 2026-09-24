package com.example.aiassistent1.data.provider

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Experimental, fixed upstream CLI MFCC recipe. This is NOT verified checkpoint metadata
 * and is not connected to activation. See docs/METRIC_KWS_DSP.md for reference and parity.
 * Input is mono normalized PCM at 16 kHz. Output is row-major [101 frames, 40 coefficients].
 * No preemphasis, resampling, amplitude normalization, delta features or learned parameters.
 */
internal class MetricKwsFeatureExtractor {
    private val window = FloatArray(FFT_SIZE) { index ->
        // Periodic Hann, float32 angle as in torch.hann_window.
        (1f - cos(index * (2.0 * PI / FFT_SIZE).toFloat())) * .5f
    }
    private val melFilters = createMelFilters()
    private val dct = Array(COEFFICIENTS) { coefficient ->
        FloatArray(COEFFICIENTS) { mel ->
            val angle = (PI / COEFFICIENTS).toFloat() * (mel + .5f) * coefficient
            val scale = sqrt(2.0 / COEFFICIENTS).toFloat()
            cos(angle) * (if (coefficient == 0) (1.0 / sqrt(2.0)).toFloat() else 1f) * scale
        }
    }
    private val twiddleReal = DoubleArray(FFT_SIZE) { cos(-2.0 * PI * it / FFT_SIZE) }
    private val twiddleImaginary = DoubleArray(FFT_SIZE) { sin(-2.0 * PI * it / FFT_SIZE) }

    fun extract(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): FloatArray {
        require(sampleRate == SAMPLE_RATE) { "MFCC requires 16 kHz mono PCM" }
        require(samples.size in MIN_SAMPLES..MAX_SAMPLES) { "MFCC requires 0.5–8 seconds" }
        require(samples.all { it.isFinite() && it in -1f..1f }) { "Invalid normalized PCM" }
        val audio = centerOneSecond(samples)
        val frame = DoubleArray(FFT_SIZE)
        val fft = FftWorkspace()
        val power = FloatArray(FREQUENCIES)
        val melDb = FloatArray(FRAMES * COEFFICIENTS)
        try {
            var maximumDb = Float.NEGATIVE_INFINITY
            for (time in 0 until FRAMES) {
                for (index in 0 until FFT_SIZE) {
                    val position = time * HOP_SIZE + index - FFT_SIZE / 2
                    val reflected = when {
                        position < 0 -> -position
                        position >= SAMPLE_RATE -> 2 * SAMPLE_RATE - position - 2
                        else -> position
                    }
                    frame[index] = (audio[reflected] * window[index]).toDouble()
                }
                fft.transform(frame)
                for (frequency in 0 until FREQUENCIES) {
                    power[frequency] = (fft.real[frequency] * fft.real[frequency] +
                        fft.imaginary[frequency] * fft.imaginary[frequency]).toFloat()
                }
                for (mel in 0 until COEFFICIENTS) {
                    var energy = 0.0
                    for (frequency in 0 until FREQUENCIES) {
                        energy += power[frequency].toDouble() * melFilters[mel][frequency]
                    }
                    val db = 10f * log10(max(energy.toFloat(), 1e-10f))
                    melDb[time * COEFFICIENTS + mel] = db
                    maximumDb = max(maximumDb, db)
                }
            }
            // AmplitudeToDB(power, top_db=80) uses one maximum over the entire utterance.
            val floor = maximumDb - 80f
            return FloatArray(FRAMES * COEFFICIENTS) { offset ->
                val time = offset / COEFFICIENTS
                val coefficient = offset % COEFFICIENTS
                var result = 0.0
                for (mel in 0 until COEFFICIENTS) {
                    result += max(melDb[time * COEFFICIENTS + mel], floor).toDouble() * dct[coefficient][mel]
                }
                result.toFloat()
            }
        } finally {
            audio.fill(0f)
            frame.fill(0.0)
            power.fill(0f)
            melDb.fill(0f)
            fft.clear()
        }
    }

    /** Matches loadWAV at the audited upstream commit, including odd-length left padding. */
    private fun centerOneSecond(samples: FloatArray): FloatArray {
        val result = FloatArray(SAMPLE_RATE)
        if (samples.size < SAMPLE_RATE) {
            samples.copyInto(result, (SAMPLE_RATE - samples.size + 1) / 2)
        } else {
            val start = (samples.size - SAMPLE_RATE) / 2
            samples.copyInto(result, 0, start, start + SAMPLE_RATE)
        }
        return result
    }

    private fun createMelFilters(): Array<FloatArray> {
        // HTK scale, 40 triangular bands, f_min=0, f_max=8000, no Slaney normalization.
        val melMax = 2595.0 * log10(1.0 + SAMPLE_RATE / 2.0 / 700.0)
        val step = (melMax / (COEFFICIENTS + 1)).toFloat()
        val melEnd = melMax.toFloat()
        val points = FloatArray(COEFFICIENTS + 2) { index ->
            // Match float32 linspace's endpoint handling.
            val mel = if (index < (COEFFICIENTS + 2) / 2) step * index
                else melEnd - step * (COEFFICIENTS + 1 - index)
            700f * (10f.pow(mel / 2595f) - 1f)
        }
        return Array(COEFFICIENTS) { mel ->
            FloatArray(FREQUENCIES) { bin ->
                val hz = if (bin < FREQUENCIES / 2) (SAMPLE_RATE / 2f / (FREQUENCIES - 1)) * bin
                    else SAMPLE_RATE / 2f - (SAMPLE_RATE / 2f / (FREQUENCIES - 1)) * (FREQUENCIES - 1 - bin)
                max(0f, min((hz - points[mel]) / (points[mel + 1] - points[mel]),
                    (points[mel + 2] - hz) / (points[mel + 2] - points[mel + 1])))
            }
        }
    }

    /** Mixed-radix FFT for 480=2^5*3*5. Scratch is per extraction, never shared across calls. */
    private inner class FftWorkspace {
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        private val scratchReal = DoubleArray(FFT_SIZE)
        private val scratchImaginary = DoubleArray(FFT_SIZE)

        fun transform(input: DoubleArray) = transform(input, 0, 1, FFT_SIZE, 0)

        private fun transform(input: DoubleArray, source: Int, stride: Int, size: Int, output: Int) {
            if (size == 1) {
                real[output] = input[source]
                imaginary[output] = 0.0
                return
            }
            val radix = when { size % 2 == 0 -> 2; size % 3 == 0 -> 3; else -> 5 }
            val childSize = size / radix
            for (part in 0 until radix) {
                transform(input, source + part * stride, stride * radix, childSize, output + part * childSize)
            }
            for (bin in 0 until size) {
                var realSum = 0.0
                var imaginarySum = 0.0
                for (part in 0 until radix) {
                    val child = output + part * childSize + bin % childSize
                    val twiddle = (part * bin * (FFT_SIZE / size)) % FFT_SIZE
                    realSum += real[child] * twiddleReal[twiddle] - imaginary[child] * twiddleImaginary[twiddle]
                    imaginarySum += real[child] * twiddleImaginary[twiddle] + imaginary[child] * twiddleReal[twiddle]
                }
                scratchReal[output + bin] = realSum
                scratchImaginary[output + bin] = imaginarySum
            }
            scratchReal.copyInto(real, output, output, output + size)
            scratchImaginary.copyInto(imaginary, output, output, output + size)
        }

        fun clear() {
            real.fill(0.0)
            imaginary.fill(0.0)
            scratchReal.fill(0.0)
            scratchImaginary.fill(0.0)
        }
    }

    companion object {
        const val FEATURE_VERSION = "metric-cli-mfcc40-1s-v1"
        const val SAMPLE_RATE = 16000
        const val FRAMES = 101
        const val COEFFICIENTS = 40
        private const val FFT_SIZE = 480
        private const val FREQUENCIES = FFT_SIZE / 2 + 1
        private const val HOP_SIZE = 160
        private const val MIN_SAMPLES = 8000
        private const val MAX_SAMPLES = 128000
    }
}
