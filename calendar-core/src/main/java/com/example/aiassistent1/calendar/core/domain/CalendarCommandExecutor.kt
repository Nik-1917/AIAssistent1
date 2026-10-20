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
    data class Add(val title: String?, val date: LocalDate, val time: LocalTime?, val durationMinutes: Int?, val value: Long? = null, val notes: String? = null, val endsAt: LocalDateTime? = null,
        val schedule: CalendarSchedule? = null) : CalendarCommand
    data class Search(val query: String?, val range: CalendarRange?) : CalendarCommand
    data class Sum(val query: String?, val range: CalendarRange?) : CalendarCommand
}

data class CalendarRange(val start: Long, val end: Long) {
    init { require(start < end) { "Начало периода должно предшествовать концу" } }
}

sealed interface CalendarMutation {
    data class Create(val draft: CalendarEventDraft) : CalendarMutation
}

data class CalendarReceipt(val requestId: String, val kind: String, val eventId: String, val title: String, val notes: String? = null)
class CalendarConflictException : IllegalStateException("Событие изменилось после выбора. Повторите запрос с актуальными данными.")

enum class MissingCalendarField { TITLE, TIME, QUERY, RANGE }

sealed interface CalendarCommandResult {
    data class NeedsFields(val command: CalendarCommand, val fields: List<MissingCalendarField>) : CalendarCommandResult
    data class AddDraft(val command: CalendarCommand.Add) : CalendarCommandResult
    data class Completed(val receipt: CalendarReceipt) : CalendarCommandResult
    data class Found(val events: List<CalendarEvent>) : CalendarCommandResult
    /** BigInteger avoids both floating-point rounding and overflow of a sum of Long values. */
    data class Sum(val total: BigInteger, val matchedCount: Int, val valueCount: Int) : CalendarCommandResult
}

/** One execution path for V12.67 calendar commands. reply belongs to the presentation layer. */
class CalendarCommandExecutor(
    private val repository: CalendarEventRepository,
    private val zoneId: ZoneId? = null,
) {
    suspend fun findReceipt(requestId: String): Result<CalendarReceipt?> = calendarResult {
        repository.getReceipt(requestId).getOrThrow()
    }

    suspend fun execute(
        command: CalendarCommand,
        requestId: String,
        confirmed: Boolean = false,
        timeZone: ZoneId = zoneId ?: ZoneId.systemDefault(),
    ): Result<CalendarCommandResult> = calendarResult {
        require(requestId.isNotBlank()) { "Отсутствует идентификатор запроса" }
        // Consult receipt even if a regenerated response changes the intent or fields.
        repository.getReceipt(requestId).getOrThrow()?.let { return@calendarResult CalendarCommandResult.Completed(it) }
        when (command) {
            is CalendarCommand.Add -> {
                require(command.title == null || command.title.isNotBlank()) { "Пустое название события" }
                require(command.durationMinutes == null || command.durationMinutes > 0) { "Некорректная длительность" }
                val schedule = command.schedule
                if (schedule != null) {
                    require(command.date == schedule.start.toLocalDate() && command.time == schedule.start.toLocalTime()) { "Время расписания не совпадает с событием." }
                    require(command.endsAt == schedule.end) { "Окончание расписания не совпадает с событием." }
                }
                val duration = if (schedule?.mode == CalendarTimeMode.FLOATING) {
                    schedule.end?.let { java.time.Duration.between(schedule.start, it).toMinutes().also { minutes ->
                        require(minutes <= Int.MAX_VALUE && (command.durationMinutes == null || minutes == command.durationMinutes.toLong())) { "Проверьте длительность и окончание." }
                    }.toInt() } ?: command.durationMinutes
                } else CalendarTime.durationMinutes(command.time?.let { LocalDateTime.of(command.date, it) },
                    command.endsAt, command.durationMinutes, timeZone)
                val resolved = command.copy(durationMinutes = duration)
                val missing = buildList {
                    if (command.title == null) add(MissingCalendarField.TITLE)
                    if (command.time == null) add(MissingCalendarField.TIME)
                }
                if (missing.isNotEmpty()) CalendarCommandResult.NeedsFields(resolved, missing)
                else if (!confirmed) CalendarCommandResult.AddDraft(resolved)
                else {
                    val projectionZone = if (schedule?.mode == CalendarTimeMode.FLOATING) java.time.ZoneOffset.UTC else schedule?.zoneId?.let(ZoneId::of) ?: timeZone
                    val start = if (schedule != null) schedule.start.atZone(projectionZone).toInstant().toEpochMilli()
                        else CalendarTime.toEpochMillis(LocalDateTime.of(command.date, command.time!!), timeZone)
                    val draft = CalendarEventDraft(command.title!!, start,
                        schedule?.end?.let { it.atZone(projectionZone).toInstant().toEpochMilli() }
                            ?: command.endsAt?.let { CalendarTime.toEpochMillis(it, timeZone) }
                            ?: duration?.let { Math.addExact(start, Math.multiplyExact(it.toLong(), 60_000L)) }
                            ?: start, command.value, command.notes, schedule)
                    CalendarCommandResult.Completed(repository.commit(requestId, CalendarMutation.Create(draft)).getOrThrow())
                }
            }
            is CalendarCommand.Search -> {
                val missing = buildList {
                    if (command.query == null && command.range == null) add(MissingCalendarField.QUERY)
                }
                if (missing.isNotEmpty()) CalendarCommandResult.NeedsFields(command, missing)
                else {
                    val range = command.range ?: CalendarRange(Long.MIN_VALUE + 1, Long.MAX_VALUE)
                    CalendarCommandResult.Found(repository.search(command.query.orEmpty(), range.start, range.end, timeZone).getOrThrow())
                }
            }
            is CalendarCommand.Sum -> {
                if (command.range == null) CalendarCommandResult.NeedsFields(command, listOf(MissingCalendarField.RANGE))
                else {
                    val events = repository.search(command.query.orEmpty(), command.range.start, command.range.end, timeZone).getOrThrow()
                    val values = events.mapNotNull { it.value }
                    CalendarCommandResult.Sum(values.fold(BigInteger.ZERO) { sum, value -> sum + BigInteger.valueOf(value) }, events.size, values.size)
                }
            }
        }
    }
}
