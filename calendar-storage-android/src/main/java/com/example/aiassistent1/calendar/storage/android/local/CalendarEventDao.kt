package com.example.aiassistent1.calendar.storage.android.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import com.example.aiassistent1.calendar.core.domain.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CalendarEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(event: CalendarEventEntity)

    @Query("SELECT * FROM calendar_events WHERE id = :id")
    abstract suspend fun getById(id: String): CalendarEventEntity?

    @Query(
        """
        SELECT * FROM calendar_events
        WHERE scheduleData IS NOT NULL OR (startsAtEpochMillis < :rangeEndEpochMillis
          AND (endsAtEpochMillis > :rangeStartEpochMillis
               OR (endsAtEpochMillis = startsAtEpochMillis AND startsAtEpochMillis >= :rangeStartEpochMillis)))
        ORDER BY startsAtEpochMillis ASC, id ASC
        """,
    )
    abstract fun observeInRange(
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): Flow<List<CalendarEventEntity>>

    @Update
    abstract suspend fun update(event: CalendarEventEntity): Int

    @Query("DELETE FROM calendar_events WHERE id = :id")
    abstract suspend fun deleteById(id: String): Int

    @Query("DELETE FROM calendar_events WHERE parentSeriesId = :id")
    abstract suspend fun deleteChildren(id: String)

    @Query(
        """
        SELECT * FROM calendar_events
        WHERE scheduleData IS NOT NULL OR (startsAtEpochMillis < :rangeEndEpochMillis
          AND (endsAtEpochMillis > :rangeStartEpochMillis
               OR (endsAtEpochMillis = startsAtEpochMillis AND startsAtEpochMillis >= :rangeStartEpochMillis)))
        ORDER BY startsAtEpochMillis ASC, id ASC
        """,
    )
    abstract suspend fun findInRange(
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): List<CalendarEventEntity>

    @Query("SELECT * FROM calendar_command_receipts WHERE requestId = :requestId")
    abstract suspend fun getReceipt(requestId: String): CalendarReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertReceipt(receipt: CalendarReceiptEntity)

    @Transaction
    open suspend fun updateChecked(update: CalendarEventUpdate, now: Long): CalendarEventEntity {
        require(update.title.isNotBlank() && update.startsAtEpochMillis <= update.endsAtEpochMillis)
        val stored = getById(update.id)
        if (stored == null) return updateOccurrence(update, now)
        val existing = stored
        if (update.expectedRevision != null && existing.revision != update.expectedRevision) throw CalendarConflictException()
        val keepSchedule = existing.scheduleData != null && !update.replaceSchedule
        val schedule = updatedSchedule(existing, update)
        require(existing.parentSeriesId == null || schedule?.isRepeating != true) { "Для отдельного повторения выберите «Один раз»." }
        val changed = existing.copy(title = update.title.trim(), startsAtEpochMillis = if (keepSchedule) existing.startsAtEpochMillis else update.startsAtEpochMillis,
            endsAtEpochMillis = if (keepSchedule) existing.endsAtEpochMillis else update.endsAtEpochMillis, value = update.valueChange.applyTo(existing.value),
            notes = if (update.clearNotes) null else update.notes?.takeIf { it.isNotBlank() } ?: existing.notes,
            scheduleData = schedule?.encode(),
            updatedAtEpochMillis = now, revision = Math.addExact(existing.revision, 1))
        check(update(changed) == 1) { "Событие не обновлено" }
        return changed
    }

    private fun updatedSchedule(existing: CalendarEventEntity, change: CalendarEventUpdate): CalendarSchedule? {
        if (change.replaceSchedule) return change.schedule
        // An epoch-only caller cannot reinterpret floating wall time or change a series rule.
        return existing.scheduleData?.let(CalendarSchedule::decode)
    }

    private suspend fun updateOccurrence(change: CalendarEventUpdate, now: Long): CalendarEventEntity {
        val (seriesId, date) = CalendarSchedule.parseOccurrenceId(change.id) ?: throw NoSuchElementException("Событие не найдено")
        val parent = getById(seriesId) ?: throw NoSuchElementException("Серия не найдена")
        if (change.expectedRevision != null && parent.revision != change.expectedRevision) throw CalendarConflictException()
        val schedule = parent.scheduleData?.let(CalendarSchedule::decode) ?: throw NoSuchElementException("Серия не найдена")
        require(schedule.isRepeating && schedule.recurrence.includes(schedule.start.toLocalDate(), date) && date !in schedule.excludedDates) { "Повторение уже изменено или удалено." }
        val replacementSchedule = if (change.replaceSchedule) change.schedule else if (schedule.mode == CalendarTimeMode.FLOATING) {
            CalendarSchedule(CalendarTimeMode.FLOATING, schedule.localStart(date), schedule.localEnd(date))
        } else null
        require(replacementSchedule?.isRepeating != true) { "Для отдельного повторения выберите «Один раз»." }
        val replacement = parent.copy(id = change.id, title = change.title.trim(), startsAtEpochMillis = change.startsAtEpochMillis,
            endsAtEpochMillis = change.endsAtEpochMillis, value = change.valueChange.applyTo(parent.value),
            notes = if (change.clearNotes) null else change.notes?.takeIf { it.isNotBlank() } ?: parent.notes,
            scheduleData = replacementSchedule?.encode(), parentSeriesId = seriesId, createdAtEpochMillis = now, updatedAtEpochMillis = now,
            revision = Math.addExact(parent.revision, 1))
        check(update(parent.copy(scheduleData = schedule.copy(excludedDates = schedule.excludedDates + date).encode(),
            revision = Math.addExact(parent.revision, 1), updatedAtEpochMillis = now)) == 1)
        insert(replacement)
        return replacement
    }

    @Transaction
    open suspend fun deleteEventOrOccurrence(id: String, now: Long) {
        val stored = getById(id)
        if (stored != null) {
            deleteChildren(id)
            check(deleteById(id) == 1)
            return
        }
        val (seriesId, date) = CalendarSchedule.parseOccurrenceId(id) ?: throw NoSuchElementException("Событие не найдено")
        val parent = getById(seriesId) ?: throw NoSuchElementException("Серия не найдена")
        val schedule = parent.scheduleData?.let(CalendarSchedule::decode) ?: throw NoSuchElementException("Серия не найдена")
        require(schedule.isRepeating && schedule.recurrence.includes(schedule.start.toLocalDate(), date) && date !in schedule.excludedDates) { "Повторение уже удалено." }
        check(update(parent.copy(scheduleData = schedule.copy(excludedDates = schedule.excludedDates + date).encode(),
            revision = Math.addExact(parent.revision, 1), updatedAtEpochMillis = now)) == 1)
    }

    @Transaction
    open suspend fun commitMutation(requestId: String, mutation: CalendarMutation, newId: String, now: Long): CalendarReceiptEntity {
        require(requestId.isNotBlank())
        getReceipt(requestId)?.let { return it }
        val event: CalendarEventEntity
        val kind: String
        when (mutation) {
            is CalendarMutation.Create -> {
                val draft = mutation.draft
                require(draft.title.isNotBlank() && draft.startsAtEpochMillis <= draft.endsAtEpochMillis)
                event = CalendarEventEntity(newId, draft.title.trim(), draft.startsAtEpochMillis,
                    draft.endsAtEpochMillis, now, now, draft.value, notes = draft.notes?.takeIf { it.isNotBlank() },
                    scheduleData = draft.schedule?.encode())
                insert(event)
                kind = "calendar_add"
            }
        }
        return CalendarReceiptEntity(requestId, kind, event.id, event.title, event.notes).also { insertReceipt(it) }
    }
}
