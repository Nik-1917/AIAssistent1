package com.example.aiassistent1.calendar.core.domain

/** Selects one local event for immediate deletion. */
data class CalendarDeleteCommand(
    val target: CalendarUpdateTarget,
)
