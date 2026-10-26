package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.interfaces.CalendarDraftRepository
import com.example.aiassistent1.domain.model.CalendarDraftRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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

    @Test fun `restart and retry keep the exact input zone of an interrupted save`() = runTest {
        val repository = Repository()
        val first = CalendarDraftController(repository, this)
        val original = draft("home").copy(timeZoneId = "Europe/Moscow", inputTimeZoneId = "Europe/Samara")
        first.add(original)
        runCurrent()
        first.save("home") { error("Interrupted after durable request") }
        runCurrent()
        val reopened = CalendarDraftController(repository, this)
        runCurrent()
        val restored = reopened.find("home")!!
        assertTrue(restored.saveRequested)
        assertFalse(restored.isEditable)
        assertEquals("Europe/Samara", restored.inputTimeZoneId)
        assertEquals("Europe/Moscow", restored.timeZoneId)
        reopened.save("home") { prepared ->
            assertEquals(java.time.Instant.parse("2026-10-01T11:00:00Z").toEpochMilli(),
                com.example.aiassistent1.calendar.core.domain.CalendarTime.toEpochMillis(
                    com.example.aiassistent1.calendar.core.domain.CalendarTime.dateTime(prepared.startsAt!!), prepared.zoneId))
            "saved-home"
        }
        runCurrent()
        assertEquals("saved-home", reopened.find("home")!!.savedEventId)
    }

    @Test fun `discard persists intent before deleting only its reply and survives reopen`() = runTest {
        val repository = Repository()
        val deleted = mutableListOf<String>()
        val controller = CalendarDraftController(repository, this) { id ->
            assertTrue(repository.stored.single { it.requestId == id }.discardRequested)
            deleted += id
        }
        controller.add(draft("a")); controller.add(draft("b")); runCurrent()
        controller.open("b")
        controller.edit("b", CalendarEventField.Title, "Сохранить введённый текст")
        controller.discard("a")
        controller.discard("a")
        controller.add(draft("a")) // A delayed callback cannot resurrect the discarded draft.
        assertNull(controller.find("a"))
        assertEquals(setOf("a"), controller.state.value.discardedReplyIds)
        assertEquals("b", controller.state.value.selectedId)
        runCurrent()
        assertEquals(listOf("a"), deleted)
        assertEquals(listOf("b"), repository.stored.map { it.requestId })
        val reopened = CalendarDraftController(repository, this)
        runCurrent()
        assertNull(reopened.find("a"))
        assertEquals("Сохранить введённый текст", reopened.find("b")!!.fieldText(CalendarEventField.Title))
    }

    @Test fun `discard never touches saved saving or confirmed drafts or an unknown id`() = runTest {
        val repository = Repository()
        val controller = CalendarDraftController(repository, this) { error("Reply must stay") }
        val protected = listOf(
            draft("saved").copy(savedEventId = "event"),
            draft("saving").copy(isSaving = true),
            draft("confirmed").copy(saveRequested = true),
            draft("other-chat").copy(chatId = "general"),
        )
        protected.forEach(controller::add)
        runCurrent()
        (protected.map { it.requestId } + "unknown").forEach(controller::discard)
        runCurrent()
        assertEquals(protected, controller.state.value.drafts)
        assertTrue(controller.state.value.discardedReplyIds.isEmpty())
        assertTrue(repository.stored.none { it.discardRequested })
    }

    @Test fun `failed intent write keeps reply on disk until storage retry succeeds`() = runTest {
        val repository = Repository()
        val deleted = mutableListOf<String>()
        val controller = CalendarDraftController(repository, this) { deleted += it }
        controller.add(draft("a")); runCurrent()
        repository.failWrites = true
        controller.discard("a"); runCurrent()
        assertTrue(deleted.isEmpty())
        assertFalse(repository.stored.single().discardRequested)
        assertNull(controller.find("a"))
        assertNotNull(controller.state.value.storageError)
        repository.failWrites = false
        controller.retryStorage(); runCurrent()
        assertEquals(listOf("a"), deleted)
        assertTrue(repository.stored.isEmpty())
        assertNull(controller.state.value.storageError)
    }

    @Test fun `reply deletion failure remains retryable while other drafts are edited`() = runTest {
        val repository = Repository()
        var failDelete = true
        var calls = 0
        val controller = CalendarDraftController(repository, this) {
            calls++
            check(!failDelete) { "Chat storage unavailable" }
        }
        controller.add(draft("a")); runCurrent()
        controller.discard("a"); runCurrent()
        assertTrue(repository.stored.single().discardRequested)
        assertNotNull(controller.state.value.storageError)
        controller.add(draft("b")); runCurrent()
        assertNotNull(controller.state.value.storageError)
        assertEquals(listOf("b"), controller.state.value.drafts.map { it.requestId })
        failDelete = false
        controller.retryStorage(); runCurrent()
        assertEquals(2, calls)
        assertEquals(listOf("b"), repository.stored.map { it.requestId })
        assertNull(controller.state.value.storageError)
    }

    @Test fun `restart completes a persisted discard interrupted before reply deletion`() = runTest {
        val repository = Repository()
        val firstJob = Job(coroutineContext[Job])
        val firstScope = CoroutineScope(coroutineContext + firstJob)
        val gate = CompletableDeferred<Unit>()
        val first = CalendarDraftController(repository, firstScope) { gate.await() }
        first.add(draft("a")); runCurrent()
        first.discard("a"); runCurrent()
        assertTrue(repository.stored.single().discardRequested)
        firstJob.cancelAndJoin()
        val deleted = mutableListOf<String>()
        val restarted = CalendarDraftController(repository, this) { deleted += it }
        runCurrent()
        assertNull(restarted.find("a"))
        assertEquals(setOf("a"), restarted.state.value.discardedReplyIds)
        assertEquals(listOf("a"), deleted)
        assertTrue(repository.stored.isEmpty())
    }

    @Test fun `lost final cleanup write repeats reply deletion safely after restart`() = runTest {
        val repository = Repository()
        val replies = mutableSetOf("a")
        val controller = CalendarDraftController(repository, this) {
            replies.remove(it)
            repository.failWrites = true
        }
        controller.add(draft("a")); runCurrent()
        controller.discard("a"); runCurrent()
        assertTrue(replies.isEmpty())
        assertTrue(repository.stored.single().discardRequested)
        assertNotNull(controller.state.value.storageError)
        repository.failWrites = false
        val restarted = CalendarDraftController(repository, this) { replies.remove(it); Unit }
        runCurrent()
        assertTrue(restarted.state.value.drafts.isEmpty())
        assertTrue(repository.stored.isEmpty())
        assertTrue(replies.isEmpty())
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

    @Test fun `invalid input and blank required fields never save previous valid values`() = runTest {
        val controller = CalendarDraftController(Repository(), this)
        controller.add(draft("a")); runCurrent()
        controller.edit("a", CalendarEventField.Time, "25:99")
        controller.save("a") { error("Must not commit") }; runCurrent()
        assertNotNull(controller.find("a")!!.error)
        assertFalse(controller.find("a")!!.saveRequested)
        controller.edit("a", CalendarEventField.Time, "15:00")
        controller.edit("a", CalendarEventField.Value, "не число")
        controller.save("a") { error("Must not commit") }; runCurrent()
        assertNotNull(controller.find("a")!!.error)
        assertEquals("не число", controller.find("a")!!.fieldText(CalendarEventField.Value))
        controller.edit("a", CalendarEventField.Value, "")
        controller.edit("a", CalendarEventField.Time, "")
        controller.save("a") { error("Must not commit") }; runCurrent()
        assertNotNull(controller.find("a")!!.error)
        assertFalse(controller.find("a")!!.saveRequested)
    }

    @Test fun `cleared optional fields remain absent through save and restoration`() = runTest {
        val repository = Repository()
        val controller = CalendarDraftController(repository, this)
        controller.add(draft("optional").copy(endsAt = "2026-10-01T16:00", notes = "Прежний текст"))
        runCurrent()
        controller.edit("optional", CalendarEventField.DurationMinutes, "")
        controller.edit("optional", CalendarEventField.Value, "")
        controller.editNotes("optional", "")
        controller.save("optional") {
            assertTrue(it.isComplete)
            assertNull(it.durationMinutes)
            assertNull(it.endsAt)
            assertNull(it.value)
            assertNull(it.notes)
            "event-optional"
        }
        runCurrent()
        val restored = CalendarDraftController(repository, this)
        runCurrent()
        val saved = restored.find("optional")!!
        assertEquals("event-optional", saved.savedEventId)
        assertNull(saved.value)
        assertNull(saved.notes)
        assertNull(saved.durationMinutes)
        assertNull(saved.endsAt)
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

    @Test fun `notes survive draft reopening editing and event creation without altering value`() = runTest {
        val repository = Repository()
        val controller = CalendarDraftController(repository, this)
        val initialNotes = "  Первая строка\nВторая строка: 12500 рублей.  "
        controller.add(draft("notes").copy(notes = initialNotes))
        runCurrent()
        val reopened = CalendarDraftController(repository, this)
        runCurrent()
        assertEquals(initialNotes, reopened.find("notes")!!.notes)
        val editedNotes = "  Взять документы.\nАкт от 2027-09-01 в 09:30.  "
        reopened.editNotes("notes", editedNotes)
        runCurrent()
        var committedNotes: String? = null
        reopened.save("notes") {
            committedNotes = it.notes
            assertEquals(0L, it.value)
            "event-with-notes"
        }
        runCurrent()
        assertEquals(editedNotes, committedNotes)
        val saved = repository.stored.single()
        assertEquals("event-with-notes", saved.savedEventId)
        assertEquals(editedNotes, saved.notes)
        assertEquals(0L, saved.value)
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
