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
    /** A known event date when the user has not specified its time yet. */
    val date: String? = null,
    /** A known event time when the user has not specified its date yet. */
    val time: String? = null,
) : AssistantParams

/** JSON transport model only. Map it to calendar-core's CalendarUpdateCommand before execution. */
data class CalendarUpdateParams(
    val target: CalendarUpdateTargetParams = CalendarUpdateTargetParams(),
    val changes: CalendarUpdateChangesParams = CalendarUpdateChangesParams(),
) : AssistantParams

data class CalendarUpdateTargetParams(
    val query: String? = null,
    val rangeStart: String? = null,
    val rangeEnd: String? = null,
    val useLastCreated: Boolean = false,
    val useLastReferenced: Boolean = false,
)

data class CalendarUpdateChangesParams(
    val title: String? = null,
    val date: String? = null,
    val time: String? = null,
    val durationMin: Int? = null,
)

/** JSON transport model for an immediate local-calendar deletion. */
data class CalendarDeleteParams(
    val target: CalendarDeleteTargetParams = CalendarDeleteTargetParams(),
) : AssistantParams

data class CalendarDeleteTargetParams(
    val query: String? = null,
    val rangeStart: String? = null,
    val rangeEnd: String? = null,
    val useLastCreated: Boolean = false,
    val useLastInRange: Boolean = false,
)
