package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.presentation.playback.SpeechPlaybackState

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val modelState: ModelState = ModelState.Unloaded,
    val isModelMissing: Boolean = false,
    val calendarEventDraft: CalendarEventDraftUiState? = null,
    val isProcessing: Boolean = false,
    val isVoiceMode: Boolean = false,
    val voiceDraft: VoiceDraftState = VoiceDraftState(),
    val speechPlaybackState: SpeechPlaybackState = SpeechPlaybackState.Idle,
    val error: String? = null,
    val snackbarMessage: String? = null,
    val isStopping: Boolean = false,
    val showDeleteMessageConfirmation: Boolean = true,
    val showClearChatConfirmation: Boolean = true,
    val availableModels: List<String> = emptyList(),
    val selectedModel: String = "",
    val modelParams: GenerationParams = GenerationParams(),
)
