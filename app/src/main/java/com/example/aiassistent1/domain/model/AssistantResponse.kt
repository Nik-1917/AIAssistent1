package com.example.aiassistent1.domain.model

data class AssistantResponse(
    val intent: String,
    val reply: String,
    val params: AssistantParams? = null
)

sealed interface AssistantParams

data class CalendarSearchParams(
    val query: String,
    val rangeStart: String?,
    val rangeEnd: String?,
) : AssistantParams

data class CalendarAddParams(
    val title: String?,
    val startsAt: String?,
    val durationMin: Int?,
) : AssistantParams
