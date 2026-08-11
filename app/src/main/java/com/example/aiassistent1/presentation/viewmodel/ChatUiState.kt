package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelState

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val modelState: ModelState = ModelState.Unloaded,
    val isModelMissing: Boolean = false,
    val needsPermission: Boolean = false,
    val needsCalendarPermission: Boolean = false,
    val isProcessing: Boolean = false,
    val isVoiceMode: Boolean = false,
    val voiceDraft: VoiceDraftState = VoiceDraftState(),
    val error: String? = null,
    val snackbarMessage: String? = null,
    val isStopping: Boolean = false,
    val availableModels: List<String> = listOf("qwen2.5-3b-instruct-q4_k_m.gguf", "ruadapt_qwen2.5_3B_ext_u48_instruct_v4_Q4_K_M.gguf"),
    val selectedModel: String = "qwen2.5-3b-instruct-q4_k_m.gguf",
    val modelParams: GenerationParams = GenerationParams(),
)
