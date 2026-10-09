package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.AppNavigationState
import com.example.aiassistent1.domain.model.ChatScrollPosition
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelProfile
import com.example.aiassistent1.domain.model.ModelParameterProfiles
import com.example.aiassistent1.domain.model.FloatingControlPositions
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.domain.model.SpeechRate
import com.example.aiassistent1.presentation.playback.SpeechPlaybackState

enum class ModelAvailability {
    Checking,
    Missing,
    Available,
}

data class ChatUiState(
    val navigationState: AppNavigationState? = null,
    val sessionError: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isHistoryLoaded: Boolean = false,
    val chatScrollPosition: ChatScrollPosition = ChatScrollPosition(),
    val isChatScrollPositionLoaded: Boolean = false,
    val floatingControlPositions: FloatingControlPositions = FloatingControlPositions(),
    val isFloatingControlPositionsLoaded: Boolean = false,
    val modelState: ModelState = ModelState.Unloaded,
    val modelAvailability: ModelAvailability = ModelAvailability.Checking,
    val calendarDrafts: CalendarDraftsState = CalendarDraftsState(),
    val calendarChatPrompt: CalendarChatPromptUiState? = null,
    val isProcessing: Boolean = false,
    val isCheckingRequest: Boolean = false,
    val isVoiceMode: Boolean = false,
    val voiceDraft: VoiceDraftState = VoiceDraftState(),
    val speechPlaybackState: SpeechPlaybackState = SpeechPlaybackState.Idle,
    val error: String? = null,
    val snackbarMessage: String? = null,
    val isStopping: Boolean = false,
    val showDeleteMessageConfirmation: Boolean = true,
    val showClearChatConfirmation: Boolean = true,
    val compactDatesEnabled: Boolean = true,
    val smoothResponseEnabled: Boolean = false,
    val systemPromptEnabled: Boolean = true,
    val dialogueModeEnabled: Boolean = false,
    val autoPlaybackEnabled: Boolean = true,
    val activeChatId: String = "general",
    val speechRate: Float = SpeechRate.DEFAULT,
    val availableModels: List<String> = emptyList(),
    val selectedModel: String = "",
    val modelProfiles: ModelParameterProfiles = ModelParameterProfiles(),
    val areModelParamsLoaded: Boolean = false,
    val chatHistoryPolicy: com.example.aiassistent1.domain.model.ChatHistoryPolicy = com.example.aiassistent1.domain.model.ChatHistoryPolicy.ASK,
    val chatContextPressure: com.example.aiassistent1.domain.model.ChatContextPressure? = null,
) {
    val isCalendarMode: Boolean get() = activeChatId == "calendar"
    val modelProfile: ModelProfile get() = ModelProfile.forCalendarMode(isCalendarMode)
    val modelParams: GenerationParams get() = modelProfiles[modelProfile]
    val isGenerating: Boolean get() = isProcessing && !isCheckingRequest
    val calendarEventDraft: CalendarEventDraftUiState? get() = calendarDrafts.selected
}
