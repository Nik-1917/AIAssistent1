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
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    private val date = LocalDate.of(2030, 9, 12)

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

    private suspend fun TestScope.withCalendar(block: suspend (CalendarViewModel, RecordingRepository) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = RecordingRepository()
        val viewModel = CalendarViewModel(
            ObserveCalendarEventsUseCase(repository), CreateCalendarEventUseCase(repository),
            UpdateCalendarEventUseCase(repository), DeleteCalendarEventUseCase(repository),
            DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope),
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
        override suspend fun create(draft: CalendarEventDraft): Result<CalendarEvent> {
            created = draft
            return Result.success(CalendarEvent("new", draft.title, draft.startsAtEpochMillis, draft.endsAtEpochMillis, 0, 0,
                value = draft.value, notes = draft.notes))
        }
        override suspend fun update(update: CalendarEventUpdate): Result<CalendarEvent> {
            changed = update
            return Result.success(CalendarEvent(update.id, update.title, update.startsAtEpochMillis, update.endsAtEpochMillis, 0, 0))
        }
        override fun observeInRange(rangeStartEpochMillis: Long, rangeEndEpochMillis: Long) = flowOf(emptyList<CalendarEvent>())
        override suspend fun getById(id: String): Result<CalendarEvent?> = error("Not used")
        override suspend fun delete(id: String): Result<Unit> = error("Not used")
        override suspend fun search(query: String, rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): Result<List<CalendarEvent>> = error("Not used")
    }
}
