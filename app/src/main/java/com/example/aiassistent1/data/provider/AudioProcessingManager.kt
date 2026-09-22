package com.example.aiassistent1.data.provider

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/** Each capture owns its processor. Render reference is delivered only to the active session. */
class AudioProcessingManager {
    private var active: Session? = null
    @Synchronized fun open(recorder: AudioRecord, enabled: Boolean, echo: Boolean): Session {
        check(active == null) { "Аудиообработка уже используется" }
        return Session(recorder, enabled, echo).also { active = it }
    }
    @Synchronized fun onOutputSamples(samples: FloatArray, sampleRate: Int, delayMs: Int) {
        active?.render(samples, sampleRate, delayMs)
    }
    @Synchronized fun close(session: Session) {
        session.close()
        if (active === session) active = null
    }
    class Session(recorder: AudioRecord, enabled: Boolean, echo: Boolean) : AutoCloseable {
        private var native = 0L
        private var aec: AcousticEchoCanceler? = null
        private var ns: NoiseSuppressor? = null
        private val near = FloatArray(160)
        private val far = FloatArray(160)
        private var nearSize = 0
        private var farSize = 0
        private var delay = 40
        val profile: String
        init {
            profile = if (!enabled) "Исходный звук" else {
                try {
                    native = NativeAudio.createProcessor(echo)
                    check(native != 0L)
                    "WebRTC NS" + if (echo) " + AEC" else ""
                } catch (error: LinkageError) {
                    Log.w("AudioProcessing", "Native unavailable, using Android effects", error)
                    configureHardware(recorder, echo)
                }
            }
        }
        private fun configureHardware(recorder: AudioRecord, echo: Boolean): String {
            if (echo && AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
            }
            return if (aec != null || ns != null) "Эффекты Android" else "Исходный звук: эффекты недоступны"
        }
        @Synchronized fun process(samples: FloatArray): FloatArray {
            if (native == 0L) return samples
            val result = FloatArray(((nearSize + samples.size) / 160) * 160)
            var written = 0
            samples.forEach {
                near[nearSize++] = it
                if (nearSize == 160) {
                    NativeAudio.processFrame(native, near, false, delay)
                    near.copyInto(result, written)
                    written += 160
                    nearSize = 0
                }
            }
            return result
        }
        @Synchronized internal fun render(samples: FloatArray, sampleRate: Int, delayMs: Int) {
            if (native == 0L || samples.isEmpty()) return
            delay = delayMs.coerceIn(0, 500)
            // Playback passes exact 10 ms blocks (resampled before writing to the track).
            require(sampleRate == 16_000)
            samples.forEach {
                far[farSize++] = it
                if (farSize == 160) {
                    NativeAudio.processFrame(native, far, true, delay)
                    farSize = 0
                }
            }
        }
        @Synchronized fun flush(): FloatArray {
            if (native == 0L || nearSize == 0) return FloatArray(0)
            val size = nearSize
            near.fill(0f, nearSize)
            NativeAudio.processFrame(native, near, false, delay)
            nearSize = 0
            return near.copyOf(size)
        }
        @Synchronized override fun close() {
            if (native != 0L) NativeAudio.closeProcessor(native)
            native = 0
            aec?.release(); ns?.release()
            aec = null; ns = null
        }
    }
}
