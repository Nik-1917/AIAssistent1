package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.aiassistent1.domain.model.AudioPreferences
import com.example.aiassistent1.domain.model.VoiceActivationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MessageSentSoundPreferenceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun newAndExistingSettingsDefaultToEnabledWithoutChangingOtherFeedback() = runTest {
        assertTrue(AudioPreferences().messageSentSoundEnabled)
        val fresh = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        assertTrue(fresh.readAudioPreferences().messageSentSoundEnabled)
        val existing = DataStoreSettingsRepository(TestPreferencesDataStore(preferencesOf(
            stringPreferencesKey("activation_sound_uri") to "content://test/activation",
            booleanPreferencesKey("haptic_feedback_enabled") to false,
        )), backgroundScope).readAudioPreferences()
        assertTrue(existing.messageSentSoundEnabled)
        assertEquals("content://test/activation", existing.soundUri)
        assertFalse(existing.haptics)
    }

    @Test fun switchIsSharedAcrossAllModesAndDoesNotRestartVoicePolicy() = runTest {
        val settings = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        settings.setActivationSoundUri("content://test/activation")
        settings.setHapticFeedbackEnabled(false)
        for (mode in VoiceActivationMode.entries) {
            settings.setVoiceActivationMode(mode)
            for (enabled in listOf(false, true)) {
                val before = settings.readAudioPreferences()
                settings.setMessageSentSoundEnabled(enabled)
                val after = settings.readAudioPreferences()
                assertEquals(enabled, after.messageSentSoundEnabled)
                assertEquals(before.inputPolicy(), after.inputPolicy())
                assertEquals(before.copy(messageSentSoundEnabled = enabled), after)
            }
        }
    }

    @Test fun bothSwitchPositionsSurviveReopeningActualPreferencesFile() = runTest {
        for (enabled in listOf(false, true)) {
            val snapshot = TestPreferencesDataStore()
            val settings = DataStoreSettingsRepository(snapshot, backgroundScope)
            settings.setMessageSentSoundEnabled(enabled)
            val file = File(temporaryFolder.newFolder(), "settings.preferences_pb")
            val writerJob = Job(backgroundScope.coroutineContext[Job])
            val writer = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(backgroundScope.coroutineContext + writerJob),
            ) { file }
            writer.updateData { snapshot.data.first() }
            writerJob.cancelAndJoin()
            val reopened = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            assertEquals(enabled, DataStoreSettingsRepository(reopened, backgroundScope)
                .readAudioPreferences().messageSentSoundEnabled)
        }
    }
}
