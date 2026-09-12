package com.example.aiassistent1.domain.model

enum class AppDestination {
    CHAT,
    CALENDAR,
}

data class AppNavigationState(
    val destination: AppDestination = AppDestination.CHAT,
    val isCalendarMode: Boolean = true,
) {
    val chatId: String get() = if (isCalendarMode) "calendar" else "general"
}

data class AppSession(
    val navigation: AppNavigationState,
    val messages: List<ChatMessage>,
    val scrollPosition: ChatScrollPosition,
)
