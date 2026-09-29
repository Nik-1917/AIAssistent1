package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.aiassistent1.domain.model.AppDestination
import com.example.aiassistent1.domain.model.AppNavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSettingsRepositoryTest {
    @Test
    fun `context settings and message limit persist per model without saving working sizes`() = runTest {
        val store = TestPreferencesDataStore()
        val repository = DataStoreSettingsRepository(store, backgroundScope)
        val chosen = com.example.aiassistent1.domain.model.GenerationParams(
            contextSize = 2048, maxContextSize = 4096, autoContextEnabled = true, maxMessageLength = 6000,
        )
        repository.updateParamsForModel("first.gguf", chosen)
        val first = repository.getParamsForModel("first.gguf")
        val second = repository.getParamsForModel("second.gguf")
        runCurrent()
        assertEquals(chosen, first.value)
        assertEquals(3000, second.value.maxMessageLength)
        val budget = com.example.aiassistent1.domain.context.ContextWindowPolicy.plan(1400, first.value, 32768)
        assertEquals(4096, budget.contextSize)
        val reopened = DataStoreSettingsRepository(store, backgroundScope).getParamsForModel("first.gguf")
        runCurrent()
        assertEquals(chosen, reopened.value)
        repository.updateParamsForModel("first.gguf", chosen.copy(autoContextEnabled = false))
        runCurrent()
        assertEquals(false, first.value.autoContextEnabled)
    }

    @Test
    fun `legacy minimum and independent answer setting migrate to paired steps`() = runTest {
        val store = TestPreferencesDataStore(preferencesOf(
            androidx.datastore.preferences.core.intPreferencesKey("old.gguf_contextSize") to 512,
            androidx.datastore.preferences.core.intPreferencesKey("old.gguf_maxTokens") to 64,
        ))
        val repository = DataStoreSettingsRepository(store, backgroundScope)
        val saved = repository.getParamsForModel("old.gguf")
        runCurrent()
        assertEquals(1024, saved.value.contextSize)
        assertEquals(512, saved.value.maxTokens)
        assertEquals(true, saved.value.autoContextEnabled)
        assertEquals(8192, saved.value.maxContextSize)
        assertEquals(3000, saved.value.maxMessageLength)
        repository.updateParamsForModel("old.gguf", saved.value.copy(contextSize = 8192))
        runCurrent()
        assertEquals(4096, saved.value.maxTokens)
        assertEquals(null, store.data.first()[androidx.datastore.preferences.core.intPreferencesKey("old.gguf_maxTokens")])
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `old saved mode is restored when page preference does not yet exist`() = runTest {
        for (isCalendar in listOf(false, true)) {
            val store = TestPreferencesDataStore(preferencesOf(booleanPreferencesKey("system_prompt_enabled") to isCalendar))
            val repository = DataStoreSettingsRepository(store, backgroundScope)

            assertEquals(AppNavigationState(AppDestination.CHAT, isCalendar), repository.navigationState.first())
        }
    }

    @Test
    fun `new installation uses calendar conversation and unknown destination keeps saved mode`() = runTest {
        assertEquals(
            AppNavigationState(AppDestination.CHAT, true),
            DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope).navigationState.first(),
        )
        val store = TestPreferencesDataStore(preferencesOf(
            stringPreferencesKey("last_app_destination") to "UNKNOWN",
            booleanPreferencesKey("system_prompt_enabled") to false,
        ))
        assertEquals(
            AppNavigationState(AppDestination.CHAT, false),
            DataStoreSettingsRepository(store, backgroundScope).navigationState.first(),
        )
    }

    @Test
    fun `opening chat from calendar saves destination and mode in one emission`() = runTest {
        val repository = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        repository.setAppDestination(AppDestination.CALENDAR)
        val states = mutableListOf<AppNavigationState>()
        backgroundScope.launch { repository.navigationState.collect { states += it } }
        runCurrent()

        repository.setSystemPromptEnabled(false)
        runCurrent()

        assertEquals(listOf(
            AppNavigationState(AppDestination.CALENDAR, true),
            AppNavigationState(AppDestination.CHAT, false),
        ), states)
    }

    @Test
    fun `calendar page remembers conversation mode when returning`() = runTest {
        val repository = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        for (isCalendar in listOf(false, true)) {
            repository.setSystemPromptEnabled(isCalendar)
            repository.setAppDestination(AppDestination.CALENDAR)
            assertEquals(AppNavigationState(AppDestination.CALENDAR, isCalendar), repository.navigationState.first())
            repository.setAppDestination(AppDestination.CHAT)
            assertEquals(AppNavigationState(AppDestination.CHAT, isCalendar), repository.navigationState.first())
        }
    }

    @Test
    fun `restores all pages from the reopened actual preferences file`() = runTest {
        for (state in listOf(
            AppNavigationState(AppDestination.CHAT, false),
            AppNavigationState(AppDestination.CHAT, true),
            AppNavigationState(AppDestination.CALENDAR, false),
            AppNavigationState(AppDestination.CALENDAR, true),
        )) {
            val file = File(temporaryFolder.newFolder(), "settings.preferences_pb")
            val writerJob = Job(backgroundScope.coroutineContext[Job])
            val writerScope = CoroutineScope(backgroundScope.coroutineContext + writerJob)
            val writerStore = PreferenceDataStoreFactory.create(scope = writerScope, produceFile = { file })
            // Seed a disk snapshot in one transaction; repository mutations are tested above.
            writerStore.edit { preferences ->
                preferences[booleanPreferencesKey("system_prompt_enabled")] = state.isCalendarMode
                preferences[stringPreferencesKey("last_app_destination")] = state.destination.name
            }
            writerJob.cancelAndJoin()

            val readerStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
            val reader = DataStoreSettingsRepository(readerStore, backgroundScope)
            assertEquals(state, reader.navigationState.first())
        }
    }
    @Test
    fun `compact date mode defaults to compact and persists both switch positions`() = runTest {
        val store = TestPreferencesDataStore()
        val writer = DataStoreSettingsRepository(store, backgroundScope)
        runCurrent()
        assertEquals(true, writer.compactDatesEnabled.value)
        for (enabled in listOf(true, false)) {
            writer.setCompactDatesEnabled(enabled)
            runCurrent()
            assertEquals(enabled, writer.compactDatesEnabled.value)
            assertEquals(enabled, store.data.first()[booleanPreferencesKey("compact_dates_enabled")])
            val reader = DataStoreSettingsRepository(store, backgroundScope)
            runCurrent()
            assertEquals(enabled, reader.compactDatesEnabled.value)
        }
    }

    @Test
    fun `compact date mode restores both values from reopened preferences files`() = runTest {
        for (enabled in listOf(true, false)) {
            val file = File(temporaryFolder.newFolder(), "date-settings.preferences_pb")
            val writerJob = Job(backgroundScope.coroutineContext[Job])
            val writerScope = CoroutineScope(backgroundScope.coroutineContext + writerJob)
            val writerStore = PreferenceDataStoreFactory.create(scope = writerScope, produceFile = { file })
            // Single disk transaction, as in the navigation persistence test above.
            writerStore.edit { it[booleanPreferencesKey("compact_dates_enabled")] = enabled }
            writerJob.cancelAndJoin()
            val readerStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
            val reader = DataStoreSettingsRepository(readerStore, backgroundScope)
            readerStore.data.first()
            runCurrent()
            assertEquals(enabled, reader.compactDatesEnabled.value)
        }
    }
}
