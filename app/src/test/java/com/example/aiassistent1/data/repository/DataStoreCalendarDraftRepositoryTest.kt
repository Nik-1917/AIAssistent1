package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.aiassistent1.domain.model.CalendarDraftRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreCalendarDraftRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `drafts and unfinished text survive closing and reopening the actual datastore file`() = runBlocking {
        val file = File(temporary.root, "drafts.preferences_pb")
        val expected = listOf(
            CalendarDraftRecord(
                requestId = "first", chatId = "calendar", createdAtEpochMillis = 123,
                title = "Встреча", date = "2026-10-01", time = "15:00", durationMinutes = 60,
                value = 0, notes = "  Не менять\nтекст  ", endsAt = "2026-10-01T16:00",
                fieldInputs = mapOf("Time" to "завтра в пол", "Value" to ""),
            ),
            CalendarDraftRecord("second", "calendar", 124, value = Long.MAX_VALUE, saveRequested = true),
            CalendarDraftRecord("third", "calendar", 125, savedEventId = "saved-event"),
        )
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = DataStoreCalendarDraftRepository(PreferenceDataStoreFactory.create(scope = firstScope) { file })
            assertEquals(emptyList<CalendarDraftRecord>(), repository.load())
            repository.save(expected)
        } finally { firstScope.coroutineContext[Job]!!.cancelAndJoin() }
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val restored = DataStoreCalendarDraftRepository(PreferenceDataStoreFactory.create(scope = secondScope) { file })
            assertEquals(expected, restored.load())
        } finally { secondScope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
