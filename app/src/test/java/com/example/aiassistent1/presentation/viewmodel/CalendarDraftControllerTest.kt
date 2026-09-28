package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.interfaces.CalendarDraftRepository
import com.example.aiassistent1.domain.model.CalendarDraftRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarDraftControllerTest {
    private fun draft(id: String) = CalendarEventDraftUiState(
        requestId = id, title = "Встреча $id", date = "2026-10-01", time = "15:00", durationMinutes = 60, value = 0,
    )

    private class Repository(var stored: List<CalendarDraftRecord> = emptyList()) : CalendarDraftRepository {
        var failWrites = false
        var failSavedStatus = false
        override suspend fun load() = stored
        override suspend fun save(drafts: List<CalendarDraftRecord>) {
            check(!failWrites && !(failSavedStatus && drafts.any { it.savedEventId != null })) { "Disk unavailable" }
            stored = drafts
        }
    }

    @Test fun `new event and closing editor retain unfinished input in the first event`() = runTest {
        val repository = Repository()
        val controller = CalendarDraftController(repository, this)
        controller.add(draft("first"))
        runCurrent()
        controller.open("first")
        controller.edit("first", CalendarEventField.Time, "завтра в пол")
        controller.edit("first", CalendarEventField.Value, "")
        controller.add(draft("second"))
        runCurrent()
        assertEquals("first", controller.state.value.selectedId)
        assertEquals("завтра в пол", controller.find("first")!!.fieldText(CalendarEventField.Time))
        assertEquals("15:00", controller.find("second")!!.fieldText(CalendarEventField.Time))
        controller.close()
        runCurrent()
        val restored = CalendarDraftController(repository, this)
        runCurrent()
        assertNull(restored.state.value.selectedId)
        assertEquals(2, restored.state.value.drafts.size)
        assertEquals("", restored.find("first")!!.fieldText(CalendarEventField.Value))
        restored.open("first")
        assertEquals("завтра в пол", restored.state.value.selected!!.fieldText(CalendarEventField.Time))
    }

    @Test fun `response arriving during restore is appended after persisted drafts`() = runTest {
        val gate = CompletableDeferred<List<CalendarDraftRecord>>()
        val repository = object : CalendarDraftRepository {
            override suspend fun load() = gate.await()
            override suspend fun save(drafts: List<CalendarDraftRecord>) = Unit
        }
        val controller = CalendarDraftController(repository, this)
        controller.add(draft("new"))
        runCurrent()
        assertTrue(controller.state.value.drafts.isEmpty())
        gate.complete(listOf(CalendarDraftRecord("old", "calendar", 1)))
        runCurrent()
        assertEquals(listOf("old", "new"), controller.state.value.drafts.map { it.requestId })
    }

    @Test fun `late save completion does not close or change another editor and double tap commits once`() = runTest {
        val controller = CalendarDraftController(Repository(), this)
        controller.add(draft("a")); controller.add(draft("b"))
        runCurrent()
        controller.open("a")
        val commitGate = CompletableDeferred<String>()
        var calls = 0
        controller.save("a") { calls++; commitGate.await() }
        controller.save("a") { calls++; "duplicate" }
        runCurrent()
        controller.open("b")
        controller.edit("b", CalendarEventField.Title, "Другое событие")
        commitGate.complete("event-a")
        runCurrent()
        assertEquals(1, calls)
        assertEquals("event-a", controller.find("a")!!.savedEventId)
        assertEquals("b", controller.state.value.selectedId)
        assertEquals("Другое событие", controller.find("b")!!.fieldText(CalendarEventField.Title))
        assertNull(controller.find("b")!!.savedEventId)
    }

    @Test fun `failed commit keeps immutable data and same receipt id for a safe retry`() = runTest {
        val repository = Repository()
        val controller = CalendarDraftController(repository, this)
        controller.add(draft("stable")); runCurrent()
        controller.save("stable") { error("Database unavailable") }
        runCurrent()
        assertNotNull(controller.find("stable")!!.error)
        assertTrue(controller.find("stable")!!.saveRequested)
        controller.edit("stable", CalendarEventField.Title, "Must not change the pending command")
        assertEquals("Встреча stable", controller.find("stable")!!.title)
        val restarted = CalendarDraftController(repository, this)
        runCurrent()
        restarted.save("stable") { assertEquals("stable", it.requestId); "event" }
        runCurrent()
        assertEquals("event", restarted.find("stable")!!.savedEventId)
        assertFalse(restarted.find("stable")!!.saveRequested)
    }

    @Test fun `commit receipt survives loss of the final status write`() = runTest {
        val repository = Repository().apply { failSavedStatus = true }
        val controller = CalendarDraftController(repository, this)
        controller.add(draft("stable")); runCurrent()
        val receipts = mutableMapOf<String, String>()
        var creations = 0
        val commit: suspend (CalendarEventDraftUiState) -> String = { draft ->
            receipts.getOrPut(draft.requestId) { creations++; "event" }
        }
        controller.save("stable", commit); runCurrent()
        assertTrue(repository.stored.single().saveRequested)
        val restarted = CalendarDraftController(repository, this)
        repository.failSavedStatus = false
        runCurrent()
        restarted.save("stable", commit); runCurrent()
        assertEquals(1, creations)
        assertEquals("event", repository.stored.single().savedEventId)
    }

    @Test fun `storage failure prevents a calendar mutation`() = runTest {
        val repository = Repository()
        val controller = CalendarDraftController(repository, this)
        controller.add(draft("a")); runCurrent()
        repository.failWrites = true
        var committed = false
        controller.save("a") { committed = true; "event" }; runCurrent()
        assertFalse(committed)
        assertNotNull(controller.state.value.storageError)
        assertFalse(controller.find("a")!!.isSaving)
        repository.failWrites = false
        controller.save("a") { committed = true; "event" }; runCurrent()
        assertTrue(committed)
    }

    @Test fun `late formatting never overwrites a newer input or another draft`() = runTest {
        val controller = CalendarDraftController(Repository(), this)
        controller.add(draft("a")); controller.add(draft("b")); runCurrent()
        controller.edit("a", CalendarEventField.Time, "в пять")
        controller.update("a", persist = false) { it.copy(isFormatting = true, formattingField = CalendarEventField.Time) }
        controller.open("b")
        controller.edit("a", CalendarEventField.Time, "18:00")
        controller.finishFormatting("a", CalendarEventField.Time, "в пять", Result.success("17:00"))
        assertEquals("18:00", controller.find("a")!!.fieldText(CalendarEventField.Time))
        assertEquals("15:00", controller.find("b")!!.fieldText(CalendarEventField.Time))
        assertFalse(controller.find("a")!!.isFormatting)
        controller.discard("a")
        controller.finishFormatting("a", CalendarEventField.Time, "18:00", Result.success("19:00"))
        assertNull(controller.find("a"))
    }

    @Test fun `invalid or blank edits never save the previous valid values`() = runTest {
        val controller = CalendarDraftController(Repository(), this)
        controller.add(draft("a")); runCurrent()
        controller.edit("a", CalendarEventField.Time, "25:99")
        controller.save("a") { error("Must not commit") }; runCurrent()
        assertNotNull(controller.find("a")!!.error)
        assertFalse(controller.find("a")!!.saveRequested)
        controller.edit("a", CalendarEventField.Time, "15:00")
        controller.edit("a", CalendarEventField.Value, "")
        controller.save("a") { error("Must not commit") }; runCurrent()
        assertNotNull(controller.find("a")!!.error)
        assertEquals("", controller.find("a")!!.fieldText(CalendarEventField.Value))
    }

    @Test fun `read failure is surfaced without overwriting persisted data`() = runTest {
        var writes = 0
        val repository = object : CalendarDraftRepository {
            override suspend fun load(): List<CalendarDraftRecord> = error("Unreadable data")
            override suspend fun save(drafts: List<CalendarDraftRecord>) { writes++ }
        }
        val controller = CalendarDraftController(repository, this)
        controller.add(draft("new")); runCurrent()
        assertFalse(controller.state.value.isLoaded)
        assertNotNull(controller.state.value.storageError)
        assertEquals(0, writes)
        assertEquals("new", controller.state.value.drafts.single().requestId)
    }

    @Test fun `manual schedule changes recalculate the end and retain notes and explicit zero`() {
        val original = draft("a").copy(endsAt = "2026-10-01T16:00", notes = "Точное примечание")
        assertEquals(original.endsAt, original.resolveInputs().endsAt)
        val changed = original.copy(fieldInputs = mapOf(CalendarEventField.Time to "16:30")).resolveInputs()
        assertNull(changed.endsAt)
        assertEquals("17:30", changed.endDisplayText())
        assertEquals(0L, changed.value)
        assertEquals(original.notes, changed.notes)
    }

    @Test fun `filling a missing start retains the supplied end and derives the duration`() {
        val draft = CalendarEventDraftUiState(
            title = "Встреча", date = "2026-10-01", endsAt = "2026-10-01T16:00", value = 0,
            fieldInputs = mapOf(CalendarEventField.Time to "14:30"),
        )
        assertTrue(draft.missingFields.isEmpty())
        assertEquals("90", draft.fieldText(CalendarEventField.DurationMinutes))
        val resolved = draft.resolveInputs()
        assertEquals(90, resolved.durationMinutes)
        assertEquals(draft.endsAt, resolved.endsAt)
        assertTrue(resolved.isComplete)
    }

    @Test fun `retrying storage after a read error merges old and newly received drafts`() = runTest {
        var canRead = false
        var stored = listOf(CalendarDraftRecord("old", "calendar", 1))
        val repository = object : CalendarDraftRepository {
            override suspend fun load(): List<CalendarDraftRecord> { check(canRead); return stored }
            override suspend fun save(drafts: List<CalendarDraftRecord>) { stored = drafts }
        }
        val controller = CalendarDraftController(repository, this)
        controller.add(draft("new")); runCurrent()
        controller.edit("new", CalendarEventField.Time, "в пол"); runCurrent()
        canRead = true
        controller.retryStorage(); runCurrent()
        assertEquals(listOf("old", "new"), stored.map { it.requestId })
        assertEquals("в пол", controller.find("new")!!.fieldText(CalendarEventField.Time))
        assertNull(controller.state.value.storageError)
    }
}
