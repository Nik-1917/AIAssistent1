package com.example.aiassistent1.presentation.viewmodel

/** Shown when the model answers a calendar-mode request with intent "chat" instead of a calendar command. */
data class CalendarChatPromptUiState(
    val query: String,
)
