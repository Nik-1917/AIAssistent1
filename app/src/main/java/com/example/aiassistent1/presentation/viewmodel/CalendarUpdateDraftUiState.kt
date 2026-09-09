package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.calendar.core.domain.CalendarEvent
import com.example.aiassistent1.calendar.core.domain.CalendarEventChanges
import com.example.aiassistent1.calendar.core.domain.CalendarUpdateCommand
import com.example.aiassistent1.calendar.core.domain.CalendarUpdateTarget

enum class CalendarUpdateField(val label: String) {
    Title("Новое название события"),
    StartsAt("Новые дата и время (ГГГГ-ММ-ДДTЧЧ:ММ)"),
    DurationMinutes("Новая длительность в минутах"),
}

data class CalendarUpdateDraftUiState(
    val event: CalendarEvent,
    val changes: CalendarEventChanges,
    val previewTitle: String,
    val previewStartsAt: String,
    val previewDurationMinutes: Int,
    val target: CalendarUpdateTarget,
    val requestId: String,
    val isSelectingField: Boolean = false,
    val activeField: CalendarUpdateField? = null,
    val input: String = "",
    val error: String? = null,
) {
    val isReadyForConfirmation: Boolean
        get() = !isSelectingField && activeField == null && !changes.isEmpty
}

data class CalendarUpdateTargetSelectionUiState(
    val candidates: List<CalendarEvent>,
    val command: CalendarUpdateCommand,
    val requestId: String = "",
)
