package com.example.aiassistent1.domain.interfaces

import kotlinx.coroutines.flow.Flow

interface ModelProvider {
    suspend fun getModelPath(): Result<String>
}

interface InputProvider {
    fun observeInput(): Flow<String>
    fun start()
    fun stop()
}

interface CalendarProvider {
    suspend fun openCalendar(): Result<Unit>
}

interface AgentTool {
    suspend fun execute(input: String): Result<String>
}