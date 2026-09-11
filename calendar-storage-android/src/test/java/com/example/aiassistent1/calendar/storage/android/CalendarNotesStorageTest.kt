package com.example.aiassistent1.calendar.storage.android

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.calendar.storage.android.local.CalendarDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** JVM-only Room checks: every database belongs to Robolectric's isolated test context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class CalendarNotesStorageTest {
    private val databases = mutableListOf<CalendarDatabase>()
    private val names = mutableListOf<String>()
    private val context get() = RuntimeEnvironment.getApplication()
    private val text = "  Примечание: \"ТЕКСТ\"\n12500 ₽ 😀\nhttps://example.org  "

    @After fun closeDatabases() {
        databases.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    // Keep native SQLite paths short on Windows; Robolectric already isolates each test.
    private fun newName() = "notes-${names.size}.db".also { names.add(it) }
    private fun open(name: String) = Room.databaseBuilder(context, CalendarDatabase::class.java, name)
        .addMigrations(CalendarDatabase.MIGRATION_1_2, CalendarDatabase.MIGRATION_2_3)
        .build().also { databases.add(it) }
    private fun repository(database: CalendarDatabase) = RoomCalendarEventRepository(database.calendarEventDao())

    @Test fun explicitEndPersists() = runTest {
        val name = newName()
        val database = open(name)
        val repo = repository(database)
        val zone = java.time.ZoneOffset.UTC
        val executor = CalendarCommandExecutor(repo, zone)
        val end = CalendarTime.dateTime("2026-09-12T00:20")
        val command = CalendarCommand.Add("Встреча", java.time.LocalDate.of(2026, 9, 11),
            java.time.LocalTime.of(23, 40), null, value = 0, notes = text, endsAt = end)
        val draft = executor.execute(command, "end-create").getOrThrow() as CalendarCommandResult.AddDraft
        assertEquals(40, draft.command.durationMinutes)
        val incomplete = executor.execute(command.copy(value = null), "missing-value").getOrThrow() as CalendarCommandResult.NeedsFields
        assertEquals(listOf(MissingCalendarField.VALUE), incomplete.fields)
        assertTrue(executor.execute(command.copy(durationMinutes = 20), "conflict", confirmed = true).isFailure)
        assertTrue(repo.search("", 0, Long.MAX_VALUE).getOrThrow().isEmpty())
        val result = executor.execute(command, "end-create", confirmed = true).getOrThrow() as CalendarCommandResult.Completed
        database.close()
        val saved = repository(open(name)).getById(result.receipt.eventId).getOrThrow()!!
        assertEquals(CalendarTime.toEpochMillis(end, zone), saved.endsAtEpochMillis)
        assertEquals(40 * 60_000L, saved.endsAtEpochMillis - saved.startsAtEpochMillis)
        assertEquals(text, saved.notes)
        assertEquals(0L, saved.value)
    }

    @Test fun notesPersistAfterReopen() = runTest {
        val name = newName()
        val database = open(name)
        val created = repository(database).create(CalendarEventDraft("Встреча", 60000, 120000, 0, text)).getOrThrow()
        database.close()
        val repo = repository(open(name))
        assertEquals(created, repo.getById(created.id).getOrThrow())
        assertEquals(text, repo.search("Встреча", 0, 180000).getOrThrow().single().notes)
        assertEquals(text, repo.observeInRange(0, 180000).first().single().notes)
        // Notes do not change the existing title-only matching contract.
        assertTrue(repo.search("12500", 0, 180000).getOrThrow().isEmpty())
    }

    @Test fun partialUpdatesKeepNotes() = runTest {
        val repo = repository(open(newName()))
        var event = repo.create(CalendarEventDraft("Встреча", 60000, 120000, 17, text)).getOrThrow()
        for (notes in listOf(null, "", " \n ")) {
            event = repo.update(CalendarEventUpdate(event.id, "Новое название", 120000, 180000, notes = notes)).getOrThrow()
            assertEquals(text, event.notes)
            assertEquals(17L, event.value)
        }
        val update = PrepareCalendarEventUpdateUseCase()(event, CalendarEventChanges(notes = "  Новый текст  ")).getOrThrow()
        val changed = repo.update(update).getOrThrow()
        assertEquals("  Новый текст  ", changed.notes)
        assertEquals(event.revision + 1, changed.revision)
        assertTrue(repo.update(update).exceptionOrNull() is CalendarConflictException)
    }

    @Test fun atomicRetriesAndDeletion() = runTest {
        val repo = repository(open(newName()))
        val receipt = repo.commit("create", CalendarMutation.Create(CalendarEventDraft("Встреча", 60000, 120000, 0, text))).getOrThrow()
        assertEquals(text, receipt.notes)
        assertEquals(receipt, repo.commit("create", CalendarMutation.Create(CalendarEventDraft("Дубль", 60000, 120000))).getOrThrow())
        var event = repo.getById(receipt.eventId).getOrThrow()!!
        assertEquals(text, event.notes)
        assertEquals(1, repo.search("", 0, 180000).getOrThrow().size)
        val update = PrepareCalendarEventUpdateUseCase()(event, CalendarEventChanges(notes = "Новое")).getOrThrow()
        val updated = repo.commit("update", CalendarMutation.Update(update)).getOrThrow()
        assertEquals("Новое", updated.notes)
        assertEquals(updated, repo.commit("update", CalendarMutation.Update(update)).getOrThrow())
        event = repo.getById(event.id).getOrThrow()!!
        val deleted = repo.commit("delete", CalendarMutation.Delete(event.id, event.revision)).getOrThrow()
        assertEquals("Новое", deleted.notes)
        assertNull(repo.getById(event.id).getOrThrow())
        assertEquals(deleted, repo.commit("delete", CalendarMutation.Delete(event.id, event.revision)).getOrThrow())
    }

    @Test fun notesRemainOptional() = runTest {
        val repo = repository(open(newName()))
        for (notes in listOf(null, "", "  ")) {
            assertNull(repo.create(CalendarEventDraft("Встреча", 60000, 120000, notes = notes)).getOrThrow().notes)
        }
    }

    @Test fun migrateV1AndV2() = runTest {
        for (version in listOf(1, 2)) {
            val name = newName()
            createHistoricalDatabase(name, version)
            // Room itself validates the migrated schema while opening this database.
            val repo = repository(open(name))
            val event = repo.getById("old-event").getOrThrow()!!
            assertEquals("Старое событие", event.title)
            assertEquals(60000L, event.startsAtEpochMillis)
            assertEquals(120000L, event.endsAtEpochMillis)
            assertEquals(100L, event.createdAtEpochMillis)
            assertEquals(200L, event.updatedAtEpochMillis)
            assertEquals(if (version == 2) 17L else null, event.value)
            assertEquals(if (version == 2) 4L else 0L, event.revision)
            assertNull(event.notes)
            if (version == 2) {
                val receipt = repo.getReceipt("old-request").getOrThrow()!!
                assertEquals(CalendarReceipt("old-request", "calendar_add", "old-event", "Старое событие"), receipt)
                assertEquals(receipt, repo.commit("old-request", CalendarMutation.Create(CalendarEventDraft("Дубль", 60000, 120000, notes = text))).getOrThrow())
            }
            val update = PrepareCalendarEventUpdateUseCase()(event, CalendarEventChanges(notes = text)).getOrThrow()
            assertEquals(text, repo.update(update).getOrThrow().notes)
        }
    }

    private fun createHistoricalDatabase(name: String, version: Int) {
        val resource = "com.example.aiassistent1.calendar.storage.android.local.CalendarDatabase/$version.json"
        val schema = JSONObject(java.io.File(System.getProperty("calendar.schemas"), resource).readText())
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            val entities = schema.getJSONObject("database").getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) {
                    database.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONObject("database").getJSONArray("setupQueries")
            for (i in 0 until setup.length()) database.execSQL(setup.getString(i))
            database.execSQL("INSERT INTO calendar_events (id,title,startsAtEpochMillis,endsAtEpochMillis,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('old-event','Старое событие',60000,120000,100,200)")
            if (version == 2) {
                database.execSQL("UPDATE calendar_events SET value=17, revision=4 WHERE id='old-event'")
                database.execSQL("INSERT INTO calendar_command_receipts (requestId,kind,eventId,title) VALUES ('old-request','calendar_add','old-event','Старое событие')")
            }
            database.version = version
        }
    }
}
