package com.example.aiassistent1.calendar.storage.android

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.calendar.core.domain.CalendarEventDraft
import com.example.aiassistent1.calendar.core.domain.CalendarEventUpdate
import com.example.aiassistent1.calendar.storage.android.local.CalendarDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomCalendarEventRepositoryInstrumentedTest {
    private lateinit var database: CalendarDatabase
    private lateinit var repository: RoomCalendarEventRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            CalendarDatabase::class.java,
        ).build()
        repository = RoomCalendarEventRepository(
            dao = database.calendarEventDao(),
            nowEpochMillis = { 1_000L },
            newId = { "event-1" },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `creates reads updates and deletes a local event`() = runTest {
        val created = repository.create(
            CalendarEventDraft(
                title = "  Team meeting  ",
                startsAtEpochMillis = 10_000L,
                endsAtEpochMillis = 70_000L,
            ),
        ).getOrThrow()

        assertEquals("event-1", created.id)
        assertEquals("Team meeting", created.title)
        assertEquals(created, repository.getById(created.id).getOrThrow())

        val updated = repository.update(
            CalendarEventUpdate(
                id = created.id,
                title = "Updated meeting",
                startsAtEpochMillis = 20_000L,
                endsAtEpochMillis = 140_000L,
            ),
        ).getOrThrow()

        assertEquals(created.createdAtEpochMillis, updated.createdAtEpochMillis)
        assertEquals("Updated meeting", updated.title)
        assertEquals(140_000L, updated.endsAtEpochMillis)

        repository.delete(created.id).getOrThrow()

        assertEquals(null, repository.getById(created.id).getOrThrow())
        assertTrue(repository.delete(created.id).isFailure)
    }

    @Test
    fun `observes overlapping events and treats search wildcard characters as literal text`() = runTest {
        val first = repository.create(
            CalendarEventDraft("100% completed", 10_000L, 70_000L),
        ).getOrThrow()
        val second = RoomCalendarEventRepository(
            dao = database.calendarEventDao(),
            nowEpochMillis = { 2_000L },
            newId = { "event-2" },
        ).create(
            CalendarEventDraft("Planning", 70_000L, 130_000L),
        ).getOrThrow()

        val observed = repository.observeInRange(60_000L, 80_000L).first()
        val searched = repository.search("100%", 0L, 200_000L).getOrThrow()

        assertEquals(listOf(first, second), observed)
        assertEquals(listOf(first), searched)
        assertFalse(repository.search("_", 0L, 200_000L).getOrThrow().isNotEmpty())
    }

    @Test
    fun `finds a Russian title regardless of letter case`() = runTest {
        val event = repository.create(
            CalendarEventDraft("Тренировка", 10_000L, 70_000L),
        ).getOrThrow()

        val searched = repository.search("ТРЕНИРОВКА", 0L, 100_000L).getOrThrow()

        assertEquals(listOf(event), searched)
    }

    @Test
    fun `returns the most recently inserted event when creation times are equal`() = runTest {
        repository.create(
            CalendarEventDraft("Первое", 10_000L, 70_000L),
        ).getOrThrow()
        val second = RoomCalendarEventRepository(
            dao = database.calendarEventDao(),
            nowEpochMillis = { 1_000L },
            newId = { "event-2" },
        ).create(
            CalendarEventDraft("Второе", 80_000L, 140_000L),
        ).getOrThrow()

        assertEquals(second, repository.getLastCreated().getOrThrow())
    }

    @Test
    fun `returns the final event in the requested calendar period`() = runTest {
        repository.create(
            CalendarEventDraft("Утреннее", 10_000L, 70_000L),
        ).getOrThrow()
        val lastToday = RoomCalendarEventRepository(
            dao = database.calendarEventDao(),
            nowEpochMillis = { 2_000L },
            newId = { "event-2" },
        ).create(
            CalendarEventDraft("Вечернее", 80_000L, 140_000L),
        ).getOrThrow()
        RoomCalendarEventRepository(
            dao = database.calendarEventDao(),
            nowEpochMillis = { 3_000L },
            newId = { "event-3" },
        ).create(
            CalendarEventDraft("Завтрашнее", 200_000L, 260_000L),
        ).getOrThrow()

        assertEquals(lastToday, repository.getLastInRange(0L, 150_000L).getOrThrow())
        assertEquals(null, repository.getLastInRange(150_000L, 200_000L).getOrThrow())
    }

    @Test
    fun `rejects invalid event data`() = runTest {
        val blankTitle = repository.create(CalendarEventDraft(" ", 10L, 20L))
        val invertedRange = repository.create(CalendarEventDraft("Meeting", 20L, 20L))

        assertTrue(blankTitle.isFailure)
        assertTrue(invertedRange.isFailure)
        assertNotNull(blankTitle.exceptionOrNull())
        assertNotNull(invertedRange.exceptionOrNull())
    }
}
