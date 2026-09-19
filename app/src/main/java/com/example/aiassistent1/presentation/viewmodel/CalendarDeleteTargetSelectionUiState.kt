package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.calendar.core.domain.CalendarCommand
import com.example.aiassistent1.calendar.core.domain.CalendarEvent

data class CalendarDeleteTargetSelectionUiState(
    val candidates: List<CalendarEvent>,
    val command: CalendarCommand.Delete,
    val requestId: String,
    val deletingEventId: String? = null,
    val deletedCount: Int = 0,
) {
    val allowsMultipleDeletes: Boolean
        get() = command.target.let { it.query == null && it.range != null &&
            !it.useLastCreated && !it.useLastInRange }

    internal fun deletionRequestId(eventId: String): String =
        if (allowsMultipleDeletes) "$requestId/delete/$eventId" else requestId

    internal fun beginDelete(eventId: String): CalendarDeleteTargetSelectionUiState? =
        if (deletingEventId != null || candidates.none { it.id == eventId }) null
        else copy(deletingEventId = eventId)

    internal fun finishDelete(eventId: String, succeeded: Boolean): CalendarDeleteTargetSelectionUiState {
        if (deletingEventId != eventId) return this
        return copy(
            candidates = if (succeeded) candidates.filterNot { it.id == eventId } else candidates,
            deletingEventId = null,
            deletedCount = deletedCount + if (succeeded) 1 else 0,
        )
    }
}
