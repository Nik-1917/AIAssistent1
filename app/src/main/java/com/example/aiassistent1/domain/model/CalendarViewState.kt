package com.example.aiassistent1.domain.model

import java.time.LocalDate
import java.time.YearMonth

/** Last month/day shown on the calendar page, restored after restart or a crash. */
data class CalendarViewState(
    val visibleMonth: YearMonth? = null,
    val selectedDate: LocalDate? = null,
)
