package com.example.aiassistent1.audio

import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.domain.interfaces.PersonalKeywordControls
import com.example.aiassistent1.domain.interfaces.PersonalKeywordStatus
import com.example.aiassistent1.domain.model.AudioPreferences
import com.example.aiassistent1.domain.model.WakeWordEngine
import com.example.aiassistent1.presentation.ui.PersonalKeywordSettings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Compose controls and cancellation, fake capture only; permission is granted on the test device. */
class MetricKwsSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val prefs = mutableStateOf(AudioPreferences(voiceIdEnabled = true, wakeWordEnabled = true))
    private val visible = mutableStateOf(true)
    private var ready = false
    private var hold = false
    private var recordings = 0
    private var cancellations = 0
    private val recording = MutableStateFlow(false)
    private val controls = object : PersonalKeywordControls {
        override fun observeKeywordRecording() = recording
        override suspend fun keywordStatus() = PersonalKeywordStatus(true, ready, ready,
            if (ready) "Слово сохранено" else "Персональное слово не записано")
        override suspend fun enrollKeyword() {
            recordings++
            recording.value = true
            try { if (hold) awaitCancellation() else ready = true }
            finally { recording.value = false; if (hold) cancellations++ }
        }
        override suspend fun deleteKeyword() { ready = false; selectKeywordMode(false) }
        override suspend fun selectKeywordMode(enabled: Boolean) {
            check(!enabled || ready)
            prefs.value = prefs.value.copy(wakeWordEngine = if (enabled) WakeWordEngine.METRIC_KWS else WakeWordEngine.LEGACY_ASR)
        }
    }

    @Before fun grantPermission() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
            "pm grant ${instrument.targetContext.packageName} android.permission.RECORD_AUDIO")).use { it.readBytes() }
    }

    private fun show() = compose.setContent {
        MaterialTheme { Column { if (visible.value) PersonalKeywordSettings(controls, prefs.value, false) } }
    }

    @Test fun modeNeedsEnrollmentAndDeletionReturnsToLegacy() {
        show()
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.onNodeWithText("Записать слово").performClick()
        compose.onNodeWithText("Перезаписать слово").assertExists()
        compose.onNode(isToggleable()).assertIsEnabled().performClick().assertIsOn()
        compose.onNodeWithText("Удалить слово").performClick()
        compose.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, recordings); assertFalse(ready) }
    }

    @Test fun cancelAndDialogDisposalKeepThePreviousProfile() {
        ready = true; hold = true
        show()
        compose.onNodeWithText("Перезаписать слово").performClick()
        compose.onNodeWithText("Отменить запись").performClick()
        compose.onNodeWithText("Запись остановлена. Состояние сохранённого слова обновлено.").assertExists()
        compose.runOnIdle { assertTrue(ready); assertEquals(1, cancellations) }
        compose.onNodeWithText("Перезаписать слово").performClick()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(ready); assertEquals(2, recordings); assertEquals(2, cancellations) }
    }
}
