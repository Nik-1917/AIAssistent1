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
}
