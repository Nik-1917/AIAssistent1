package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.AppDestination
import com.example.aiassistent1.domain.model.AppNavigationState
import com.example.aiassistent1.domain.model.CalendarViewState
import com.example.aiassistent1.domain.model.ChatScrollPosition
import com.example.aiassistent1.domain.model.FloatingControlPositions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val audioPreferences: Flow<com.example.aiassistent1.domain.model.AudioPreferences>
    suspend fun readAudioPreferences(): com.example.aiassistent1.domain.model.AudioPreferences
    suspend fun completeVoiceEnrollment(revision: Long): Boolean
    suspend fun setWakeWordEnabled(enabled: Boolean)
    suspend fun setWakeWordEngine(engine: com.example.aiassistent1.domain.model.WakeWordEngine)
    suspend fun setRecordingDirectory(uri: String?)
    val navigationState: Flow<AppNavigationState>
    suspend fun setAppDestination(destination: AppDestination)

    val selectedModel: StateFlow<String?>
    suspend fun setSelectedModel(modelName: String)

    fun getParamsForModel(modelName: String): StateFlow<GenerationParams>
    suspend fun updateParamsForModel(modelName: String, params: GenerationParams)

    val showDeleteMessageConfirmation: StateFlow<Boolean>
    suspend fun setShowDeleteMessageConfirmation(show: Boolean)

    val showClearChatConfirmation: StateFlow<Boolean>
    suspend fun setShowClearChatConfirmation(show: Boolean)

    val compactDatesEnabled: StateFlow<Boolean>
    suspend fun setCompactDatesEnabled(enabled: Boolean)

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

    val chatScrollPosition: Flow<ChatScrollPosition>
    suspend fun setChatScrollPosition(position: ChatScrollPosition)

    val floatingControlPositions: Flow<FloatingControlPositions>
    suspend fun setFloatingControlPositions(positions: FloatingControlPositions)

    val calendarViewState: Flow<CalendarViewState>
    suspend fun setCalendarViewState(state: CalendarViewState)

    val isFirstRun: Flow<Boolean>
    suspend fun setFirstRunCompleted()

    val voiceIdEnabled: StateFlow<Boolean>
    suspend fun setVoiceIdEnabled(enabled: Boolean)

    val voiceIdNeedsEnrollment: StateFlow<Boolean>
    suspend fun setVoiceIdNeedsEnrollment(needsEnrollment: Boolean)

    val customWakeWord: StateFlow<String>
    suspend fun setCustomWakeWord(word: String)

    val autoSummaryEnabled: StateFlow<Boolean>
    suspend fun setAutoSummaryEnabled(enabled: Boolean)

    val conferenceModeEffectsEnabled: StateFlow<Boolean>
    suspend fun setConferenceModeEffectsEnabled(enabled: Boolean)

    val bargeInEnabled: StateFlow<Boolean>
    suspend fun setBargeInEnabled(enabled: Boolean)

    val activationSoundUri: StateFlow<String?>
    suspend fun setActivationSoundUri(uri: String?)

    val hapticFeedbackEnabled: StateFlow<Boolean>
    suspend fun setHapticFeedbackEnabled(enabled: Boolean)
}
