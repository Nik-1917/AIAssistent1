package com.example.aiassistent1.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.aiassistent1.domain.model.SavedTextDraft
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreVoiceDraftRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `saves restores and clears voice draft`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val repository = DataStoreVoiceDraftRepository(dataStore)

        repository.saveDraft("Первая сохраненная фраза")

        assertEquals(
            "Первая сохраненная фраза",
            DataStoreVoiceDraftRepository(dataStore).loadDraft(),
        )

        repository.clearDraft()

        assertEquals("", repository.loadDraft())
    }

    @Test fun `all working text survives closing and reopening the actual file`() = runBlocking {
        val file = File(temporary.root, "voice.preferences_pb")
        val text = "Первая фраза\n🙂 Вторая фраза с пробелами  ".repeat(100)
        withRepository(file) { it.saveDraft(text) }
        withRepository(file) { assertEquals(text, it.loadDraft()) }
    }

    @Test fun `archive and working clear persist together and survive reopening`() = runBlocking {
        val file = File(temporary.root, "archive.preferences_pb")
        val first = SavedTextDraft("first", "Первый отдельный черновик", 100)
        val second = SavedTextDraft("second", "Второй отдельный черновик", 200)
        withRepository(file) {
            it.saveDraft(first.text)
            assertEquals(listOf(first), it.archiveVoiceDraft(first))
            assertEquals("", it.loadDraft())
            it.saveDraft(second.text)
            assertEquals(listOf(second, first), it.archiveVoiceDraft(second))
        }
        withRepository(file) {
            assertEquals("", it.loadDraft())
            assertEquals(listOf(second, first), it.loadTextDrafts())
        }
    }

    @Test fun `clearing current text does not delete saved drafts after reopening`() = runBlocking {
        val file = File(temporary.root, "clear.preferences_pb")
        val saved = SavedTextDraft("saved", "Сохранённый", 100)
        withRepository(file) {
            it.saveDraft(saved.text)
            it.archiveVoiceDraft(saved)
            it.saveDraft("Удаляемый крестиком")
            it.clearDraft()
        }
        withRepository(file) {
            assertEquals("", it.loadDraft())
            assertEquals(listOf(saved), it.loadTextDrafts())
        }
    }

    @Test fun `archive retry is idempotent and preserves a newer working draft`() = runTest {
        val repository = DataStoreVoiceDraftRepository(InMemoryPreferencesDataStore())
        val saved = SavedTextDraft("same-id", "Сохранённый", 100)
        repository.saveDraft(saved.text)
        repository.archiveVoiceDraft(saved)
        repository.saveDraft("Уже новый текущий текст")

        assertEquals(listOf(saved), repository.archiveVoiceDraft(saved))
        assertEquals("Уже новый текущий текст", repository.loadDraft())
    }

    @Test fun `stale archive cannot discard different working text`() = runTest {
        val repository = DataStoreVoiceDraftRepository(InMemoryPreferencesDataStore())
        repository.saveDraft("Новый текст")
        try {
            repository.archiveVoiceDraft(SavedTextDraft("stale", "Старый текст", 100))
            fail("A stale archive must fail")
        } catch (_: IllegalStateException) { }
        assertEquals("Новый текст", repository.loadDraft())
        assertTrue(repository.loadTextDrafts().isEmpty())
    }

    @Test fun `a conflicting archive id cannot overwrite saved or current text`() = runTest {
        val repository = DataStoreVoiceDraftRepository(InMemoryPreferencesDataStore())
        val saved = SavedTextDraft("same-id", "Сохранённый", 100)
        repository.saveDraft(saved.text)
        repository.archiveVoiceDraft(saved)
        repository.saveDraft("Текущий")
        try {
            repository.archiveVoiceDraft(saved.copy(text = "Текущий"))
            fail("Conflicting ids must not overwrite text")
        } catch (_: IllegalStateException) { }
        assertEquals("Текущий", repository.loadDraft())
        assertEquals(listOf(saved), repository.loadTextDrafts())
    }

    @Test fun `failed transaction leaves working text and library intact`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreVoiceDraftRepository(store)
        repository.saveDraft("Не терять при ошибке записи")
        store.failCommit = true
        try {
            repository.archiveVoiceDraft(SavedTextDraft("failed", "Не терять при ошибке записи", 100))
            fail("The failed commit must propagate")
        } catch (_: IOException) { }
        assertEquals("Не терять при ошибке записи", repository.loadDraft())
        assertTrue(repository.loadTextDrafts().isEmpty())
    }

    @Test fun `corrupt library is not replaced with empty data during archive`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreVoiceDraftRepository(store)
        repository.saveDraft("Текущий текст")
        store.edit { it[stringPreferencesKey("saved_text_drafts")] = "broken json" }
        try {
            repository.archiveVoiceDraft(SavedTextDraft("new", "Текущий текст", 100))
            fail("Corrupt library must fail without clearing current text")
        } catch (_: org.json.JSONException) { }
        assertEquals("Текущий текст", repository.loadDraft())
    }

    @Test fun `deleting a saved draft preserves current text and other saved drafts`() = runTest {
        val repository = DataStoreVoiceDraftRepository(InMemoryPreferencesDataStore())
        val first = SavedTextDraft("first", "Первый", 100)
        val second = SavedTextDraft("second", "Второй", 200)
        for (draft in listOf(first, second)) {
            repository.saveDraft(draft.text)
            repository.archiveVoiceDraft(draft)
        }
        repository.saveDraft("Текущий текст")
        assertEquals(listOf(second), repository.deleteTextDraft(first.id))
        assertEquals("Текущий текст", repository.loadDraft())
    }

    @Test fun `read failure propagates instead of pretending the working text is empty`() = runTest {
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("read failed") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("No write expected")
        }
        try {
            DataStoreVoiceDraftRepository(store).loadDraft()
            fail("Read failure must propagate")
        } catch (_: IOException) { }
    }

    private suspend fun withRepository(file: File, block: suspend (DataStoreVoiceDraftRepository) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { file.absolutePath.toPath() },
                scope = scope,
            )
            block(DataStoreVoiceDraftRepository(store))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        var failCommit = false
        private val preferences = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = preferences

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val updatedPreferences = transform(preferences.value)
            if (failCommit) throw IOException("commit failed")
            preferences.value = updatedPreferences
            return updatedPreferences
        }
    }
}
