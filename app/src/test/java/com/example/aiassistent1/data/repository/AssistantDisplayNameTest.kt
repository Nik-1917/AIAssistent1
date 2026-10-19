package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
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

    @Test fun editingDirectAndPersonalNamesNeverChangesAudioPreferenceOrPolicy() = runTest {
        val settings = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        settings.setCustomWakeWord("Алиса")
        for (mode in listOf(VoiceActivationMode.DIRECT, VoiceActivationMode.PERSONAL_WORD)) for (voiceId in listOf(false, true)) {
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
        val session = PersonalKeywordWakeSession(
            preferences = settings::readAudioPreferences,
            evaluate = { keywordChecks++; MetricKwsDecision.Accept },
            recognize = { settings.setAssistantDisplayName("Помощник"); "Создай заметку" },
            onActivation = {}, clock = { 100L },
        )
        assertEquals(PersonalWakeResult.Activated, session.handle(floatArrayOf(.1f), 100L))
        settings.setAssistantDisplayName("Алиса")
        assertEquals(PersonalWakeResult.Command("Создай заметку"), session.handle(floatArrayOf(.2f), 100L))
        assertEquals(1, keywordChecks)
    }

    @Test fun invalidDisplayNameCannotReplaceTheSavedName() = runTest {
        val settings = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        settings.setAssistantDisplayName("Алиса")
        for (value in listOf("a".repeat(41), "имя\u0000", "<имя>")) {
            assertTrue(runCatching { settings.setAssistantDisplayName(value) }.isFailure)
            assertEquals("Алиса", settings.assistantDisplayName.first())
        }
    }

    @Test fun oldActiveCallNameWinsOverDifferentHeaderWithoutChangingTheCall() = runTest {
        val store = TestPreferencesDataStore(preferencesOf(
            booleanPreferencesKey("wake_word_enabled") to true,
            stringPreferencesKey("custom_wake_word") to "Алиса",
            stringPreferencesKey("assistant_display_name") to "Старый заголовок",
        ))
        val settings = DataStoreSettingsRepository(store, backgroundScope)
        assertEquals("Алиса", settings.assistantDisplayName.first())
        assertEquals("Алиса", settings.readAudioPreferences().wakeWord)
        settings.setVoiceActivationMode(VoiceActivationMode.DIRECT)
        assertEquals("Алиса", settings.assistantDisplayName.first())
        assertEquals("Алиса", DataStoreSettingsRepository(store, backgroundScope).assistantDisplayName.first())
    }

    @Test fun singleNameFieldUpdatesHeaderCallAndPolicyAtomicallyOnlyInNameMode() = runTest {
        val store = TestPreferencesDataStore()
        val settings = DataStoreSettingsRepository(store, backgroundScope)
        settings.setAssistantDisplayName("Алиса")
        settings.setVoiceActivationMode(VoiceActivationMode.NAME)
        assertEquals("Алиса", settings.readAudioPreferences().wakeWord)
        val before = settings.readAudioPreferences()
        settings.setAssistantDisplayName("  Мой   помощник  ")
        val saved = store.data.first()
        assertEquals("Мой помощник", saved[stringPreferencesKey("assistant_display_name")])
        assertEquals("Мой помощник", saved[stringPreferencesKey("custom_wake_word")])
        assertEquals(before.policyRevision + 1, settings.readAudioPreferences().policyRevision)
        settings.setAssistantDisplayName("Мой помощник")
        assertEquals(before.policyRevision + 1, settings.readAudioPreferences().policyRevision)
        val restored = DataStoreSettingsRepository(store, backgroundScope)
        assertEquals(restored.assistantDisplayName.first(), restored.readAudioPreferences().wakeWord)
    }

    @Test fun invalidCallNameCannotPartiallyRenameHeaderOrSwitchModes() = runTest {
        val settings = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        settings.setVoiceActivationMode(VoiceActivationMode.NAME)
        val before = settings.readAudioPreferences()
        for (value in listOf("", "1", "---", "Имя123")) {
            assertTrue(runCatching { settings.setAssistantDisplayName(value) }.isFailure)
            assertEquals(before, settings.readAudioPreferences())
            assertEquals("Ассистент", settings.assistantDisplayName.first())
        }
        settings.setVoiceActivationMode(VoiceActivationMode.DIRECT)
        settings.setAssistantDisplayName("Имя123")
        assertTrue(runCatching { settings.setVoiceActivationMode(VoiceActivationMode.NAME) }.isFailure)
        assertEquals(VoiceActivationMode.DIRECT, settings.readAudioPreferences().activationMode)
        assertEquals("Имя123", settings.assistantDisplayName.first())
    }
}
