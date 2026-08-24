package com.example.aiassistent1.calendar.storage.android.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: CalendarEventEntity)

    @Query("SELECT * FROM calendar_events WHERE id = :id")
    suspend fun getById(id: String): CalendarEventEntity?

    @Query(
        """
        SELECT * FROM calendar_events
        WHERE startsAtEpochMillis < :rangeEndEpochMillis
          AND endsAtEpochMillis > :rangeStartEpochMillis
        ORDER BY startsAtEpochMillis ASC, id ASC
        """,
    )
    fun observeInRange(
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): Flow<List<CalendarEventEntity>>

    @Update
    suspend fun update(event: CalendarEventEntity): Int

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query(
        """
        SELECT * FROM calendar_events
        WHERE startsAtEpochMillis < :rangeEndEpochMillis
          AND endsAtEpochMillis > :rangeStartEpochMillis
        ORDER BY startsAtEpochMillis ASC, id ASC
        """,
    )
    suspend fun findInRange(
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
    suspend fun findForUpdateCandidates(
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
    suspend fun getLastCreated(): CalendarEventEntity?
}
