package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.aiassistent1.domain.model.ScreenOrientation
import com.example.aiassistent1.domain.model.ScreenPreferences
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenPreferencesRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `existing installations default to unlocked portrait without writing preferences`() = runTest {
        val store = TestPreferencesDataStore()
        val repository = DataStoreSettingsRepository(store, backgroundScope)

        assertEquals(ScreenPreferences(), repository.screenPreferences.first())
        assertEquals(emptyMap<Any, Any>(), store.data.first().asMap())
    }

    @Test
    fun `first emission waits for saved settings instead of temporarily releasing the lock`() = runTest {
        val readGate = CompletableDeferred<Unit>()
        val store = TestPreferencesDataStore(preferencesOf(
            booleanPreferencesKey("screen_orientation_locked") to true,
            stringPreferencesKey("screen_orientation") to "LANDSCAPE",
        ), readGate)
        val repository = DataStoreSettingsRepository(store, backgroundScope)
        val first = async { repository.screenPreferences.first() }
        runCurrent()
        assertFalse(first.isCompleted)

        readGate.complete(Unit)
        assertEquals(ScreenPreferences(true, ScreenOrientation.LANDSCAPE), first.await())
    }

    @Test
    fun `orientation cannot change while unlocked and the saved choice survives toggling the lock`() = runTest {
        val repository = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        repository.setScreenOrientation(ScreenOrientation.LANDSCAPE)
        assertEquals(ScreenPreferences(), repository.screenPreferences.first())

        repository.setOrientationLocked(true)
        repository.setScreenOrientation(ScreenOrientation.LANDSCAPE)
        repository.setOrientationLocked(false)
        repository.setScreenOrientation(ScreenOrientation.PORTRAIT)
        assertEquals(ScreenPreferences(false, ScreenOrientation.LANDSCAPE), repository.screenPreferences.first())

        repository.setOrientationLocked(true)
        assertEquals(ScreenPreferences(true, ScreenOrientation.LANDSCAPE), repository.screenPreferences.first())
    }

    @Test
    fun `both orientations and lock positions survive reopening the preferences file`() = runTest {
        for (orientation in ScreenOrientation.entries) {
            for (locked in listOf(false, true)) {
                val savedStore = TestPreferencesDataStore()
                val writer = DataStoreSettingsRepository(savedStore, backgroundScope)
                writer.setOrientationLocked(true)
                writer.setScreenOrientation(orientation)
                writer.setOrientationLocked(locked)

                val file = File(temporaryFolder.newFolder(), "screen-settings.preferences_pb")
                val writerJob = Job(backgroundScope.coroutineContext[Job])
                val writerScope = CoroutineScope(backgroundScope.coroutineContext + writerJob)
                val writerStore = PreferenceDataStoreFactory.create(scope = writerScope, produceFile = { file })
                // Persist the exact setter output once, following the existing Windows disk-test pattern.
                writerStore.updateData { savedStore.data.first() }
                writerJob.cancelAndJoin()

                val readerStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
                val reader = DataStoreSettingsRepository(readerStore, backgroundScope)
                assertEquals(ScreenPreferences(locked, orientation), reader.screenPreferences.first())
            }
        }
    }

    @Test
    fun `unknown saved orientation falls back to portrait without discarding the lock`() = runTest {
        val store = TestPreferencesDataStore(preferencesOf(
            booleanPreferencesKey("screen_orientation_locked") to true,
            stringPreferencesKey("screen_orientation") to "unknown",
        ))
        val repository = DataStoreSettingsRepository(store, backgroundScope)
        assertEquals(ScreenPreferences(true, ScreenOrientation.PORTRAIT), repository.screenPreferences.first())
    }

    @Test
    fun `screen changes preserve all existing preferences`() = runTest {
        val original = preferencesOf(
            stringPreferencesKey("last_app_destination") to "CALENDAR",
            booleanPreferencesKey("system_prompt_enabled") to true,
            booleanPreferencesKey("wake_word_enabled") to true,
            booleanPreferencesKey("activation_voice_interruption_enabled") to true,
            booleanPreferencesKey("compact_dates_enabled") to false,
        )
        val store = TestPreferencesDataStore(original)
        val repository = DataStoreSettingsRepository(store, backgroundScope)
        val audio = repository.readAudioPreferences()
        repository.setOrientationLocked(true)
        repository.setScreenOrientation(ScreenOrientation.LANDSCAPE)
        repository.setOrientationLocked(false)

        assertEquals(audio, repository.readAudioPreferences())
        val saved = store.data.first().asMap()
        for ((key, value) in original.asMap()) assertEquals(value, saved[key])
    }
}
