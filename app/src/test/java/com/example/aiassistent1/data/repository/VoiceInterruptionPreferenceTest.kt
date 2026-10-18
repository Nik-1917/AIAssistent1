package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
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

class VoiceInterruptionPreferenceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun savedChoiceIsSharedByActivationModesButCannotEnableDirectInterruption() = runTest {
        val store = TestPreferencesDataStore()
        val writer = DataStoreSettingsRepository(store, backgroundScope)
        assertFalse(writer.readAudioPreferences().allowVoiceInterruption)
        for (enabled in listOf(true, false)) {
            writer.setVoiceInterruptionEnabled(enabled)
            for (mode in VoiceActivationMode.entries) {
                writer.setVoiceActivationMode(mode)
                val restored = DataStoreSettingsRepository(store, backgroundScope).readAudioPreferences()
                assertEquals(enabled, restored.allowVoiceInterruption)
                assertEquals(enabled && mode != VoiceActivationMode.DIRECT, restored.voiceInterruptionEnabled)
            }
        }
    }

    @Test fun changingSwitchInvalidatesOnlyAudioPolicyAndRepeatingValueDoesNothing() = runTest {
        val repository = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        repository.setVoiceActivationMode(VoiceActivationMode.NAME)
        repository.setVoiceIdEnabled(true)
        val before = repository.readAudioPreferences()
        repository.setVoiceInterruptionEnabled(true)
        val enabled = repository.readAudioPreferences()
        assertEquals(before.policyRevision + 1, enabled.policyRevision)
        assertNotEquals(before.inputPolicy(), enabled.inputPolicy())
        assertEquals(before, enabled.copy(allowVoiceInterruption = false, policyRevision = before.policyRevision))
        repository.setVoiceInterruptionEnabled(true)
        assertEquals(enabled, repository.readAudioPreferences())
        repository.setVoiceInterruptionEnabled(false)
        val disabled = repository.readAudioPreferences()
        assertFalse(disabled.voiceInterruptionEnabled)
        assertEquals(enabled.policyRevision + 1, disabled.policyRevision)
        assertEquals(before, disabled.copy(policyRevision = before.policyRevision))
    }

    @Test fun bothSwitchPositionsSurviveReopeningActualPreferencesFile() = runTest {
        for (enabled in listOf(false, true)) {
            val snapshot = TestPreferencesDataStore(preferencesOf(booleanPreferencesKey("barge_in_enabled") to true))
            val repository = DataStoreSettingsRepository(snapshot, backgroundScope)
            repository.setVoiceActivationMode(VoiceActivationMode.NAME)
            repository.setVoiceInterruptionEnabled(true)
            repository.setVoiceInterruptionEnabled(enabled)
            val file = File(temporaryFolder.newFolder(), "settings.preferences_pb")
            val writerJob = Job(backgroundScope.coroutineContext[Job])
            val writerScope = CoroutineScope(backgroundScope.coroutineContext + writerJob)
            val writer = PreferenceDataStoreFactory.create(scope = writerScope) { file }
            // One complete disk write avoids mock Android renameTo limitations on Windows.
            writer.updateData { snapshot.data.first() }
            writerJob.cancelAndJoin()
            val reopened = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            val restored = DataStoreSettingsRepository(reopened, backgroundScope).readAudioPreferences()
            assertEquals(VoiceActivationMode.NAME, restored.activationMode)
            assertEquals(enabled, restored.allowVoiceInterruption)
            assertEquals(enabled, restored.voiceInterruptionEnabled)
        }
    }
}
