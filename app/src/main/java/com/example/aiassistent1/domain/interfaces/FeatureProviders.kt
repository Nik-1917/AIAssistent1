package com.example.aiassistent1.domain.interfaces

import kotlinx.coroutines.flow.Flow
import com.example.aiassistent1.domain.model.SynthesizedSpeech

interface ModelProvider {
    suspend fun getModelPath(): Result<String>
}

interface InputProvider {
    fun observeInput(): Flow<String>
    fun start()
    fun stop()
}

interface SpeechRecognizer : AutoCloseable {
    suspend fun recognize(samples: FloatArray): Result<String>
}

interface SpeechSynthesizer : AutoCloseable {
    suspend fun synthesize(text: String): Result<SynthesizedSpeech>
}

interface SpeechPlayback : AutoCloseable {
    suspend fun speak(text: String): Result<Unit>
    fun stop()
}

interface VoiceActivityDetector : AutoCloseable {
    suspend fun accept(samples: FloatArray): List<FloatArray>
    fun reset()
}

interface CalendarProvider {
    suspend fun openCalendar(): Result<Unit>
}

interface AgentTool {
    suspend fun execute(input: String): Result<String>
}