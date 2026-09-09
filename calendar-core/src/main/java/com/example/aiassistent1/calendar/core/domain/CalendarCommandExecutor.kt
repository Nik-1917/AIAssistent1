package com.example.aiassistent1.calendar.core.domain

import kotlinx.coroutines.CancellationException
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Cancellation is control flow, never a failed command which can be retried as a mutation. */
 suspend fun <T> calendarResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

sealed interface CalendarCommand {
    data class Add(val title: String?, val date: LocalDate, val time: LocalTime?, val durationMinutes: Int?, val value: Long? = null) : CalendarCommand
    data class Search(val query: String?, val range: CalendarRange?) : CalendarCommand
    data class Sum(val query: String?, val range: CalendarRange?) : CalendarCommand
    data class Update(val target: CalendarUpdateTarget?, val changes: CalendarEventChanges) : CalendarCommand
    data class Delete(val target: CalendarUpdateTarget?) : CalendarCommand
}

data class CalendarRange(val start: Long, val end: Long) {
    init { require(start < end) { "Начало периода должно предшествовать концу" } }
}

sealed interface CalendarMutation {
    data class Create(val draft: CalendarEventDraft) : CalendarMutation
    data class Update(val update: CalendarEventUpdate) : CalendarMutation
    data class Delete(val id: String, val expectedRevision: Long) : CalendarMutation
}

data class CalendarReceipt(val requestId: String, val kind: String, val eventId: String, val title: String)
class CalendarConflictException : IllegalStateException("Событие изменилось после выбора. Повторите запрос с актуальными данными.")

enum class MissingCalendarField { TITLE, TIME, DURATION, VALUE, QUERY, RANGE, TARGET }

sealed interface CalendarCommandResult {
    data class NeedsFields(val command: CalendarCommand, val fields: List<MissingCalendarField>) : CalendarCommandResult
    data class AddDraft(val command: CalendarCommand.Add) : CalendarCommandResult
    data class UpdateDraft(val command: CalendarCommand.Update, val event: CalendarEvent) : CalendarCommandResult
    data class Selection(val command: CalendarCommand, val candidates: List<CalendarEvent>) : CalendarCommandResult
    data class Completed(val receipt: CalendarReceipt) : CalendarCommandResult
    data class Found(val events: List<CalendarEvent>) : CalendarCommandResult
    /** BigInteger avoids both floating-point rounding and overflow of a sum of Long values. */
    data class Sum(val total: BigInteger, val matchedCount: Int, val valueCount: Int) : CalendarCommandResult
    data object NotFound : CalendarCommandResult
}

/** One execution path for all V12.4 calendar commands. reply belongs to the presentation layer. */
class CalendarCommandExecutor(
    private val repository: CalendarEventRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun execute(
        command: CalendarCommand,
        requestId: String,
        confirmed: Boolean = false,
        selectedEvent: CalendarEvent? = null,
    ): Result<CalendarCommandResult> = calendarResult {
        require(requestId.isNotBlank()) { "Отсутствует идентификатор запроса" }
        // Consult receipt even if a regenerated response changes the intent or target.
        repository.getReceipt(requestId).getOrThrow()?.let { return@calendarResult CalendarCommandResult.Completed(it) }
        when (command) {
            is CalendarCommand.Add -> {
                require(command.title == null || command.title.isNotBlank()) { "Пустое название события" }
                require(command.durationMinutes == null || command.durationMinutes > 0) { "Некорректная длительность" }
                val missing = buildList {
                    if (command.title == null) add(MissingCalendarField.TITLE)
                    if (command.time == null) add(MissingCalendarField.TIME)
                    if (command.durationMinutes == null) add(MissingCalendarField.DURATION)
                    if (command.value == null) add(MissingCalendarField.VALUE)
                }
                if (missing.isNotEmpty()) CalendarCommandResult.NeedsFields(command, missing)
                else if (!confirmed) CalendarCommandResult.AddDraft(command)
                else {
                    val start = CalendarTime.toEpochMillis(LocalDateTime.of(command.date, command.time!!), zoneId)
                    val draft = CalendarEventDraft(command.title!!, start,
                        Math.addExact(start, Math.multiplyExact(command.durationMinutes!!.toLong(), 60_000L)), command.value)
                    CalendarCommandResult.Completed(repository.commit(requestId, CalendarMutation.Create(draft)).getOrThrow())
                }
            }
            is CalendarCommand.Search -> {
                val missing = buildList {
                    if (command.query == null) add(MissingCalendarField.QUERY)
                    if (command.range == null) add(MissingCalendarField.RANGE)
                }
                if (missing.isNotEmpty()) CalendarCommandResult.NeedsFields(command, missing)
                else CalendarCommandResult.Found(repository.search(command.query!!, command.range!!.start, command.range.end).getOrThrow())
            }
            is CalendarCommand.Sum -> {
                if (command.range == null) CalendarCommandResult.NeedsFields(command, listOf(MissingCalendarField.RANGE))
                else {
                    val events = repository.search(command.query.orEmpty(), command.range.start, command.range.end).getOrThrow()
                    val values = events.mapNotNull { it.value }
                    CalendarCommandResult.Sum(values.fold(BigInteger.ZERO) { sum, value -> sum + BigInteger.valueOf(value) }, events.size, values.size)
                }
            }
            is CalendarCommand.Update -> mutateTarget(command, command.target, requestId, confirmed, selectedEvent)
            is CalendarCommand.Delete -> mutateTarget(command, command.target, requestId, confirmed, selectedEvent)
        }
    }

    private suspend fun mutateTarget(command: CalendarCommand, target: CalendarUpdateTarget?, requestId: String,
        confirmed: Boolean, selected: CalendarEvent?): CalendarCommandResult {
        if (target == null) return CalendarCommandResult.NeedsFields(command, listOf(MissingCalendarField.TARGET))
        val event = selected ?: when (val resolution = ResolveCalendarUpdateTargetUseCase(repository)(target).getOrThrow()) {
            CalendarUpdateTargetResolution.NotFound -> return CalendarCommandResult.NotFound
            is CalendarUpdateTargetResolution.Ambiguous -> return CalendarCommandResult.Selection(command, resolution.candidates)
            is CalendarUpdateTargetResolution.Resolved -> resolution.event
        }
        return when (command) {
            is CalendarCommand.Update -> {
                if (!confirmed || command.changes.isEmpty) CalendarCommandResult.UpdateDraft(command, event)
                else {
                    val update = PrepareCalendarEventUpdateUseCase(zoneId)(event, command.changes).getOrThrow()
                    CalendarCommandResult.Completed(repository.commit(requestId, CalendarMutation.Update(update)).getOrThrow())
                }
            }
            is CalendarCommand.Delete -> CalendarCommandResult.Completed(
                repository.commit(requestId, CalendarMutation.Delete(event.id, event.revision)).getOrThrow())
            else -> error("Not a mutation")
        }
    }
}
