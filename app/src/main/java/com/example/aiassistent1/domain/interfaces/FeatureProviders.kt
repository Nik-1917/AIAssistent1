package com.example.aiassistent1.domain.interfaces

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.example.aiassistent1.domain.model.VoiceInputError
import com.example.aiassistent1.domain.model.VoiceInputEvent
import com.example.aiassistent1.domain.model.SynthesizedSpeech

interface ModelProvider {
    suspend fun getModelPath(): Result<String>
}

interface InputProvider {
    fun observeActivity(): Flow<com.example.aiassistent1.domain.model.AudioSessionState> = emptyFlow()
    /** Input accepted for interruption; provisional VAD/noise detections must not stop playback. */
    fun observeSpeechStart(): Flow<Long> = emptyFlow()
    fun observeInput(): Flow<VoiceInputEvent>
    fun observeErrors(): Flow<VoiceInputError> = emptyFlow()
    fun start(): Long
    fun startContinuous(noiseSuppressionEnabled: Boolean = true) = start()
    fun startWakeWord() = start()
    /** Updates only the named live capture; an old draft cannot change a later microphone session. */
    fun setNoiseSuppressionEnabled(sessionId: Long, enabled: Boolean) = Unit
    /** Completes only for this live capture session, after microphone processing is ready. */
    suspend fun awaitReady(sessionId: Long) = Unit
    /** One VAD utterance; implementations must share their existing capture ownership. */
    suspend fun captureEnrollmentSegment(): FloatArray = error("Запись ключевой фразы недоступна")
    fun stop()
    /** Invalidates capture immediately and awaits release of its recorder/processing session. */
    suspend fun stopAndAwait() { stop() }
}

interface SpeechRecognizer : AutoCloseable {
    suspend fun prepare() = Unit
    suspend fun recognize(samples: FloatArray): Result<String>
}

interface SpeechSynthesizer : AutoCloseable {
    suspend fun synthesize(text: String): Result<SynthesizedSpeech>
}

interface SpeechPlayback : AutoCloseable {
    suspend fun speak(text: String, onPlaybackStarted: () -> Unit): Result<Unit>
    /** Implementations may synthesize first, but must await readiness before audible output. */
    suspend fun speakWhenReady(text: String, awaitReady: suspend () -> Unit,
        onPlaybackStarted: () -> Unit): Result<Unit> {
        awaitReady()
        return speak(text, onPlaybackStarted)
    }
    fun stop()
}

/** A stopped/replaced microphone is not evidence that a new session is ready. */
class VoiceInputSessionChangedException : IllegalStateException("Сеанс микрофона изменился")

interface VoiceActivityDetector : AutoCloseable {
    fun isSpeechDetected(): Boolean = false
    suspend fun acceptTimed(samples: FloatArray): List<TimedVoiceSegment> = accept(samples).map { TimedVoiceSegment(0, it) }
    suspend fun flushTimed(): List<TimedVoiceSegment> = emptyList()
    suspend fun prepare() = Unit
    suspend fun accept(samples: FloatArray): List<FloatArray>
    fun reset()
}

data class TimedVoiceSegment(val startSample: Long, val samples: FloatArray)

interface AgentTool {
    suspend fun execute(input: String): Result<String>
}
