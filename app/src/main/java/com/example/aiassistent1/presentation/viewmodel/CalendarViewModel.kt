package com.example.aiassistent1.presentation.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiassistent1.calendar.core.domain.CalendarEvent
import com.example.aiassistent1.calendar.core.domain.CalendarEventDraft
import com.example.aiassistent1.calendar.core.domain.CalendarEventUpdate
import com.example.aiassistent1.calendar.core.domain.CalendarValueChange
import com.example.aiassistent1.calendar.core.domain.CreateCalendarEventUseCase
import com.example.aiassistent1.calendar.core.domain.DeleteCalendarEventUseCase
import com.example.aiassistent1.calendar.core.domain.GetCalendarEventUseCase
import com.example.aiassistent1.calendar.core.domain.ObserveCalendarEventsUseCase
import com.example.aiassistent1.calendar.core.domain.UpdateCalendarEventUseCase
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import com.example.aiassistent1.domain.interfaces.CalendarTimeZoneRepository
import com.example.aiassistent1.domain.interfaces.SystemCalendarTimeZoneRepository
import com.example.aiassistent1.calendar.core.domain.CalendarTime
import com.example.aiassistent1.calendar.core.domain.CalendarTimeMode
import com.example.aiassistent1.domain.model.CalendarScheduleInput
import com.example.aiassistent1.domain.model.CalendarViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Instant
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
    val focusedEventId: String? = null,
    val zoneId: ZoneId = ZoneId.systemDefault(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val observeCalendarEvents: ObserveCalendarEventsUseCase,
    private val createCalendarEvent: CreateCalendarEventUseCase,
    private val updateCalendarEvent: UpdateCalendarEventUseCase,
    private val deleteCalendarEvent: DeleteCalendarEventUseCase,
    private val getCalendarEvent: GetCalendarEventUseCase,
    private val settingsRepository: SettingsRepository,
    private val calendarTimeZones: CalendarTimeZoneRepository = SystemCalendarTimeZoneRepository(),
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(CalendarUiState())
    private val visibleMonth = MutableStateFlow(mutableUiState.value.visibleMonth)
    private val refreshRequests = MutableStateFlow(0)
    private var pendingRefreshRequest: Int? = null
    private var refreshStartedAtElapsedMillis = 0L
    private var refreshCompletionJob: Job? = null
    private val restorePositionJob: Job

    val uiState: StateFlow<CalendarUiState> = mutableUiState.asStateFlow()

    init {
        restorePositionJob = restorePersistedCalendarPosition()
        viewModelScope.launch {
            combine(visibleMonth, refreshRequests,
                calendarTimeZones.state.filter { it.isLoaded }.map { it.zoneId }.distinctUntilChanged(),
            ) { month, request, zone -> MonthRefreshRequest(month, request, zone) }
                .flatMapLatest { request ->
                    observeCalendarEvents(
                        request.month.atDay(1).atStartOfDay(request.zoneId).toInstant().toEpochMilli(),
                        request.month.plusMonths(1).atDay(1).atStartOfDay(request.zoneId).toInstant().toEpochMilli(),
                        request.zoneId,
                    ).map { events -> Triple(request.requestId, request.zoneId, events) }
            }.catch { error ->
                showError(error)
                finishRefreshImmediately()
            }.collect { (requestId, zone, events) ->
                mutableUiState.update { it.copy(events = events, zoneId = zone) }
                if (requestId == pendingRefreshRequest) {
                    finishRefreshAfterMinimumDuration(requestId)
                }
            }
        }
    }

    /** Restores the last month/day shown before the app was closed or killed. */
    private fun restorePersistedCalendarPosition(): Job =
        viewModelScope.launch {
            val persisted = settingsRepository.calendarViewState.first()
            val month = persisted.visibleMonth ?: return@launch
            val date = persisted.selectedDate ?: month.atDay(1)
            visibleMonth.value = month
            mutableUiState.update { it.copy(visibleMonth = month, selectedDate = date) }
        }

    /** Follows the saved event, including a date changed after the chat card was created. */
    suspend fun focusSavedEvent(id: String): Result<Unit> {
        restorePositionJob.join()
        val event = try {
            getCalendarEvent(id).getOrElse { return Result.failure(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result.failure(error)
        } ?: return Result.failure(NoSuchElementException("Событие не найдено в локальном календаре"))

        val zone = try {
            calendarTimeZones.snapshot().zoneId
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { return Result.failure(error) }
        val eventSchedule = event.schedule
        val navigationEvent = if (eventSchedule?.isRepeating == true && event.occurrenceDate == null && event.parentSeriesId == null)
            eventSchedule.firstOccurrence(event, zone) ?: return Result.failure(NoSuchElementException("В серии нет доступных повторений")) else event
        val date = navigationEvent.localStart(zone).toLocalDate()
        val month = YearMonth.from(date)
        try {
            settingsRepository.setCalendarViewState(CalendarViewState(month, date))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result.failure(error)
        }
        visibleMonth.value = month
        mutableUiState.update { it.copy(visibleMonth = month, selectedDate = date, focusedEventId = navigationEvent.id) }
        return Result.success(Unit)
    }

    fun clearFocusedEvent(id: String) {
        mutableUiState.update { if (it.focusedEventId == id) it.copy(focusedEventId = null) else it }
    }

    fun showPreviousMonth() = showMonth(visibleMonth.value.minusMonths(1))

    fun showNextMonth() = showMonth(visibleMonth.value.plusMonths(1))

    fun selectDate(date: LocalDate) {
        mutableUiState.update { it.copy(selectedDate = date, focusedEventId = null) }
        persistCalendarPosition(visibleMonth.value, date)
    }

    fun refreshCalendar() {
        if (mutableUiState.value.isRefreshing) return
        val requestId = refreshRequests.value + 1
        pendingRefreshRequest = requestId
        refreshStartedAtElapsedMillis = SystemClock.elapsedRealtime()
        mutableUiState.update {
            it.copy(
                isRefreshing = true,
                focusedEventId = null,
            )
        }
        refreshRequests.value = requestId
    }

    fun createEvent(
        title: String,
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime?,
        value: Long?,
        notes: String?,
        zoneId: ZoneId = calendarTimeZones.state.value.zoneId,
        scheduleInput: CalendarScheduleInput = CalendarScheduleInput(),
        inputZoneId: ZoneId? = null,
    ) {
        viewModelScope.launch {
            val draft = buildDraft(title, date, startTime, endTime, value, notes, inputZoneId ?: zoneId, scheduleInput).getOrElse { error ->
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
        endTime: LocalTime?,
        value: Long?,
        notes: String?,
        zoneId: ZoneId = calendarTimeZones.state.value.zoneId,
        originalEvent: CalendarEvent? = null,
        scheduleInput: CalendarScheduleInput = CalendarScheduleInput.forEvent(originalEvent),
    ) {
        viewModelScope.launch {
            // Editing text/value alone must also preserve an existing DST-overlap instant.
            val unchangedSchedule = originalEvent?.let { original ->
                val oldStart = original.localStart(zoneId)
                val oldEnd = original.localEnd(zoneId)
                date == oldStart.toLocalDate() && startTime == oldStart.toLocalTime() &&
                    endTime == oldEnd.toLocalTime().takeIf { original.hasEndTime } &&
                    scheduleInput == CalendarScheduleInput.forEvent(original)
            } == true
            val built = if (unchangedSchedule) runCatching {
                require(title.isNotBlank()) { "Введите название события." }
                val original = originalEvent!!
                val schedule = if (original.seriesId != null) original.schedule?.takeIf { it.mode == CalendarTimeMode.FLOATING }?.let {
                    com.example.aiassistent1.calendar.core.domain.CalendarSchedule(CalendarTimeMode.FLOATING,
                        original.localStart(zoneId), original.localEnd(zoneId).takeIf { original.hasEndTime })
                } else original.schedule
                CalendarEventDraft(title, original.startsAtEpochMillis, original.endsAtEpochMillis,
                    value, notes?.takeIf { it.isNotBlank() }, schedule)
            } else buildDraft(title, date, startTime, endTime, value, notes, zoneId, scheduleInput)
            val draft = built.getOrElse { error ->
                showError(error)
                return@launch
            }
            updateCalendarEvent(
                CalendarEventUpdate(
                    id = id,
                    title = draft.title,
                    startsAtEpochMillis = draft.startsAtEpochMillis,
                    endsAtEpochMillis = draft.endsAtEpochMillis,
                    valueChange = value?.let(CalendarValueChange::Set) ?: CalendarValueChange.Clear,
                    notes = draft.notes,
                    clearNotes = draft.notes == null,
                    expectedRevision = originalEvent?.revision,
                    schedule = draft.schedule?.let { schedule ->
                        if (originalEvent?.seriesId == null && schedule.isRepeating)
                            schedule.copy(excludedDates = originalEvent?.schedule?.excludedDates.orEmpty()) else schedule
                    },
                    replaceSchedule = true,
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

    fun loadSeriesForEdit(event: CalendarEvent, onLoaded: (CalendarEvent) -> Unit) {
        val seriesId = event.seriesId ?: return
        viewModelScope.launch {
            getCalendarEvent(seriesId).onSuccess { series ->
                if (series == null) showError(NoSuchElementException("Серия не найдена")) else onLoaded(series)
            }.onFailure(::showError)
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
        val resolvedDate = month.atDay(minOf(mutableUiState.value.selectedDate.dayOfMonth, month.lengthOfMonth()))
        mutableUiState.update {
            it.copy(
                visibleMonth = month,
                selectedDate = resolvedDate,
                focusedEventId = null,
            )
        }
        persistCalendarPosition(month, resolvedDate)
    }

    private fun persistCalendarPosition(month: YearMonth, date: LocalDate) {
        viewModelScope.launch {
            settingsRepository.setCalendarViewState(CalendarViewState(month, date))
        }
    }

    private fun buildDraft(
        title: String,
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime?,
        value: Long?,
        notes: String?,
        zoneId: ZoneId,
        scheduleInput: CalendarScheduleInput = CalendarScheduleInput(),
    ): Result<CalendarEventDraft> = runCatching {
        require(title.isNotBlank()) { "Введите название события." }
        val localStart = LocalDateTime.of(date, startTime)
        val localEnd = endTime?.let { LocalDateTime.of(resolveCalendarEndDate(date, startTime, it), it) }
        val start = if (scheduleInput.mode == CalendarTimeMode.FLOATING) localStart.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            else CalendarTime.toEpochMillis(localStart, zoneId)
        val end = endTime?.let {
            (if (scheduleInput.mode == CalendarTimeMode.FLOATING) localEnd!!.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            else CalendarTime.toEpochMillis(localEnd!!, zoneId))
                .also { end -> require(end > start) { "Окончание события должно быть позже начала." } }
        } ?: start
        CalendarEventDraft(
            title = title,
            startsAtEpochMillis = start,
            endsAtEpochMillis = end,
            value = value,
            notes = notes?.takeIf { it.isNotBlank() },
            schedule = scheduleInput.build(localStart, localEnd, zoneId),
        )
    }

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
        const val MINIMUM_REFRESH_INDICATOR_MILLIS = 1_000L
    }

    private data class MonthRefreshRequest(
        val month: YearMonth,
        val requestId: Int,
        val zoneId: ZoneId,
    )
}

internal fun resolveCalendarEndDate(
    eventDate: LocalDate,
    startTime: LocalTime,
    endTime: LocalTime,
): LocalDate = if (endTime <= startTime) eventDate.plusDays(1) else eventDate
