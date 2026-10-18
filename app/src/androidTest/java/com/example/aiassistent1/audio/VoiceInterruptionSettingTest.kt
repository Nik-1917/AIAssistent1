package com.example.aiassistent1.audio

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.repository.DataStoreSettingsRepository
import com.example.aiassistent1.domain.model.AudioPreferences
import com.example.aiassistent1.domain.model.VoiceActivationMode
import com.example.aiassistent1.presentation.ui.VoiceInterruptionSetting
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated UI and real DataStore; no microphone, models or application preferences. */
class VoiceInterruptionSettingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun activationSwitchDefaultsOffSharesSavedChoiceAndIsAbsentInDirectMode() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.cacheDir, "interruption-ui-${UUID.randomUUID()}.preferences_pb")
        val data = PreferenceDataStoreFactory.create(scope = scope) { file }
        val settings = DataStoreSettingsRepository(data, scope)
        try {
            data.edit { it[booleanPreferencesKey("barge_in_enabled")] = true }
            settings.setVoiceActivationMode(VoiceActivationMode.NAME)
            compose.setContent {
                val preferences: AudioPreferences? by settings.audioPreferences.collectAsStateWithLifecycle(initialValue = null)
                val edits = rememberCoroutineScope()
                MaterialTheme {
                    preferences?.let { current ->
                        VoiceInterruptionSetting(current, true) { enabled ->
                            edits.launch { settings.setVoiceInterruptionEnabled(enabled) }
                        }
                    }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Прерывать ответ голосом").assertExists()
            compose.onNode(isToggleable()).assertIsOff().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(isToggleable() and isOn()).fetchSemanticsNodes().size == 1
            }
            assertTrue(settings.readAudioPreferences().allowVoiceInterruption)
            settings.setVoiceActivationMode(VoiceActivationMode.PERSONAL_WORD)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Чтобы остановить диктора, произнесите персональное слово, дождитесь сигнала и скажите команду.")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(isToggleable()).assertIsOn().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(isToggleable() and isOff()).fetchSemanticsNodes().size == 1 }
            assertFalse(settings.readAudioPreferences().allowVoiceInterruption)
            settings.setVoiceActivationMode(VoiceActivationMode.NAME)
            compose.waitForIdle()
            compose.onNode(isToggleable()).assertIsOff()
            // Even a persisted true value must not expose an enabled direct-mode switch.
            settings.setVoiceInterruptionEnabled(true)
            settings.setVoiceActivationMode(VoiceActivationMode.DIRECT)
            compose.waitUntil(5_000) { compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Прерывать ответ голосом").assertDoesNotExist()
            assertFalse(settings.readAudioPreferences().voiceInterruptionEnabled)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            file.delete()
        }
    }
}
