package com.example.aiassistent1.data.provider

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.example.aiassistent1.domain.interfaces.SpeakerIdentifier
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Conference NS and voice AEC keep separate sessions; loopback never opens a second microphone. */
class AudioProcessingManager(speakerIdentifier: SpeakerIdentifier? = null) {
    enum class EchoBackend { WEBRTC, ANDROID }
    private val reference = PlaybackEchoReference()
    private val mutableReferenceNeeded = MutableStateFlow(false)
    val referenceNeeded = mutableReferenceNeeded.asStateFlow()
    private var active: Session? = null
    internal val speechEchoGuard = AssistantSpeechEchoGuard(speakerIdentifier)

    internal fun beginOutput() = speechEchoGuard.beginPlayback()
    internal fun prepareOutput(text: String, samples: FloatArray, playbackId: Long) =
        speechEchoGuard.prepare(text, samples, playbackId)

    @Synchronized fun open(recorder: AudioRecord, enabled: Boolean, echo: Boolean,
        backend: EchoBackend = EchoBackend.WEBRTC): Session {
        check(active == null) { "Аудиообработка уже используется" }
        return Session(recorder, enabled, echo, backend, reference).also {
            active = it
            mutableReferenceNeeded.value = it.softwareEcho
        }
    }

    fun onOutputSamples(samples: FloatArray, sampleRate: Int, delayMs: Int,
        firstSampleTimeNs: Long = System.nanoTime() + delayMs * 1_000_000L, utteranceId: Long = 0) {
        require(sampleRate == PlaybackEchoReference.SAMPLE_RATE)
        reference.write(PlaybackEchoReference.Source.ASSISTANT, samples, firstSampleTimeNs)
        speechEchoGuard.queued(utteranceId, firstSampleTimeNs, samples.size)
    }

    internal fun onOtherAppSamples(samples: FloatArray, firstSampleTimeNs: Long) =
        reference.write(PlaybackEchoReference.Source.OTHER_APPS, samples, firstSampleTimeNs)

    internal fun clearOtherApps() = reference.clear(PlaybackEchoReference.Source.OTHER_APPS)
    fun onOutputStopped() {
        val now = System.nanoTime()
        reference.discardFrom(PlaybackEchoReference.Source.ASSISTANT, now)
        speechEchoGuard.outputStopped(now)
    }

    internal data class CaptureStatistics(val samples: Long, val referenceSamples: Long,
        val rawRms: Double, val processedRms: Double, val profile: String)
    @Synchronized internal fun captureStatistics(): CaptureStatistics? = active?.statistics()

    @Synchronized fun close(session: Session) {
        session.close()
        if (active === session) {
            active = null
            mutableReferenceNeeded.value = false
        }
    }

