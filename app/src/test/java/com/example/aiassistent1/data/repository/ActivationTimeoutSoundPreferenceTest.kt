package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
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

class ActivationTimeoutSoundPreferenceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun defaultsOnForNewAndExistingSettingsIndependentlyOfSendSound() = runTest {
        assertTrue(AudioPreferences().activationTimeoutSoundEnabled)
        for (sendEnabled in listOf(false, true)) {
            val settings = DataStoreSettingsRepository(TestPreferencesDataStore(preferencesOf(
                booleanPreferencesKey("message_sent_sound_enabled") to sendEnabled,
            )), backgroundScope)
            val prefs = settings.readAudioPreferences()
            assertTrue(prefs.activationTimeoutSoundEnabled)
            assertEquals(sendEnabled, prefs.messageSentSoundEnabled)
        }
    }

    @Test fun changingSwitchPreservesActivationPolicyAndOtherFeedbackInAllModes() = runTest {
        val settings = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        settings.setActivationSoundUri("content://test/activation")
        settings.setMessageSentSoundEnabled(false)
        settings.setHapticFeedbackEnabled(false)
        for (mode in VoiceActivationMode.entries) {
            settings.setVoiceActivationMode(mode)
            for (enabled in listOf(false, true)) {
                val before = settings.readAudioPreferences()
                settings.setActivationTimeoutSoundEnabled(enabled)
                val after = settings.readAudioPreferences()
                assertEquals(before.copy(activationTimeoutSoundEnabled = enabled), after)
                assertEquals(before.inputPolicy(), after.inputPolicy())
            }
        }
    }

    @Test fun bothSwitchPositionsSurviveReopeningThePreferencesFile() = runTest {
        for (enabled in listOf(false, true)) {
            val snapshot = TestPreferencesDataStore()
            val settings = DataStoreSettingsRepository(snapshot, backgroundScope)
            settings.setActivationTimeoutSoundEnabled(enabled)
            val file = File(temporaryFolder.newFolder(), "settings.preferences_pb")
            val writerJob = Job(backgroundScope.coroutineContext[Job])
            val writer = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(backgroundScope.coroutineContext + writerJob),
            ) { file }
            writer.updateData { snapshot.data.first() }
            writerJob.cancelAndJoin()
            val reopened = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            assertEquals(enabled, DataStoreSettingsRepository(reopened, backgroundScope)
                .readAudioPreferences().activationTimeoutSoundEnabled)
        }
    }
}
