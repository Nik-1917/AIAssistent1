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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class RoomCalendarEventRepository(
    private val dao: CalendarEventDao,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
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
        )
        dao.insert(event)
        event.toDomain()
    }

    override suspend fun getById(id: String): Result<CalendarEvent?> = calendarResult {
        require(id.isNotBlank()) { "Event id must not be blank." }
        dao.getById(id)?.toDomain()
    }

    override fun observeInRange(
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): Flow<List<CalendarEvent>> {
        validateRange(rangeStartEpochMillis, rangeEndEpochMillis)
        return dao.observeInRange(rangeStartEpochMillis, rangeEndEpochMillis)
            .map { events -> events.map(CalendarEventEntity::toDomain) }
    }

    override suspend fun update(update: CalendarEventUpdate): Result<CalendarEvent> = calendarResult {
        require(update.id.isNotBlank()) { "Event id must not be blank." }
        validate(update.title, update.startsAtEpochMillis, update.endsAtEpochMillis)
        dao.updateChecked(update, nowEpochMillis()).toDomain()
    }

    override suspend fun getReceipt(requestId: String): Result<CalendarReceipt?> = calendarResult {
        dao.getReceipt(requestId)?.let { CalendarReceipt(it.requestId, it.kind, it.eventId, it.title) }
    }

    override suspend fun commit(requestId: String, mutation: CalendarMutation): Result<CalendarReceipt> = calendarResult {
        dao.commitMutation(requestId, mutation, newId(), nowEpochMillis()).let {
            CalendarReceipt(it.requestId, it.kind, it.eventId, it.title)
        }
    }

    override suspend fun delete(id: String): Result<Unit> = calendarResult {
        require(id.isNotBlank()) { "Event id must not be blank." }
        if (dao.deleteById(id) != 1) {
            throw NoSuchElementException("Calendar event '$id' does not exist.")
        }
    }

    override suspend fun search(
        query: String,
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): Result<List<CalendarEvent>> = calendarResult {
        validateRange(rangeStartEpochMillis, rangeEndEpochMillis)
        val normalizedQuery = query.trim()
        dao.findInRange(
            rangeStartEpochMillis = rangeStartEpochMillis,
            rangeEndEpochMillis = rangeEndEpochMillis,
        ).map(CalendarEventEntity::toDomain)
            .filter { event ->
                normalizedQuery.isEmpty() || event.title.contains(normalizedQuery, ignoreCase = true)
            }
    }

    override suspend fun findForUpdate(
        query: String,
        rangeStartEpochMillis: Long?,
        rangeEndEpochMillis: Long?,
    ): Result<List<CalendarEvent>> = calendarResult {
        require(query.isNotBlank()) { "Event query must not be blank." }
        require((rangeStartEpochMillis == null) == (rangeEndEpochMillis == null)) {
            "Both target range boundaries must be supplied together."
        }
        if (rangeStartEpochMillis != null && rangeEndEpochMillis != null) {
            validateRange(rangeStartEpochMillis, rangeEndEpochMillis)
        }
        val normalizedQuery = query.trim()
        dao.findForUpdateCandidates(
            rangeStartEpochMillis = rangeStartEpochMillis,
            rangeEndEpochMillis = rangeEndEpochMillis,
        ).map(CalendarEventEntity::toDomain)
            .filter { event -> event.title.contains(normalizedQuery, ignoreCase = true) }
    }

    override suspend fun getLastCreated(): Result<CalendarEvent?> = calendarResult {
        dao.getLastCreated()?.toDomain()
    }

    override suspend fun getLastInRange(
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): Result<CalendarEvent?> = calendarResult {
        validateRange(rangeStartEpochMillis, rangeEndEpochMillis)
        dao.getLastInRange(rangeStartEpochMillis, rangeEndEpochMillis)?.toDomain()
    }

    private fun validate(title: String, startsAtEpochMillis: Long, endsAtEpochMillis: Long) {
        require(title.isNotBlank()) { "Event title must not be blank." }
        validateRange(startsAtEpochMillis, endsAtEpochMillis)
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
)
