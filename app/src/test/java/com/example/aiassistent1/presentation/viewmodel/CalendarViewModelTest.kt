package com.example.aiassistent1.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.data.repository.DataStoreSettingsRepository
import com.example.aiassistent1.data.repository.TestPreferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    private val date = LocalDate.of(2030, 9, 12)

    @Test fun `zone choice reopens the month query without moving selected month or modifying events`() = runTest {
        val zones = com.example.aiassistent1.domain.interfaces.SystemCalendarTimeZoneRepository()
        val samara = ZoneId.of("Europe/Samara")
        val moscow = ZoneId.of("Europe/Moscow")
        zones.selectZone(samara.id)
        withCalendar(zones) { viewModel, repository ->
            val start = LocalDate.of(2030, 10, 1).atTime(0, 30).atZone(samara).toInstant().toEpochMilli()
            val original = CalendarEvent("saved", "Встреча", start, start + 600_000L, 1, 2, value = 0, notes = "  Документы  ")
            repository.existing = original
            viewModel.focusSavedEvent("saved").getOrThrow()
            runCurrent()
            assertEquals(YearMonth.of(2030, 10), viewModel.uiState.value.visibleMonth)
            zones.selectZone(moscow.id)
            runCurrent()
            assertEquals(YearMonth.of(2030, 10), viewModel.uiState.value.visibleMonth)
            assertEquals(moscow, viewModel.uiState.value.zoneId)
            assertEquals(LocalDate.of(2030, 10, 1).atStartOfDay(moscow).toInstant().toEpochMilli(), repository.observedRanges.last().first)
            assertTrue(viewModel.uiState.value.events.isEmpty())
            viewModel.focusSavedEvent("saved").getOrThrow()
            assertEquals(LocalDate.of(2030, 9, 30), viewModel.uiState.value.selectedDate)
            assertEquals(original, repository.existing)
            assertNull(repository.created)
            assertNull(repository.changed)
        }
    }

    @Test fun `manual creation rejects a DST gap and unchanged editing preserves an overlap instant`() = runTest {
        val zones = com.example.aiassistent1.domain.interfaces.SystemCalendarTimeZoneRepository()
        val berlin = ZoneId.of("Europe/Berlin")
        zones.selectZone(berlin.id)
        withCalendar(zones) { viewModel, repository ->
            viewModel.createEvent("Встреча", LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), null, null, null)
            runCurrent()
            assertNull(repository.created)
            assertNotNull(viewModel.uiState.value.error)
            val start = java.time.Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()
            val original = CalendarEvent("saved", "Прежнее название", start, start + 600_000L, 1, 2)
            viewModel.updateEvent(original.id, "Новое название", LocalDate.of(2026, 10, 25), LocalTime.of(2, 30),
                LocalTime.of(2, 40), 0, "  Документы  ", berlin, original)
            runCurrent()
            assertEquals(original.startsAtEpochMillis, repository.changed!!.startsAtEpochMillis)
            assertEquals(original.endsAtEpochMillis, repository.changed!!.endsAtEpochMillis)
        }
    }

    @Test fun `manual creation saves missing optional fields without defaults`() = runTest {
        withCalendar { viewModel, repository ->
            viewModel.createEvent("Встреча", date, LocalTime.of(9, 0), null, null, " \n ")
            runCurrent()
            val draft = repository.created!!
            assertEquals(date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), draft.startsAtEpochMillis)
            assertEquals(draft.startsAtEpochMillis, draft.endsAtEpochMillis)
            assertNull(draft.value)
            assertNull(draft.notes)
            assertEquals("Событие создано", viewModel.uiState.value.snackbarMessage)
        }
    }

    @Test fun `manual creation carries value and exact notes across midnight`() = runTest {
        withCalendar { viewModel, repository ->
            val notes = "  Взять документы\n12500 ₽  "
            viewModel.createEvent("Встреча", date, LocalTime.of(23, 30), LocalTime.of(0, 20), -17L, notes)
            runCurrent()
            val draft = repository.created!!
            assertEquals(-17L, draft.value)
            assertEquals(notes, draft.notes)
            assertEquals(date.plusDays(1).atTime(0, 20).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), draft.endsAtEpochMillis)
            assertEquals(50 * 60_000L, draft.endsAtEpochMillis - draft.startsAtEpochMillis)
        }
    }

    @Test fun `manual editing distinguishes zero from clearing value and notes`() = runTest {
        withCalendar { viewModel, repository ->
            viewModel.updateEvent("existing", "Встреча", date, LocalTime.of(9, 0), LocalTime.of(10, 0), 0L, "  Новый текст  ")
            runCurrent()
            assertEquals(CalendarValueChange.Set(0L), repository.changed!!.valueChange)
            assertEquals("  Новый текст  ", repository.changed!!.notes)
            assertFalse(repository.changed!!.clearNotes)

            viewModel.updateEvent("existing", "Встреча", date, LocalTime.of(9, 0), null, null, "")
            runCurrent()
            val update = repository.changed!!
            assertEquals("existing", update.id)
            assertEquals(CalendarValueChange.Clear, update.valueChange)
            assertTrue(update.clearNotes)
            assertNull(update.notes)
            assertEquals(update.startsAtEpochMillis, update.endsAtEpochMillis)
            assertEquals("Событие изменено", viewModel.uiState.value.snackbarMessage)
        }
    }

    @Test fun `blank title cannot reach storage`() = runTest {
        withCalendar { viewModel, repository ->
            viewModel.createEvent(" ", date, LocalTime.of(9, 0), null, null, null)
            viewModel.updateEvent("existing", " ", date, LocalTime.of(9, 0), null, null, null)
            runCurrent()
            assertNull(repository.created)
            assertNull(repository.changed)
            assertEquals("Введите название события.", viewModel.uiState.value.error)
        }
    }

    @Test fun `saved event link selects its current month and day`() = runTest {
        withCalendar { viewModel, repository ->
            val movedDate = LocalDate.of(2031, 2, 18)
            val start = movedDate.atTime(14, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            repository.existing = CalendarEvent("saved", "Встреча", start, start + 3_600_000, 0, 0)

            assertTrue(viewModel.focusSavedEvent("saved").isSuccess)
            assertEquals(YearMonth.from(movedDate), viewModel.uiState.value.visibleMonth)
            assertEquals(movedDate, viewModel.uiState.value.selectedDate)
            assertEquals("saved", viewModel.uiState.value.focusedEventId)

            viewModel.selectDate(movedDate.plusDays(1))
            assertEquals(movedDate.plusDays(1), viewModel.uiState.value.selectedDate)
            assertNull(viewModel.uiState.value.focusedEventId)

            assertTrue(viewModel.focusSavedEvent("saved").isSuccess)
            viewModel.clearFocusedEvent("saved")
            assertNull(viewModel.uiState.value.focusedEventId)
        }
    }

    @Test fun `deleted saved event does not navigate to an obsolete date`() = runTest {
        withCalendar { viewModel, _ ->
            val before = viewModel.uiState.value
            val result = viewModel.focusSavedEvent("deleted")
            assertTrue(result.isFailure)
            assertEquals("Событие не найдено в локальном календаре", result.exceptionOrNull()?.message)
            assertEquals(before.visibleMonth, viewModel.uiState.value.visibleMonth)
            assertEquals(before.selectedDate, viewModel.uiState.value.selectedDate)
        }
    }

    private suspend fun TestScope.withCalendar(
        zones: com.example.aiassistent1.domain.interfaces.CalendarTimeZoneRepository = com.example.aiassistent1.domain.interfaces.SystemCalendarTimeZoneRepository(),
        block: suspend (CalendarViewModel, RecordingRepository) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = RecordingRepository()
        val viewModel = CalendarViewModel(
            ObserveCalendarEventsUseCase(repository), CreateCalendarEventUseCase(repository),
            UpdateCalendarEventUseCase(repository), DeleteCalendarEventUseCase(repository),
            GetCalendarEventUseCase(repository),
            DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope),
            calendarTimeZones = zones,
        )
        try {
            runCurrent()
            block(viewModel, repository)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private class RecordingRepository : CalendarEventRepository {
        var created: CalendarEventDraft? = null
        var changed: CalendarEventUpdate? = null
        var existing: CalendarEvent? = null
        val observedRanges = mutableListOf<Pair<Long, Long>>()
        override suspend fun create(draft: CalendarEventDraft): Result<CalendarEvent> {
            created = draft
            return Result.success(CalendarEvent("new", draft.title, draft.startsAtEpochMillis, draft.endsAtEpochMillis, 0, 0,
                value = draft.value, notes = draft.notes))
        }
        override suspend fun update(update: CalendarEventUpdate): Result<CalendarEvent> {
            changed = update
            return Result.success(CalendarEvent(update.id, update.title, update.startsAtEpochMillis, update.endsAtEpochMillis, 0, 0))
        }
        override fun observeInRange(rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): kotlinx.coroutines.flow.Flow<List<CalendarEvent>> {
            observedRanges.add(rangeStartEpochMillis to rangeEndEpochMillis)
            return flowOf(listOfNotNull(existing).filter {
                it.startsAtEpochMillis < rangeEndEpochMillis && it.endsAtEpochMillis > rangeStartEpochMillis
            })
        }
        override suspend fun getById(id: String): Result<CalendarEvent?> = Result.success(existing?.takeIf { it.id == id })
        override suspend fun delete(id: String): Result<Unit> = error("Not used")
        override suspend fun search(query: String, rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): Result<List<CalendarEvent>> = error("Not used")
    }
}
