package com.example.aiassistent1.calendar.storage.android

import com.example.aiassistent1.calendar.core.domain.CalendarEvent
import com.example.aiassistent1.calendar.core.domain.CalendarEventDraft
import com.example.aiassistent1.calendar.core.domain.CalendarEventRepository
import com.example.aiassistent1.calendar.core.domain.CalendarEventUpdate
import com.example.aiassistent1.calendar.storage.android.local.CalendarEventDao
import com.example.aiassistent1.calendar.storage.android.local.CalendarEventEntity
import com.example.aiassistent1.calendar.core.domain.CalendarMutation
import com.example.aiassistent1.calendar.core.domain.CalendarReceipt
import com.example.aiassistent1.calendar.core.domain.calendarResult
import com.example.aiassistent1.calendar.core.domain.CalendarSchedule
import com.example.aiassistent1.calendar.core.domain.CalendarRecurrence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import java.util.UUID
import java.time.ZoneId

class RoomCalendarEventRepository(
    private val dao: CalendarEventDao,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val deviceZone: () -> ZoneId = ZoneId::systemDefault,
) : CalendarEventRepository {

    override suspend fun create(draft: CalendarEventDraft): Result<CalendarEvent> = calendarResult {
        validate(draft.title, draft.startsAtEpochMillis, draft.endsAtEpochMillis)
        val now = nowEpochMillis()
        val event = CalendarEventEntity(
            id = newId(),
            title = draft.title.trim(),
            startsAtEpochMillis = draft.startsAtEpochMillis,
            endsAtEpochMillis = draft.endsAtEpochMillis,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            value = draft.value,
            notes = draft.notes?.takeIf { it.isNotBlank() },
            scheduleData = draft.schedule?.encode(),
        )
        dao.insert(event)
        event.toDomain().atAnchor(deviceZone())
    }

    override suspend fun getById(id: String): Result<CalendarEvent?> = calendarResult {
        require(id.isNotBlank()) { "Event id must not be blank." }
        dao.getById(id)?.toDomain()?.atAnchor(deviceZone()) ?: CalendarSchedule.parseOccurrenceId(id)?.let { (parentId, date) ->
            val parent = dao.getById(parentId)?.toDomain() ?: return@let null
            parent.schedule?.project(parent, date, deviceZone())
        }
    }

    override fun observeByIds(ids: Set<String>, zoneId: ZoneId): Flow<Map<String, CalendarEvent?>> {
        require(ids.all { it.isNotBlank() }) { "Event id must not be blank." }
        if (ids.isEmpty()) return flowOf(emptyMap())
        // Each occurrence and its parent share one query snapshot; stay below SQLite bind limits.
        val batches = ids.toList().chunked(400).map { batch ->
            val rowIds = (batch + batch.mapNotNull { CalendarSchedule.parseOccurrenceId(it)?.first }).distinct()
            dao.observeByIds(rowIds).map { rows ->
                val events = rows.associate { it.id to it.toDomain() }
                batch.associateWith { id ->
                    events[id]?.atAnchor(zoneId) ?: CalendarSchedule.parseOccurrenceId(id)?.let { (parentId, date) ->
                        val parent = events[parentId] ?: return@let null
                        parent.schedule?.project(parent, date, zoneId)
                    }
                }
            }
        }
        return combine(batches) { snapshots -> snapshots.fold(emptyMap<String, CalendarEvent?>()) { all, batch -> all + batch } }
            .distinctUntilChanged()
    }

    override fun observeInRange(
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): Flow<List<CalendarEvent>> = observeInRange(rangeStartEpochMillis, rangeEndEpochMillis, deviceZone())

    override fun observeInRange(rangeStartEpochMillis: Long, rangeEndEpochMillis: Long, zoneId: ZoneId): Flow<List<CalendarEvent>> {
        validateRange(rangeStartEpochMillis, rangeEndEpochMillis)
        return dao.observeInRange(rangeStartEpochMillis, rangeEndEpochMillis)
            .map { events -> materialize(events, rangeStartEpochMillis, rangeEndEpochMillis, zoneId) }
    }

    override suspend fun update(update: CalendarEventUpdate): Result<CalendarEvent> = calendarResult {
        require(update.id.isNotBlank()) { "Event id must not be blank." }
        validate(update.title, update.startsAtEpochMillis, update.endsAtEpochMillis)
        dao.updateChecked(update, nowEpochMillis()).toDomain().atAnchor(deviceZone())
    }

    override suspend fun getReceipt(requestId: String): Result<CalendarReceipt?> = calendarResult {
        dao.getReceipt(requestId)?.let { CalendarReceipt(it.requestId, it.kind, it.eventId, it.title, it.notes) }
    }

    override suspend fun commit(requestId: String, mutation: CalendarMutation): Result<CalendarReceipt> = calendarResult {
        dao.commitMutation(requestId, mutation, newId(), nowEpochMillis()).let {
            CalendarReceipt(it.requestId, it.kind, it.eventId, it.title, it.notes)
        }
    }

    override suspend fun delete(id: String): Result<Unit> = calendarResult {
        require(id.isNotBlank()) { "Event id must not be blank." }
        dao.deleteEventOrOccurrence(id, nowEpochMillis())
    }

    override suspend fun search(
        query: String,
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): Result<List<CalendarEvent>> = search(query, rangeStartEpochMillis, rangeEndEpochMillis, deviceZone())

    override suspend fun search(query: String, rangeStartEpochMillis: Long, rangeEndEpochMillis: Long, zoneId: ZoneId): Result<List<CalendarEvent>> = calendarResult {
        validateRange(rangeStartEpochMillis, rangeEndEpochMillis)
        val normalizedQuery = query.trim()
        materialize(dao.findInRange(
            rangeStartEpochMillis = rangeStartEpochMillis,
            rangeEndEpochMillis = rangeEndEpochMillis,
        ), rangeStartEpochMillis, rangeEndEpochMillis, zoneId)
            .filter { event ->
                normalizedQuery.isEmpty() || event.title.contains(normalizedQuery, ignoreCase = true)
            }
    }

    private fun materialize(rows: List<CalendarEventEntity>, start: Long, end: Long, zone: ZoneId): List<CalendarEvent> =
        rows.flatMap { row ->
            val event = row.toDomain()
            val schedule = event.schedule
            when {
                schedule == null -> listOf(event)
                // A query without a period returns one definition per series, never an infinite expansion.
                start < -62_135_596_800_000L || end > 253_402_214_400_000L -> listOf(event.atAnchor(zone))
                else -> schedule.expand(event, start, end, zone)
            }
        }.sortedWith(compareBy<CalendarEvent> { it.startsAtEpochMillis }.thenBy { it.id })

    private fun validate(title: String, startsAtEpochMillis: Long, endsAtEpochMillis: Long) {
        require(title.isNotBlank()) { "Event title must not be blank." }
        require(startsAtEpochMillis <= endsAtEpochMillis) {
            "The event end must not be earlier than its start."
        }
    }

    private fun validateRange(startEpochMillis: Long, endEpochMillis: Long) {
        require(startEpochMillis < endEpochMillis) {
            "The event start must be earlier than its end."
        }
    }
}

private fun CalendarEventEntity.toDomain() = CalendarEvent(
    id = id,
    title = title,
    startsAtEpochMillis = startsAtEpochMillis,
    endsAtEpochMillis = endsAtEpochMillis,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    value = value,
    revision = revision,
    notes = notes,
    schedule = scheduleData?.let(CalendarSchedule::decode),
    parentSeriesId = parentSeriesId,
)

private fun CalendarEvent.atAnchor(zone: ZoneId): CalendarEvent = schedule?.let {
    it.copy(recurrence = CalendarRecurrence(), excludedDates = emptySet()).project(this, it.start.toLocalDate(), zone)
} ?: this
