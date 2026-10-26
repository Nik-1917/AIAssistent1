package com.example.aiassistent1.calendar.storage.android

import androidx.room.Room
import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.calendar.storage.android.local.CalendarDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class CalendarLinkedCardsStorageTest {
    private val databases = mutableListOf<CalendarDatabase>()
    private val names = mutableListOf<String>()
    private val context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Europe/Moscow")
    private val day = LocalDate.of(2026, 10, 20)
    private fun open(name: String) = Room.databaseBuilder(context, CalendarDatabase::class.java, name)
        .addMigrations(CalendarDatabase.MIGRATION_1_2, CalendarDatabase.MIGRATION_2_3, CalendarDatabase.MIGRATION_3_4)
        .build().also(databases::add)
    private fun name() = "links-${names.size}.db".also(names::add)
    private fun repository(database: CalendarDatabase) = RoomCalendarEventRepository(database.calendarEventDao(), deviceZone = { zone })
    private fun epoch(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private suspend fun <T> Channel<T>.next(): T = withContext(Dispatchers.IO) { withTimeout(10_000) { receive() } }

    @After fun close() { databases.forEach { it.close() }; names.forEach { context.deleteDatabase(it) } }

    // Robolectric includes method names in native SQLite paths; keep them short on Windows.
    @Test fun linkedEventMovesAcrossMonths() = runTest {
        val dbName = name()
        val db = open(dbName)
        val repo = repository(db)
        val original = CalendarEventDraft("Встреча", epoch(day, 9), epoch(day, 10), 7, "  Документы  ")
        val receipt = repo.commit("create-once", CalendarMutation.Create(original)).getOrThrow()
        val updates = Channel<Map<String, CalendarEvent?>>(Channel.UNLIMITED)
        val observation = backgroundScope.launch(Dispatchers.IO) {
            ObserveCalendarEventsByIdUseCase(repo)(setOf(receipt.eventId), zone).collect { updates.send(it) }
        }
        val before = updates.next()[receipt.eventId]!!
        val movedDay = LocalDate.of(2027, 2, 3)
        val updated = UpdateCalendarEventUseCase(repo)(CalendarEventUpdate(before.id, "Встреча перенесена",
            epoch(movedDay, 14), epoch(movedDay, 16), expectedRevision = before.revision,
            valueChange = CalendarValueChange.Set(0), notes = "  Новый текст  ")).getOrThrow()
        val after = updates.next()[receipt.eventId]!!
        assertEquals(updated, after)
        assertEquals(movedDay.atTime(14, 0), after.localStart(zone))
        assertEquals(before.id, after.id)
        assertEquals(before.createdAtEpochMillis, after.createdAtEpochMillis)
        assertEquals("  Новый текст  ", after.notes)
        assertEquals(0L, after.value)
        assertEquals(receipt, repo.getReceipt("create-once").getOrThrow())
        assertEquals(receipt, repo.commit("create-once", CalendarMutation.Create(original)).getOrThrow())
        assertEquals(1, repo.search("", Long.MIN_VALUE + 1, Long.MAX_VALUE).getOrThrow().size)
        observation.cancelAndJoin()
        db.close()
        val reopened = repository(open(dbName))
        assertEquals(after, reopened.observeByIds(setOf(after.id), zone).first()[after.id])
        assertEquals(receipt, reopened.getReceipt("create-once").getOrThrow())
    }

    @Test fun missingLinkKeepsReceipt() = runTest {
        val repo = repository(open(name()))
        val receipt = repo.commit("create", CalendarMutation.Create(CalendarEventDraft("Встреча", epoch(day, 9), epoch(day, 10)))).getOrThrow()
        val updates = Channel<Map<String, CalendarEvent?>>(Channel.UNLIMITED)
        val observation = backgroundScope.launch(Dispatchers.IO) {
            repo.observeByIds(setOf(receipt.eventId), zone).collect { updates.send(it) }
        }
        assertNotNull(updates.next()[receipt.eventId])
        DeleteCalendarEventUseCase(repo)(receipt.eventId).getOrThrow()
        val missing = updates.next()
        assertTrue(missing.containsKey(receipt.eventId))
        assertNull(missing[receipt.eventId])
        assertEquals(receipt, repo.getReceipt("create").getOrThrow())
        assertTrue(repo.search("", Long.MIN_VALUE + 1, Long.MAX_VALUE).getOrThrow().isEmpty())
        observation.cancelAndJoin()
    }

    @Test fun occurrenceAndSeriesStaySeparate() = runTest {
        val repo = repository(open(name()))
        val schedule = CalendarSchedule(CalendarTimeMode.FLOATING, day.atTime(9, 0), day.atTime(9, 30),
            recurrence = CalendarRecurrence(CalendarRepeat.DAILY))
        val root = repo.create(CalendarEventDraft("Привычка", 0, 0, notes = "  Текст  ", schedule = schedule)).getOrThrow()
        val changedId = CalendarSchedule.occurrenceId(root.id, day.plusDays(1))
        val untouchedId = CalendarSchedule.occurrenceId(root.id, day.plusDays(2))
        val ids = setOf(root.id, changedId, untouchedId)
        val updates = Channel<Map<String, CalendarEvent?>>(Channel.UNLIMITED)
        val observation = backgroundScope.launch(Dispatchers.IO) { repo.observeByIds(ids, zone).collect { updates.send(it) } }
        val before = updates.next()
        val moved = day.plusDays(7).atTime(11, 30)
        val changed = repo.update(CalendarEventUpdate(changedId, "Одна привычка", moved.atZone(zone).toInstant().toEpochMilli(),
            moved.plusMinutes(30).atZone(zone).toInstant().toEpochMilli(), expectedRevision = before[changedId]!!.revision,
            schedule = CalendarSchedule(CalendarTimeMode.FLOATING, moved, moved.plusMinutes(30)), replaceSchedule = true)).getOrThrow()
        val afterOne = updates.next()
        assertEquals(changed, afterOne[changedId])
        assertEquals(day.atTime(9, 0), afterOne[root.id]!!.schedule!!.start)
        assertEquals(day.plusDays(2).atTime(9, 0), afterOne[untouchedId]!!.localStart(zone))
        val series = repo.getById(root.id).getOrThrow()!!
        val newSchedule = series.schedule!!.copy(start = day.atTime(10, 15), end = day.atTime(10, 45))
        repo.update(CalendarEventUpdate(root.id, "Новое время серии", newSchedule.start.atZone(zone).toInstant().toEpochMilli(),
            newSchedule.end!!.atZone(zone).toInstant().toEpochMilli(),
            expectedRevision = series.revision, schedule = newSchedule, replaceSchedule = true)).getOrThrow()
        val afterSeries = updates.next()
        assertEquals(day.atTime(10, 15), afterSeries[root.id]!!.localStart(zone))
        assertEquals(day.plusDays(2).atTime(10, 15), afterSeries[untouchedId]!!.localStart(zone))
        assertEquals(changed, afterSeries[changedId])
        repo.delete(changedId).getOrThrow()
        assertNull(updates.next()[changedId])
        repo.delete(root.id).getOrThrow()
        assertTrue(updates.next().values.all { it == null })
        observation.cancelAndJoin()
    }

    @Test fun largeLinkSetReadsOnlyItsIds() = runTest {
        val repo = repository(open(name()))
        val event = repo.create(CalendarEventDraft("Связанное", epoch(day, 9), epoch(day, 9))).getOrThrow()
        repo.create(CalendarEventDraft("Постороннее", epoch(day, 10), epoch(day, 10))).getOrThrow()
        val ids = (0 until 901).map { "missing-$it" }.toSet() + event.id
        val snapshot = repo.observeByIds(ids, zone).first()
        assertEquals(ids, snapshot.keys)
        assertEquals(listOf(event), snapshot.values.filterNotNull())
        assertEquals(emptyMap<String, CalendarEvent?>(), repo.observeByIds(emptySet(), zone).first())
        assertEquals(2, repo.search("", Long.MIN_VALUE + 1, Long.MAX_VALUE).getOrThrow().size)
    }
}
