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

    /** Commands issued on the calendar page always belong to the calendar conversation. */
    fun normalized(): AppNavigationState = if (destination == AppDestination.CALENDAR && !isCalendarMode) {
        copy(isCalendarMode = true)
    } else this
}

data class AppSession(
    val navigation: AppNavigationState,
    val messages: List<ChatMessage>,
    val scrollPosition: ChatScrollPosition,
)
