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
    fun observeInput(): Flow<VoiceInputEvent>
    fun observeErrors(): Flow<VoiceInputError> = emptyFlow()
    fun start(): Long
    fun startContinuous() = start()
    fun stop()
}

interface SpeechRecognizer : AutoCloseable {
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
    suspend fun accept(samples: FloatArray): List<FloatArray>
    fun reset()
}

interface CalendarProvider {
    suspend fun openCalendar(): Result<Unit>
    suspend fun addEvent(title: String, dateTime: String, durationMin: Int): Result<Unit>
    suspend fun searchEvents(query: String, days: Int): Result<List<com.example.aiassistent1.domain.model.CalendarEvent>>
}

interface AgentTool {
    suspend fun execute(input: String): Result<String>
}
