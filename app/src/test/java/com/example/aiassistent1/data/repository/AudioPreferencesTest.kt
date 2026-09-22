package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AudioPreferencesTest {
    @Test fun enrollmentRequiresActualEnableTransition() = runTest {
        val r = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        assertFalse(r.readAudioPreferences().voiceIdEnabled)
        r.setVoiceIdEnabled(true)
        val first = r.readAudioPreferences()
        assertTrue(first.needsEnrollment)
        assertEquals(1L, first.revision)
        assertTrue(r.completeVoiceEnrollment(first.revision))
        r.setVoiceIdEnabled(true)
        assertFalse(r.readAudioPreferences().needsEnrollment)
        r.setVoiceIdEnabled(false)
        r.setVoiceIdEnabled(true)
        assertTrue(r.readAudioPreferences().needsEnrollment)
        assertEquals(2L, r.readAudioPreferences().revision)
    }
    @Test fun renamingRejectsEnrollmentThatStartedWithOldName() = runTest {
        val r = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        r.setVoiceIdEnabled(true)
        val oldRevision = r.readAudioPreferences().revision
        r.setCustomWakeWord("Алиса")
        assertFalse(r.completeVoiceEnrollment(oldRevision))
        assertTrue(r.readAudioPreferences().needsEnrollment)
        assertEquals(oldRevision + 1, r.readAudioPreferences().revision)
    }
    @Test fun summarySettingAndNormalizedUnchangedNameDoNotResetProfile() = runTest {
        val r = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        r.setVoiceIdEnabled(true)
        r.completeVoiceEnrollment(1)
        r.setAutoSummaryEnabled(true)
        r.setCustomWakeWord("  Ассистент  ")
        assertEquals(1L, r.readAudioPreferences().revision)
        assertFalse(r.readAudioPreferences().needsEnrollment)
    }
    @Test fun nameChangeWhileDisabledDoesNotEnroll() = runTest {
        val r = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        r.setCustomWakeWord("Алиса")
        assertFalse(r.readAudioPreferences().needsEnrollment)
        assertEquals(0L, r.readAudioPreferences().revision)
    }
    @Test fun disablingDuringEnrollmentCannotCompleteIt() = runTest {
        val r = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        r.setVoiceIdEnabled(true)
        r.setVoiceIdEnabled(false)
        assertFalse(r.completeVoiceEnrollment(1))
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
