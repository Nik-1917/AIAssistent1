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
    val value: Long? = null,
    val clearValue: Boolean = false,
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