    class Session internal constructor(recorder: AudioRecord, enabled: Boolean, echo: Boolean,
        backend: EchoBackend, private val reference: PlaybackEchoReference) : AutoCloseable {
        private var native = 0L
        private var aec: AcousticEchoCanceler? = null
        private var ns: NoiseSuppressor? = null
        private val near = FloatArray(160)
        private val far = FloatArray(160)
        private var nearSize = 0
        private var nearTimeNs = 0L
        private data class Frame(val samples: FloatArray, val timeNs: Long)
        private val pending = ArrayDeque<Frame>()
        private var capturedSamples = 0L
        private var referenceSamples = 0L
        private var rawEnergy = 0.0
        private var processedEnergy = 0.0
        var outputStartTimeNs: Long = 0
            private set
        val softwareEcho: Boolean
        val profile: String

        init {
            var nativeEcho = false
            profile = if (!enabled) "Исходный звук" else if (echo && backend == EchoBackend.ANDROID && enableSystemEcho(recorder)) {
                enableSystemNoise(recorder)
                "Эхоподавление Android"
            } else {
                // Retain the handle, keeping the platform AEC disabled for this software session.
                if (echo && AcousticEchoCanceler.isAvailable()) {
                    aec?.release()
                    aec = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { setEnabled(false) }
                    if (aec?.enabled == true) {
                        aec?.release(); aec = null
                        error("Не удалось отключить системное эхоподавление")
                    }
                }
                try {
                    if (NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(recorder.audioSessionId)?.apply { setEnabled(false) }
                    }
                    native = NativeAudio.createProcessor(echo)
                    check(native != 0L)
                    nativeEcho = echo
                    "WebRTC NS" + if (echo) " + AEC" else ""
                } catch (error: LinkageError) {
                    Log.w("AudioProcessing", "Native unavailable", error)
                    fallback(recorder, echo)
                } catch (error: IllegalStateException) {
                    Log.w("AudioProcessing", "Native initialization failed", error)
                    fallback(recorder, echo)
                }
            }
            softwareEcho = nativeEcho
        }

        private fun enableSystemEcho(recorder: AudioRecord): Boolean = runCatching {
            if (!AcousticEchoCanceler.isAvailable()) return false
            aec?.release()
            aec = AcousticEchoCanceler.create(recorder.audioSessionId)
            aec?.let { it.hasControl() && it.setEnabled(true) == 0 && it.enabled } == true
        }.getOrDefault(false)

        private fun enableSystemNoise(recorder: AudioRecord) {
            ns?.release()
            ns = null
            runCatching {
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(recorder.audioSessionId)?.apply { setEnabled(true) }
            }.onFailure { Log.w("AudioProcessing", "Android noise suppression unavailable", it) }
        }

        private fun fallback(recorder: AudioRecord, echo: Boolean): String {
            val hasEcho = echo && enableSystemEcho(recorder)
            enableSystemNoise(recorder)
            return if (hasEcho) "Эхоподавление Android" else if (ns?.enabled == true) "Шумоподавление Android"
                else "Исходный звук: эффекты недоступны"
        }

        @Synchronized fun process(samples: FloatArray,
            firstSampleTimeNs: Long = System.nanoTime() - samples.size * PlaybackEchoReference.NANOS_PER_SAMPLE): FloatArray {
            if (native == 0L) {
                outputStartTimeNs = firstSampleTimeNs
                capturedSamples += samples.size
                samples.forEach { rawEnergy += it.toDouble() * it; processedEnergy += it.toDouble() * it }
                return samples
            }
            // Give the independently scheduled playback-capture reader 40 ms to deliver its reference.
            val blocks = (nearSize + samples.size) / 160
            val outputBlocks = if (softwareEcho) (pending.size + blocks - 4).coerceAtLeast(0) else blocks
            val result = FloatArray(outputBlocks * 160)
            var written = 0
            samples.forEachIndexed { index, sample ->
                if (nearSize == 0) nearTimeNs = firstSampleTimeNs + index * PlaybackEchoReference.NANOS_PER_SAMPLE
                near[nearSize++] = sample
                if (nearSize == 160) {
                    val frame = if (softwareEcho) {
                        pending.addLast(Frame(near.copyOf(), nearTimeNs))
                        if (pending.size > 4) pending.removeFirst() else null
                    } else Frame(near, nearTimeNs)
                    if (frame != null) {
                        if (written == 0) outputStartTimeNs = frame.timeNs
                        processFrame(frame.samples, frame.timeNs)
                        frame.samples.copyInto(result, written)
                        written += 160
                    }
                    nearSize = 0
                }
            }
            return result
        }

        private fun processFrame(samples: FloatArray, timeNs: Long) {
            capturedSamples += samples.size
            samples.forEach { rawEnergy += it.toDouble() * it }
            if (softwareEcho) {
                reference.read(timeNs, far)
                referenceSamples += far.count { it != 0f }
                NativeAudio.processFrame(native, far, true, 0)
            }
            NativeAudio.processFrame(native, samples, false, 0)
            samples.forEach { processedEnergy += it.toDouble() * it }
        }

        @Synchronized internal fun statistics() = CaptureStatistics(capturedSamples, referenceSamples,
            sqrt(rawEnergy / capturedSamples.coerceAtLeast(1)), sqrt(processedEnergy / capturedSamples.coerceAtLeast(1)), profile)

        @Synchronized fun flush(): FloatArray {
            if (native == 0L) return FloatArray(0)
            val result = FloatArray(pending.size * 160 + nearSize)
            var written = 0
            while (pending.isNotEmpty()) {
                val frame = pending.removeFirst()
                processFrame(frame.samples, frame.timeNs)
                frame.samples.copyInto(result, written)
                written += 160
            }
            if (nearSize > 0) {
                near.fill(0f, nearSize)
                processFrame(near, nearTimeNs)
                near.copyInto(result, written, 0, nearSize)
                nearSize = 0
            }
            return result
        }

        @Synchronized fun discardBufferedInput() {
            nearSize = 0
            near.fill(0f)
            pending.forEach { it.samples.fill(0f) }; pending.clear()
        }

        @Synchronized override fun close() {
            if (native != 0L) NativeAudio.closeProcessor(native)
            native = 0
            pending.forEach { it.samples.fill(0f) }; pending.clear()
            aec?.release(); ns?.release()
            aec = null; ns = null
        }
    }
}
