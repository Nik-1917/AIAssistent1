package com.example.aiassistent1.domain.model

data class AssistantResponse(
    val intent: String,
    val reply: String,
    val params: AssistantParams? = null
)

sealed interface AssistantParams

data class CalendarSearchParams(
    val query: String?,
    val rangeStart: String?,
    val rangeEnd: String?,
) : AssistantParams

data class CalendarSumParams(
    val query: String?,
    val rangeStart: String?,
    val rangeEnd: String?,
) : AssistantParams

data class CalendarAddParams(
    val title: String?,
    val startsAt: String?,
    val durationMin: Int?,
    /** A known event date when the user has not specified its time yet. */
    val date: String? = null,
    /** A known event time paired with a resolved date. */
    val time: String? = null,
    val value: Long? = null,
    val notes: String? = null,
    val endsAt: String? = null,
) : AssistantParams
