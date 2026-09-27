package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.domain.usecase.PersonalKeywordWakeSession
import com.example.aiassistent1.domain.usecase.PersonalWakeResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AssistantDisplayNameTest {
    @Test fun existingActivationNameIsNotMigratedIntoTheDefaultHeader() = runTest {
        val store = TestPreferencesDataStore(preferencesOf(stringPreferencesKey("custom_wake_word") to "Алиса"))
        val settings = DataStoreSettingsRepository(store, backgroundScope)
        assertEquals("AI Assistant", settings.assistantDisplayName.first())
        assertEquals("Алиса", settings.readAudioPreferences().wakeWord)
        settings.setAssistantDisplayName("  Мой   помощник  ")
        assertEquals("Мой помощник", DataStoreSettingsRepository(store, backgroundScope).assistantDisplayName.first())
        settings.setAssistantDisplayName("  ")
        assertEquals("AI Assistant", settings.assistantDisplayName.first())
        assertEquals("Алиса", settings.readAudioPreferences().wakeWord)
    }

    @Test fun editingTheHeaderNeverChangesAnyAudioPreferenceOrPolicy() = runTest {
        val settings = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        settings.setCustomWakeWord("Алиса")
        for (mode in VoiceActivationMode.entries) for (voiceId in listOf(false, true)) {
            settings.setVoiceActivationMode(mode)
            settings.setVoiceIdEnabled(voiceId)
            val before = settings.readAudioPreferences()
            settings.setAssistantDisplayName("Помощник")
            settings.setAssistantDisplayName("Другое имя")
            assertEquals(before, settings.readAudioPreferences())
            assertEquals(before.inputPolicy(), settings.readAudioPreferences().inputPolicy())
        }
    }

    @Test fun editingNameBetweenWakeAndCommandDoesNotCloseTheV3CommandWindow() = runTest {
        val settings = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        settings.setVoiceActivationMode(VoiceActivationMode.PERSONAL_WORD)
        var keywordChecks = 0
        var speakerChecks = 0
        val session = PersonalKeywordWakeSession(
            preferences = settings::readAudioPreferences,
            evaluate = { keywordChecks++; MetricKwsDecision.Accept },
            verifySpeaker = { speakerChecks++; true },
            recognize = { settings.setAssistantDisplayName("Помощник"); "Создай заметку" },
            onActivation = {}, clock = { 100L },
        )
        assertEquals(PersonalWakeResult.Activated, session.handle(floatArrayOf(.1f), 100L))
        settings.setAssistantDisplayName("Алиса")
        assertEquals(PersonalWakeResult.Command("Создай заметку"), session.handle(floatArrayOf(.2f), 100L))
        assertEquals(1, keywordChecks)
        assertEquals(0, speakerChecks)
    }

    @Test fun invalidDisplayNameCannotReplaceTheSavedName() = runTest {
        val settings = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        settings.setAssistantDisplayName("Алиса")
        for (value in listOf("a".repeat(41), "имя\u0000", "<имя>")) {
            assertTrue(runCatching { settings.setAssistantDisplayName(value) }.isFailure)
            assertEquals("Алиса", settings.assistantDisplayName.first())
        }
    }
}
