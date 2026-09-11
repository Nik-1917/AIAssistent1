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
        WHERE startsAtEpochMillis < :rangeEndEpochMillis
          AND endsAtEpochMillis > :rangeStartEpochMillis
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

    @Query(
        """
        SELECT * FROM calendar_events
        WHERE startsAtEpochMillis < :rangeEndEpochMillis
          AND endsAtEpochMillis > :rangeStartEpochMillis
        ORDER BY startsAtEpochMillis ASC, id ASC
        """,
    )
    abstract suspend fun findInRange(
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): List<CalendarEventEntity>

    @Query(
        """
        SELECT * FROM calendar_events
        WHERE (:rangeStartEpochMillis IS NULL OR startsAtEpochMillis < :rangeEndEpochMillis)
          AND (:rangeEndEpochMillis IS NULL OR endsAtEpochMillis > :rangeStartEpochMillis)
        ORDER BY startsAtEpochMillis ASC, id ASC
        """,
    )
    abstract suspend fun findForUpdateCandidates(
        rangeStartEpochMillis: Long?,
        rangeEndEpochMillis: Long?,
    ): List<CalendarEventEntity>

    @Query(
        """
        SELECT * FROM calendar_events
        ORDER BY createdAtEpochMillis DESC, rowid DESC
        LIMIT 1
        """,
    )
    abstract suspend fun getLastCreated(): CalendarEventEntity?

    @Query(
        """
        SELECT * FROM calendar_events
        WHERE startsAtEpochMillis < :rangeEndEpochMillis
          AND endsAtEpochMillis > :rangeStartEpochMillis
        ORDER BY startsAtEpochMillis DESC, id DESC
        LIMIT 1
        """,
    )
    abstract suspend fun getLastInRange(
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): CalendarEventEntity?

    @Query("SELECT * FROM calendar_command_receipts WHERE requestId = :requestId")
    abstract suspend fun getReceipt(requestId: String): CalendarReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertReceipt(receipt: CalendarReceiptEntity)

    @Transaction
    open suspend fun updateChecked(update: CalendarEventUpdate, now: Long): CalendarEventEntity {
        require(update.title.isNotBlank() && update.startsAtEpochMillis < update.endsAtEpochMillis)
        val existing = getById(update.id) ?: throw NoSuchElementException("Событие не найдено")
        if (update.expectedRevision != null && existing.revision != update.expectedRevision) throw CalendarConflictException()
        val changed = existing.copy(title = update.title.trim(), startsAtEpochMillis = update.startsAtEpochMillis,
            endsAtEpochMillis = update.endsAtEpochMillis, value = update.valueChange.applyTo(existing.value),
            notes = update.notes?.takeIf { it.isNotBlank() } ?: existing.notes,
            updatedAtEpochMillis = now, revision = Math.addExact(existing.revision, 1))
        check(update(changed) == 1) { "Событие не обновлено" }
        return changed
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
                require(draft.title.isNotBlank() && draft.startsAtEpochMillis < draft.endsAtEpochMillis)
                event = CalendarEventEntity(newId, draft.title.trim(), draft.startsAtEpochMillis,
                    draft.endsAtEpochMillis, now, now, draft.value, notes = draft.notes?.takeIf { it.isNotBlank() })
                insert(event)
                kind = "calendar_add"
            }
            is CalendarMutation.Update -> {
                require(mutation.update.expectedRevision != null) { "Не указана версия события" }
                event = updateChecked(mutation.update, now)
                kind = "calendar_update"
            }
            is CalendarMutation.Delete -> {
                event = getById(mutation.id) ?: throw NoSuchElementException("Событие не найдено")
                if (event.revision != mutation.expectedRevision) throw CalendarConflictException()
                check(deleteById(event.id) == 1) { "Событие не удалено" }
                kind = "calendar_delete"
            }
        }
        return CalendarReceiptEntity(requestId, kind, event.id, event.title, event.notes).also { insertReceipt(it) }
    }
}
