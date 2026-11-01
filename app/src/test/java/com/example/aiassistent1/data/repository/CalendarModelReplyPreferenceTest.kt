package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarModelReplyPreferenceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun newAndExistingSettingsEnableCalendarReplyByDefault() = runTest {
        for (preferences in listOf(preferencesOf(), preferencesOf(
            booleanPreferencesKey("system_prompt_enabled") to false,
            booleanPreferencesKey("auto_playback_enabled") to false,
        ))) {
            val repository = DataStoreSettingsRepository(TestPreferencesDataStore(preferences), backgroundScope)
            runCurrent()
            assertTrue(repository.calendarModelReplyEnabled.value)
        }
    }

    @Test fun switchingReplyVisibilityPreservesAllOtherSettings() = runTest {
        val original = preferencesOf(
            booleanPreferencesKey("system_prompt_enabled") to false,
            booleanPreferencesKey("auto_playback_enabled") to true,
            booleanPreferencesKey("smooth_response_enabled") to true,
            booleanPreferencesKey("dialogue_mode_enabled") to true,
        )
        val store = TestPreferencesDataStore(original)
        val repository = DataStoreSettingsRepository(store, backgroundScope)
        for (enabled in listOf(false, true)) {
            repository.setCalendarModelReplyEnabled(enabled)
            runCurrent()
            assertEquals(enabled, repository.calendarModelReplyEnabled.value)
            assertEquals(original.asMap(), store.data.first().asMap()
                .filterKeys { it != booleanPreferencesKey("calendar_model_reply_enabled") })
        }
    }

    @Test fun bothValuesSurviveReopeningActualPreferencesFile() = runTest {
        for (enabled in listOf(false, true)) {
            val file = File(temporaryFolder.newFolder(), "settings.preferences_pb")
            val writerJob = Job(backgroundScope.coroutineContext[Job])
            val writerScope = CoroutineScope(backgroundScope.coroutineContext + writerJob)
            val writerStore = PreferenceDataStoreFactory.create(scope = writerScope) { file }
            val writer = DataStoreSettingsRepository(writerStore, writerScope)
            writer.setCalendarModelReplyEnabled(enabled)
            writerJob.cancelAndJoin()

            val readerStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            val reader = DataStoreSettingsRepository(readerStore, backgroundScope)
            assertEquals(enabled, readerStore.data.first()[booleanPreferencesKey("calendar_model_reply_enabled")])
            runCurrent()
            assertEquals(enabled, reader.calendarModelReplyEnabled.value)
        }
    }
}
