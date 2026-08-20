package com.example.aiassistent1.calendar.storage.android

import com.example.aiassistent1.calendar.core.domain.CalendarEvent
import com.example.aiassistent1.calendar.core.domain.CalendarEventDraft
import com.example.aiassistent1.calendar.core.domain.CalendarEventRepository
import com.example.aiassistent1.calendar.core.domain.CalendarEventUpdate
import com.example.aiassistent1.calendar.storage.android.local.CalendarEventDao
import com.example.aiassistent1.calendar.storage.android.local.CalendarEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class RoomCalendarEventRepository(
    private val dao: CalendarEventDao,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : CalendarEventRepository {

    override suspend fun create(draft: CalendarEventDraft): Result<CalendarEvent> = runCatching {
        validate(draft.title, draft.startsAtEpochMillis, draft.endsAtEpochMillis)
        val now = nowEpochMillis()
        val event = CalendarEventEntity(
            id = newId(),
            title = draft.title.trim(),
            startsAtEpochMillis = draft.startsAtEpochMillis,
            endsAtEpochMillis = draft.endsAtEpochMillis,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        dao.insert(event)
        event.toDomain()
    }

    override suspend fun getById(id: String): Result<CalendarEvent?> = runCatching {
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

    override suspend fun update(update: CalendarEventUpdate): Result<CalendarEvent> = runCatching {
        require(update.id.isNotBlank()) { "Event id must not be blank." }
        validate(update.title, update.startsAtEpochMillis, update.endsAtEpochMillis)
        val existing = dao.getById(update.id)
            ?: throw NoSuchElementException("Calendar event '${update.id}' does not exist.")
        val changed = existing.copy(
            title = update.title.trim(),
            startsAtEpochMillis = update.startsAtEpochMillis,
            endsAtEpochMillis = update.endsAtEpochMillis,
            updatedAtEpochMillis = nowEpochMillis(),
        )
        check(dao.update(changed) == 1) { "Calendar event '${update.id}' was not updated." }
        changed.toDomain()
    }

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        require(id.isNotBlank()) { "Event id must not be blank." }
        if (dao.deleteById(id) != 1) {
            throw NoSuchElementException("Calendar event '$id' does not exist.")
        }
    }

    override suspend fun search(
        query: String,
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): Result<List<CalendarEvent>> = runCatching {
        validateRange(rangeStartEpochMillis, rangeEndEpochMillis)
        dao.search(
            query = query.trim(),
            rangeStartEpochMillis = rangeStartEpochMillis,
            rangeEndEpochMillis = rangeEndEpochMillis,
        ).map(CalendarEventEntity::toDomain)
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
)
