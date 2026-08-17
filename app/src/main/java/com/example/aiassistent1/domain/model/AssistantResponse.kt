package com.example.aiassistent1.domain.model

data class AssistantResponse(
    val intent: String,
    val reply: String,
    val params: AssistantParams? = null
)

sealed interface AssistantParams

data class CalendarSearchParams(
    val query: String,
    val days: Int = 7
) : AssistantParams

data class CalendarAddParams(
    val title: String,
    val date: String,
    val duration_min: Int = 60
) : AssistantParams
