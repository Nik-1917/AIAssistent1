package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.calendar.core.domain.CalendarCommand
import com.example.aiassistent1.calendar.core.domain.CalendarEvent

data class CalendarDeleteTargetSelectionUiState(
    val candidates: List<CalendarEvent>,
    val command: CalendarCommand.Delete,
    val requestId: String,
)
