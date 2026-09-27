package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AudioPreferencesTest {
    @Test fun legacyDefaultAndUnknownFutureEngineAreSafe() = runTest {
        val legacy = com.example.aiassistent1.domain.model.WakeWordEngine.LEGACY_ASR
        val fresh = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        assertEquals(legacy, fresh.readAudioPreferences().wakeWordEngine)
        val unknown = DataStoreSettingsRepository(TestPreferencesDataStore(preferencesOf(
            stringPreferencesKey("wake_word_engine") to "future-engine")), backgroundScope)
        assertEquals(legacy, unknown.readAudioPreferences().wakeWordEngine)
    }

    @Test fun engineSettingDoesNotEnableVoiceIdOrInvalidateOwner() = runTest {
        val repository = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        val before = repository.readAudioPreferences()
        repository.setWakeWordEngine(com.example.aiassistent1.domain.model.WakeWordEngine.METRIC_KWS_SHADOW)
        val after = repository.readAudioPreferences()
        assertEquals(before, after.copy(wakeWordEngine = before.wakeWordEngine, policyRevision = before.policyRevision))
        assertEquals(com.example.aiassistent1.domain.model.WakeWordEngine.METRIC_KWS_SHADOW, after.wakeWordEngine)
    }
    @Test fun enableTransitionsPreserveEnrollmentAndInvalidateOnlyActiveSession() = runTest {
        val r = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        assertFalse(r.readAudioPreferences().voiceIdEnabled)
        r.setVoiceIdEnabled(true)
        val first = r.readAudioPreferences()
        assertFalse(first.needsEnrollment) // Actual missing/corrupt file is checked by VoiceProfileManager.
        assertEquals(0L, first.revision)
        assertTrue(r.completeVoiceEnrollment(first.revision))
        r.setVoiceIdEnabled(true)
        assertFalse(r.readAudioPreferences().needsEnrollment)
        r.setVoiceIdEnabled(false)
        r.setVoiceIdEnabled(true)
        assertFalse(r.readAudioPreferences().needsEnrollment)
        assertEquals(first.revision, r.readAudioPreferences().revision)
        assertTrue(r.readAudioPreferences().policyRevision > first.policyRevision)
    }
    @Test fun nameChangeDoesNotInvalidateIndependentVoiceEnrollment() = runTest {
        val r = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        r.setVoiceIdEnabled(true)
        val oldRevision = r.readAudioPreferences().revision
        r.setCustomWakeWord("Алиса")
        assertTrue(r.completeVoiceEnrollment(oldRevision))
        assertFalse(r.readAudioPreferences().needsEnrollment)
        assertEquals(oldRevision, r.readAudioPreferences().revision)
    }
    @Test fun summarySettingAndNormalizedUnchangedNameDoNotResetProfile() = runTest {
        val r = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        r.setVoiceIdEnabled(true)
        r.completeVoiceEnrollment(0)
        r.setAutoSummaryEnabled(true)
        r.setCustomWakeWord("  Ассистент  ")
        assertEquals(0L, r.readAudioPreferences().revision)
        assertFalse(r.readAudioPreferences().needsEnrollment)
    }
    @Test fun nameChangeWhileDisabledDoesNotEnroll() = runTest {
        val r = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        r.setCustomWakeWord("Алиса")
        assertFalse(r.readAudioPreferences().needsEnrollment)
        assertEquals(0L, r.readAudioPreferences().revision)
    }
    @Test fun voiceCanBeRecordedWhileItsVerificationIsDisabled() = runTest {
        val r = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        r.setVoiceIdEnabled(true)
        r.setVoiceIdEnabled(false)
        assertTrue(r.completeVoiceEnrollment(0))
        assertFalse(r.readAudioPreferences().voiceIdEnabled)
    }

    @Test fun explicitDeletionInvalidatesAnInFlightVoiceEnrollment() = runTest {
        val r = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        val previous = r.readAudioPreferences().revision
        r.setVoiceIdNeedsEnrollment(true)
        assertFalse(r.completeVoiceEnrollment(previous))
        assertTrue(r.readAudioPreferences().needsEnrollment)
    }

    @Test fun activationModesAndVoiceIdAreIndependentAndSurviveRoundTrip() = runTest {
        val store = TestPreferencesDataStore()
        val r = DataStoreSettingsRepository(store, backgroundScope)
        for (mode in com.example.aiassistent1.domain.model.VoiceActivationMode.entries) for (voice in listOf(false, true)) {
            r.setVoiceActivationMode(mode)
            r.setVoiceIdEnabled(voice)
            val restored = DataStoreSettingsRepository(store, backgroundScope).readAudioPreferences()
            assertEquals(mode, restored.activationMode)
            assertEquals(voice, restored.voiceIdEnabled)
            assertEquals(0L, restored.revision)
        }
    }
    @Test fun securitySnapshotReadsPersistedValuesBeforeStateFlowsStart() = runTest {
        val r = DataStoreSettingsRepository(TestPreferencesDataStore(preferencesOf(
            booleanPreferencesKey("voice_id_enabled") to true,
            booleanPreferencesKey("voice_id_needs_enrollment") to true,
            longPreferencesKey("voice_id_revision") to 9L)), backgroundScope)
        val prefs = r.readAudioPreferences()
        assertTrue(prefs.voiceIdEnabled)
        assertTrue(prefs.needsEnrollment)
        assertEquals(9L, prefs.revision)
    }
    @Test fun invalidNameDoesNotMutatePreferences() = runTest {
        val r = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        for (word in listOf("", "x", "name@<blank>", "../model", "a".repeat(41))) {
            assertTrue(runCatching { r.setCustomWakeWord(word) }.isFailure)
        }
        assertEquals("Ассистент", r.readAudioPreferences().wakeWord)
    }
}
