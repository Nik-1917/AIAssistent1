package com.example.aiassistent1.audio

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.repository.DataStoreSettingsRepository
import com.example.aiassistent1.domain.model.AudioPreferences
import com.example.aiassistent1.presentation.ui.MessageSentSoundSetting
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class MessageSentSoundSettingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchShowsAgreedTextAndSavesBothPositions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.cacheDir, "send-sound-ui-${UUID.randomUUID()}.preferences_pb")
        val data = PreferenceDataStoreFactory.create(scope = scope) { file }
        val settings = DataStoreSettingsRepository(data, scope)
        try {
            compose.setContent {
                val preferences: AudioPreferences? by settings.audioPreferences.collectAsStateWithLifecycle(initialValue = null)
                val edits = rememberCoroutineScope()
                MaterialTheme {
                    preferences?.let {
                        MessageSentSoundSetting(it.messageSentSoundEnabled, true) { enabled ->
                            edits.launch { settings.setMessageSentSoundEnabled(enabled) }
                        }
                    }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Звук отправки сообщения").assertIsDisplayed()
            compose.onNodeWithText("Короткий сигнал, когда сообщение принято в обработку").assertIsDisplayed()
            compose.onNode(isToggleable()).assertIsOn().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(isToggleable() and isOff()).fetchSemanticsNodes().size == 1 }
            assertFalse(settings.readAudioPreferences().messageSentSoundEnabled)
            compose.onNode(isToggleable()).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(isToggleable() and isOn()).fetchSemanticsNodes().size == 1 }
            assertTrue(settings.readAudioPreferences().messageSentSoundEnabled)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            file.delete()
        }
    }
}
