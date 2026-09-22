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
    fun observeSpeechStart(): Flow<Long> = emptyFlow()
    fun observeInput(): Flow<VoiceInputEvent>
    fun observeErrors(): Flow<VoiceInputError> = emptyFlow()
    fun start(): Long
    fun startContinuous() = start()
    fun startWakeWord() = start()
    fun startBargeIn() = start()
    fun stop()
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
    fun stop()
}

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
