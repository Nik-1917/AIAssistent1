package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ChatScrollPosition
import com.example.aiassistent1.domain.model.FloatingControlPositions
import com.example.aiassistent1.domain.model.SpeechVoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val selectedModel: StateFlow<String>
    suspend fun setSelectedModel(modelName: String)

    fun getParamsForModel(modelName: String): StateFlow<GenerationParams>
    suspend fun updateParamsForModel(modelName: String, params: GenerationParams)

    val showDeleteMessageConfirmation: StateFlow<Boolean>
    suspend fun setShowDeleteMessageConfirmation(show: Boolean)

    val showClearChatConfirmation: StateFlow<Boolean>
    suspend fun setShowClearChatConfirmation(show: Boolean)

    val smoothResponseEnabled: StateFlow<Boolean>
    suspend fun setSmoothResponseEnabled(enabled: Boolean)

    val systemPromptEnabled: StateFlow<Boolean>
    suspend fun setSystemPromptEnabled(enabled: Boolean)

    val dialogueModeEnabled: StateFlow<Boolean>
    suspend fun setDialogueModeEnabled(enabled: Boolean)

    val autoPlaybackEnabled: StateFlow<Boolean>
    suspend fun setAutoPlaybackEnabled(enabled: Boolean)

    val speechRate: StateFlow<Float>
    suspend fun setSpeechRate(rate: Float)

    val speechVoice: StateFlow<SpeechVoice>
    suspend fun setSpeechVoice(voice: SpeechVoice)

    val chatScrollPosition: Flow<ChatScrollPosition>
    suspend fun setChatScrollPosition(position: ChatScrollPosition)

    val floatingControlPositions: Flow<FloatingControlPositions>
    suspend fun setFloatingControlPositions(positions: FloatingControlPositions)

    val isFirstRun: Flow<Boolean>
    suspend fun setFirstRunCompleted()
}
