package com.example.aiassistent1.presentation.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiassistent1.calendar.core.domain.CalendarEvent
import com.example.aiassistent1.calendar.core.domain.CalendarEventDraft
import com.example.aiassistent1.calendar.core.domain.CalendarEventUpdate
import com.example.aiassistent1.calendar.core.domain.CreateCalendarEventUseCase
import com.example.aiassistent1.calendar.core.domain.DeleteCalendarEventUseCase
import com.example.aiassistent1.calendar.core.domain.ObserveCalendarEventsUseCase
import com.example.aiassistent1.calendar.core.domain.UpdateCalendarEventUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

data class CalendarUiState(
    val visibleMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val events: List<CalendarEvent> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val snackbarMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val observeCalendarEvents: ObserveCalendarEventsUseCase,
    private val createCalendarEvent: CreateCalendarEventUseCase,
    private val updateCalendarEvent: UpdateCalendarEventUseCase,
    private val deleteCalendarEvent: DeleteCalendarEventUseCase,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(CalendarUiState())
    private val visibleMonth = MutableStateFlow(mutableUiState.value.visibleMonth)
    private val refreshRequests = MutableStateFlow(0)
    private var pendingRefreshRequest: Int? = null
    private var refreshStartedAtElapsedMillis = 0L
    private var refreshCompletionJob: Job? = null

    val uiState: StateFlow<CalendarUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(visibleMonth, refreshRequests) { month, request -> MonthRefreshRequest(month, request) }
                .flatMapLatest { request ->
                    observeCalendarEvents(
                        request.month.atDay(1).atStartOfDay().toEpochMillis(),
                        request.month.plusMonths(1).atDay(1).atStartOfDay().toEpochMillis(),
                    ).map { events -> request.requestId to events }
            }.catch { error ->
                showError(error)
                finishRefreshImmediately()
            }.collect { (requestId, events) ->
                mutableUiState.update { it.copy(events = events) }
                if (requestId == pendingRefreshRequest) {
                    finishRefreshAfterMinimumDuration(requestId)
                }
            }
        }
    }

    fun showPreviousMonth() = showMonth(visibleMonth.value.minusMonths(1))

    fun showNextMonth() = showMonth(visibleMonth.value.plusMonths(1))

    fun selectDate(date: LocalDate) {
        mutableUiState.update { it.copy(selectedDate = date) }
    }

    fun refreshCalendar() {
        if (mutableUiState.value.isRefreshing) return
        val requestId = refreshRequests.value + 1
        pendingRefreshRequest = requestId
        refreshStartedAtElapsedMillis = SystemClock.elapsedRealtime()
        mutableUiState.update { it.copy(isRefreshing = true) }
        refreshRequests.value = requestId
    }

    fun createEvent(title: String, date: LocalDate, startTime: LocalTime, durationMinutes: Int) {
        viewModelScope.launch {
            val draft = buildDraft(title, date, startTime, durationMinutes).getOrElse { error ->
                showError(error)
                return@launch
            }
            createCalendarEvent(draft)
                .onSuccess { mutableUiState.update { it.copy(snackbarMessage = "Событие создано") } }
                .onFailure(::showError)
        }
    }

    fun updateEvent(
        id: String,
        title: String,
        date: LocalDate,
        startTime: LocalTime,
        durationMinutes: Int,
    ) {
        viewModelScope.launch {
            val draft = buildDraft(title, date, startTime, durationMinutes).getOrElse { error ->
                showError(error)
                return@launch
            }
            updateCalendarEvent(
                CalendarEventUpdate(
                    id = id,
                    title = draft.title,
                    startsAtEpochMillis = draft.startsAtEpochMillis,
                    endsAtEpochMillis = draft.endsAtEpochMillis,
                ),
            ).onSuccess {
                mutableUiState.update { it.copy(snackbarMessage = "Событие изменено") }
            }.onFailure(::showError)
        }
    }

    fun deleteEvent(id: String) {
        viewModelScope.launch {
            deleteCalendarEvent(id)
                .onSuccess { mutableUiState.update { it.copy(snackbarMessage = "Событие удалено") } }
                .onFailure(::showError)
        }
    }

    fun clearError() {
        mutableUiState.update { it.copy(error = null) }
    }

    fun clearSnackbar() {
        mutableUiState.update { it.copy(snackbarMessage = null) }
    }

    private fun showMonth(month: YearMonth) {
        visibleMonth.value = month
        mutableUiState.update {
            it.copy(
                visibleMonth = month,
                selectedDate = month.atDay(minOf(it.selectedDate.dayOfMonth, month.lengthOfMonth())),
            )
        }
    }

    private fun buildDraft(
        title: String,
        date: LocalDate,
        startTime: LocalTime,
        durationMinutes: Int,
    ): Result<CalendarEventDraft> = runCatching {
        require(durationMinutes > 0) { "Длительность события должна быть больше нуля." }
        val start = LocalDateTime.of(date, startTime).toEpochMillis()
        CalendarEventDraft(
            title = title,
            startsAtEpochMillis = start,
            endsAtEpochMillis = Math.addExact(
                start,
                Math.multiplyExact(durationMinutes.toLong(), MILLIS_PER_MINUTE),
            ),
        )
    }

    private fun LocalDateTime.toEpochMillis(): Long =
        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun showError(error: Throwable) {
        mutableUiState.update { it.copy(error = error.message ?: "Не удалось выполнить операцию с событием") }
    }

    private fun finishRefreshAfterMinimumDuration(requestId: Int) {
        refreshCompletionJob?.cancel()
        refreshCompletionJob = viewModelScope.launch {
            val elapsed = SystemClock.elapsedRealtime() - refreshStartedAtElapsedMillis
            delay((MINIMUM_REFRESH_INDICATOR_MILLIS - elapsed).coerceAtLeast(0L))
            if (pendingRefreshRequest == requestId) {
                pendingRefreshRequest = null
                mutableUiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private fun finishRefreshImmediately() {
        refreshCompletionJob?.cancel()
        pendingRefreshRequest = null
        mutableUiState.update { it.copy(isRefreshing = false) }
    }

    override fun onCleared() {
        refreshCompletionJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val MINIMUM_REFRESH_INDICATOR_MILLIS = 1_000L
    }

    private data class MonthRefreshRequest(
        val month: YearMonth,
        val requestId: Int,
    )
}
