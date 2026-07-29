package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.ModelState

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val modelState: ModelState = ModelState.Unloaded,
    val isProcessing: Boolean = false,
    val error: String? = null,
)