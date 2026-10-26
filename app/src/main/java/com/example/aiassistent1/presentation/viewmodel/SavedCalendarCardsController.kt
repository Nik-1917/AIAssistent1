package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.calendar.core.domain.CalendarEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

sealed interface SavedCalendarEventCardState {
    data object Loading : SavedCalendarEventCardState
    data class Available(val event: CalendarEvent) : SavedCalendarEventCardState
    data object Missing : SavedCalendarEventCardState
    data object Failed : SavedCalendarEventCardState
}

/** Read-only presentation. Never edits a draft, commits a mutation or writes chat history. */
class SavedCalendarCardsController(
    private val scope: CoroutineScope,
    private val observeEvents: (Set<String>, ZoneId) -> Flow<Map<String, CalendarEvent?>>,
    private val onError: (Throwable) -> Unit = {},
) {
    private data class Request(val ids: Set<String>, val zoneId: ZoneId)
    private var request: Request? = null
    private var observer: Job? = null
    private var generation = 0L
    private val mutableState = MutableStateFlow<Map<String, SavedCalendarEventCardState>>(emptyMap())
    val state = mutableState.asStateFlow()

    fun watch(drafts: List<CalendarEventDraftUiState>, visibleReplyIds: Set<String>, zoneId: ZoneId) {
        val ids = drafts.asSequence().filter { it.chatId == "calendar" && it.requestId in visibleReplyIds }
            .mapNotNull { it.savedEventId }.toSet()
        observe(Request(ids, zoneId))
    }

    fun retry() { request?.let { observe(it, force = true) } }

    private fun observe(next: Request, force: Boolean = false) {
        if (!force && next == request) return
        val previous = mutableState.value.takeIf { request?.zoneId == next.zoneId }.orEmpty()
        request = next
        val ticket = ++generation
        observer?.cancel()
        mutableState.value = next.ids.associateWith { id ->
            previous[id]?.takeUnless { it == SavedCalendarEventCardState.Failed } ?: SavedCalendarEventCardState.Loading
        }
        if (next.ids.isEmpty()) return
        observer = scope.launch {
            try {
                observeEvents(next.ids, next.zoneId).collect { events ->
                    if (ticket == generation) mutableState.value = next.ids.associateWith { id ->
                        check(events.containsKey(id)) { "Missing snapshot for linked calendar event" }
                        events[id]?.let {
                            check(it.id == id) { "Linked calendar event identity mismatch" }
                            SavedCalendarEventCardState.Available(it)
                        } ?: SavedCalendarEventCardState.Missing
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ticket == generation) {
                    mutableState.value = next.ids.associateWith { SavedCalendarEventCardState.Failed }
                    onError(error)
                }
            }
        }
    }
}
