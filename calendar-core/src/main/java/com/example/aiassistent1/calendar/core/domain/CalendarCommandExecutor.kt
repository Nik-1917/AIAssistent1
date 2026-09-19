package com.example.aiassistent1.calendar.core.domain

import kotlinx.coroutines.CancellationException
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
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
    data class Add(val title: String?, val date: LocalDate, val time: LocalTime?, val durationMinutes: Int?, val value: Long? = null, val notes: String? = null, val endsAt: LocalDateTime? = null) : CalendarCommand
    data class Search(val query: String?, val range: CalendarRange?) : CalendarCommand
    data class Sum(val query: String?, val range: CalendarRange?) : CalendarCommand
    data class Update(val target: CalendarUpdateTarget?, val changes: CalendarEventChanges) : CalendarCommand
    data class Delete(val target: CalendarDeleteTarget) : CalendarCommand
}

data class CalendarRange(val start: Long, val end: Long) {
    init { require(start < end) { "Начало периода должно предшествовать концу" } }
}

sealed interface CalendarMutation {
    data class Create(val draft: CalendarEventDraft) : CalendarMutation
    data class Update(val update: CalendarEventUpdate) : CalendarMutation
    data class Delete(val id: String, val expectedRevision: Long) : CalendarMutation
}

data class CalendarReceipt(val requestId: String, val kind: String, val eventId: String, val title: String, val notes: String? = null)
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
    private val clock: Clock = Clock.system(zoneId),
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
                val duration = CalendarTime.durationMinutes(command.time?.let { LocalDateTime.of(command.date, it) },
                    command.endsAt, command.durationMinutes, zoneId)
                val resolved = command.copy(durationMinutes = duration)
                val missing = buildList {
                    if (command.title == null) add(MissingCalendarField.TITLE)
                    if (command.time == null) add(MissingCalendarField.TIME)
                    if (duration == null && command.endsAt == null) add(MissingCalendarField.DURATION)
                    if (command.value == null) add(MissingCalendarField.VALUE)
                }
                if (missing.isNotEmpty()) CalendarCommandResult.NeedsFields(resolved, missing)
                else if (!confirmed) CalendarCommandResult.AddDraft(resolved)
                else {
                    val start = CalendarTime.toEpochMillis(LocalDateTime.of(command.date, command.time!!), zoneId)
                    val draft = CalendarEventDraft(command.title!!, start,
                        command.endsAt?.let { CalendarTime.toEpochMillis(it, zoneId) }
                            ?: Math.addExact(start, Math.multiplyExact(duration!!.toLong(), 60_000L)), command.value, command.notes)
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
                    CalendarCommandResult.Found(repository.search(command.query.orEmpty(), range.start, range.end).getOrThrow())
                }
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
            is CalendarCommand.Delete -> deleteTarget(command, requestId, confirmed, selectedEvent)
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
            else -> error("Not a mutation")
        }
    }

    private suspend fun deleteTarget(command: CalendarCommand.Delete, requestId: String,
        confirmed: Boolean, selected: CalendarEvent?): CalendarCommandResult {
        val target = command.target
        // Explicit last-event commands retain their existing immediate-deletion semantics.
        if (target.useLastCreated || target.useLastInRange) {
            val event = if (target.useLastCreated) repository.getLastCreated().getOrThrow()
                else repository.getLastInRange(target.range!!.start, target.range.end).getOrThrow()
            return event?.let { commitDelete(it, requestId) } ?: CalendarCommandResult.NotFound
        }

        val range = target.range ?: LocalDate.now(clock.withZone(zoneId)).let { today ->
            CalendarRange(today.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli())
        }
        val candidates = repository.search(target.query.orEmpty(), range.start, range.end).getOrThrow()
        if (selected != null) {
            require(confirmed) { "Выберите событие для удаления" }
            // Keep the selected revision: commit rejects changes made after the card was shown.
            if (candidates.none { it.id == selected.id }) throw CalendarConflictException()
            return commitDelete(selected, requestId)
        }

        val start = Instant.ofEpochMilli(range.start).atZone(zoneId)
        val end = Instant.ofEpochMilli(range.end).atZone(zoneId)
        val wholeDays = start.toLocalTime() == LocalTime.MIDNIGHT &&
            end.toLocalTime() == LocalTime.MIDNIGHT && end.toLocalDate() > start.toLocalDate()
        val exact = if (target.query != null && target.range != null && !wholeDays) {
            candidates.filter { event ->
                event.title.trim().equals(target.query.trim(), ignoreCase = true) &&
                    Math.floorDiv(event.startsAtEpochMillis, 60_000L) == Math.floorDiv(range.start, 60_000L)
            }
        } else emptyList()
        if (exact.size == 1) return commitDelete(exact.single(), requestId)

        // Freeze today's range in the card so a later selection cannot move to another day.
        return CalendarCommandResult.Selection(command.copy(target = target.copy(range = range)),
            if (exact.isNotEmpty()) exact else candidates)
    }

    private suspend fun commitDelete(event: CalendarEvent, requestId: String): CalendarCommandResult.Completed =
        CalendarCommandResult.Completed(
            repository.commit(requestId, CalendarMutation.Delete(event.id, event.revision)).getOrThrow(),
        )
}
