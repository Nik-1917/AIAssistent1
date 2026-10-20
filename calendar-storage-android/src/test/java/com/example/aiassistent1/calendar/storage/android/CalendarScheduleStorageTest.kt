package com.example.aiassistent1.calendar.storage.android

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.calendar.storage.android.local.CalendarDatabase
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class CalendarScheduleStorageTest {
    private val databases = mutableListOf<CalendarDatabase>()
    private val names = mutableListOf<String>()
    private val context get() = RuntimeEnvironment.getApplication()
    private val day = LocalDate.of(2026, 10, 20)
    private val utc = ZoneOffset.UTC
    private var zone: ZoneId = ZoneId.of("Europe/Samara")
    private fun name() = "s${names.size}.db".also { names.add(it) }
    private fun open(name: String) = Room.databaseBuilder(context, CalendarDatabase::class.java, name)
        .addMigrations(CalendarDatabase.MIGRATION_1_2, CalendarDatabase.MIGRATION_2_3, CalendarDatabase.MIGRATION_3_4)
        .build().also { databases.add(it) }
    private fun repo(database: CalendarDatabase) = RoomCalendarEventRepository(database.calendarEventDao(), deviceZone = { zone })
    private fun schedule() = CalendarSchedule(CalendarTimeMode.FLOATING, day.atTime(9, 0), day.atTime(9, 30), recurrence = CalendarRecurrence(CalendarRepeat.DAILY))
    private fun draft() = CalendarEventDraft("Зарядка", 0, 0, 7, "  Текст\nбез изменений  ", schedule())
    private suspend fun events(repo: RoomCalendarEventRepository, days: Long = 3) = repo.search("", day.atStartOfDay(utc).toInstant().toEpochMilli(),
        day.plusDays(days).atStartOfDay(utc).toInstant().toEpochMilli(), utc).getOrThrow()

    @After fun close() { databases.forEach { it.close() }; names.forEach { context.deleteDatabase(it) } }

    @Test fun floatingRuleAndReceiptSurviveRestartAndZoneChange() = runTest {
        val name = name()
        val db = open(name)
        val first = repo(db)
        val receipt = first.commit("habit-request", CalendarMutation.Create(draft())).getOrThrow()
        assertEquals(receipt, first.commit("habit-request", CalendarMutation.Create(draft().copy(title = "Дубль"))).getOrThrow())
        val before = first.getById(receipt.eventId).getOrThrow()!!
        db.close()
        zone = ZoneId.of("Asia/Tokyo")
        val second = repo(open(name))
        val after = second.getById(receipt.eventId).getOrThrow()!!
        assertEquals(before.schedule, after.schedule)
        assertNotEquals(before.startsAtEpochMillis, after.startsAtEpochMillis)
        assertEquals(LocalTime.of(9, 0), after.localStart(zone).toLocalTime())
        assertEquals(3, events(second).size)
        assertTrue(events(second).all { it.notes == draft().notes && it.value == 7L })
        val sum = CalendarCommandExecutor(second, utc).execute(CalendarCommand.Sum(null, CalendarRange(
            day.atStartOfDay(utc).toInstant().toEpochMilli(), day.plusDays(3).atStartOfDay(utc).toInstant().toEpochMilli())), "sum").getOrThrow() as CalendarCommandResult.Sum
        assertEquals("21", sum.total.toString())
        assertEquals(3, sum.matchedCount)
        assertEquals(1, second.search("Зарядка", Long.MIN_VALUE + 1, Long.MAX_VALUE).getOrThrow().size)
    }

    @Test fun editingOneOccurrenceIsAtomicAndStaleEditsAreRejected() = runTest {
        val db = open(name())
        val repo = repo(db)
        val root = repo.create(draft()).getOrThrow()
        val before = events(repo)
        val occurrence = before[1]
        val replacementSchedule = CalendarSchedule(CalendarTimeMode.FLOATING, day.plusDays(1).atTime(11, 0))
        val time = day.plusDays(1).atTime(11, 0).toInstant(utc).toEpochMilli()
        val change = CalendarEventUpdate(occurrence.id, "Одна тренировка", time, time, expectedRevision = occurrence.revision,
            schedule = replacementSchedule, replaceSchedule = true)
        val updated = repo.update(change).getOrThrow()
        assertEquals(root.id, updated.parentSeriesId)
        assertEquals(draft().notes, updated.notes)
        assertEquals(7L, updated.value)
        assertTrue(repo.update(change).exceptionOrNull() is CalendarConflictException)
        val after = events(repo)
        assertEquals(3, after.size)
        assertEquals(before[0].title, after[0].title)
        assertEquals(before[0].startsAtEpochMillis, after[0].startsAtEpochMillis)
        assertEquals("Одна тренировка", after[1].title)
        assertEquals(LocalTime.of(11, 0), after[1].localStart(utc).toLocalTime())
        assertEquals(before[2].startsAtEpochMillis, after[2].startsAtEpochMillis)
        assertEquals(1, repo.getById(root.id).getOrThrow()!!.schedule!!.excludedDates.size)
    }

    @Test fun deleteOccurrenceAndSeriesKeepsReceipt() = runTest {
        val repo = repo(open(name()))
        val receipt = repo.commit("create", CalendarMutation.Create(draft())).getOrThrow()
        val before = events(repo)
        repo.delete(before[0].id).getOrThrow()
        assertNull(repo.getById(before[0].id).getOrThrow())
        assertEquals(2, events(repo).size)
        repo.update(CalendarEventUpdate(before[1].id, "Изменение", before[1].startsAtEpochMillis, before[1].endsAtEpochMillis)).getOrThrow()
        assertEquals(2, events(repo).size)
        repo.delete(receipt.eventId).getOrThrow()
        assertTrue(events(repo).isEmpty())
        assertEquals(receipt, repo.getReceipt("create").getOrThrow())
        assertEquals(receipt, repo.commit("create", CalendarMutation.Create(draft())).getOrThrow())
    }

    @Test fun observedMonthExpandsWeekdaysAndSelectedDatesWithoutDuplicates() = runTest {
        val repo = repo(open(name()))
        val dates = setOf(day.plusDays(1), day.plusDays(2))
        repo.create(draft().copy(schedule = schedule().copy(recurrence = CalendarRecurrence(CalendarRepeat.DATES, dates = dates)))).getOrThrow()
        val results = repo.observeInRange(day.atStartOfDay(utc).toInstant().toEpochMilli(), day.plusDays(7).atStartOfDay(utc).toInstant().toEpochMilli(), utc).first()
        assertEquals(dates.sorted(), results.map { it.occurrenceDate })
    }

    @Test fun textEditsKeepFloatingTimeAcrossProjectionZones() = runTest {
        val repo = repo(open(name()))
        val root = repo.create(draft()).getOrThrow()
        val projected = events(repo).first()
        val renamed = repo.update(CalendarEventUpdate(projected.id, "Новое название", projected.startsAtEpochMillis,
            projected.endsAtEpochMillis, expectedRevision = projected.revision)).getOrThrow()
        assertEquals(LocalTime.of(9, 0), renamed.localStart(zone).toLocalTime())
        assertEquals(LocalTime.of(9, 0), events(repo).first().localStart(utc).toLocalTime())
        val originalRule = repo.getById(root.id).getOrThrow()!!
        repo.update(CalendarEventUpdate(root.id, "Новое название серии", originalRule.startsAtEpochMillis,
            originalRule.endsAtEpochMillis, expectedRevision = originalRule.revision)).getOrThrow()
        assertEquals(root.schedule!!.start, repo.getById(root.id).getOrThrow()!!.schedule!!.start)
        assertEquals(3, events(repo).size)
    }

    @Test fun migrationFromV3PreservesEveryOldFieldAndReceipt() = runTest {
        val name = name()
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE calendar_events (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, startsAtEpochMillis INTEGER NOT NULL, endsAtEpochMillis INTEGER NOT NULL, createdAtEpochMillis INTEGER NOT NULL, updatedAtEpochMillis INTEGER NOT NULL, value INTEGER DEFAULT NULL, revision INTEGER NOT NULL DEFAULT 0, notes TEXT DEFAULT NULL)")
            db.execSQL("CREATE INDEX index_calendar_events_startsAtEpochMillis ON calendar_events(startsAtEpochMillis)")
            db.execSQL("CREATE INDEX index_calendar_events_title ON calendar_events(title)")
            db.execSQL("CREATE TABLE calendar_command_receipts (requestId TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, eventId TEXT NOT NULL, title TEXT NOT NULL, notes TEXT DEFAULT NULL)")
            db.execSQL("INSERT INTO calendar_events VALUES ('old','Событие',60000,120000,100,200,17,4,'  текст  ')")
            db.execSQL("INSERT INTO calendar_command_receipts VALUES ('request','calendar_add','old','Событие','  текст  ')")
            db.version = 3
        }
        val repo = repo(open(name))
        val expected = CalendarEvent("old", "Событие", 60000, 120000, 100, 200, 17, 4, "  текст  ")
        assertEquals(expected, repo.getById("old").getOrThrow())
        assertEquals(CalendarReceipt("request", "calendar_add", "old", "Событие", "  текст  "), repo.getReceipt("request").getOrThrow())
        assertEquals(listOf(expected), repo.search("", 0, 180000).getOrThrow())
    }
}
