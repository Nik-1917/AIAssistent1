package com.example.aiassistent1.audio

import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.domain.interfaces.*
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.presentation.ui.PersonalKeywordSettings
import com.example.aiassistent1.presentation.ui.VoiceProfileSettings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Compose interactions; controlled capture/status, no physical microphone. */
class MetricKwsSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val prefs = mutableStateOf(AudioPreferences())
    private val visible = mutableStateOf(true)
    private var ready = false
    private var voiceReady = false
    private var hold = false
    private var recordings = 0
    private var cancellations = 0
    private val recording = MutableStateFlow(false)
    private val controls = object : PersonalKeywordControls, VoiceProfileControls {
        override fun observeKeywordRecording() = recording
        override fun observeVoiceRecording() = recording
        override suspend fun keywordStatus() = PersonalKeywordStatus(true, ready, ready,
            if (ready) "Слово сохранено" else "Персональное слово не записано")
        override suspend fun enrollKeyword() {
            recordings++; recording.value = true
            try { if (hold) awaitCancellation() else ready = true }
            finally { recording.value = false; if (hold) cancellations++ }
        }
        override suspend fun deleteKeyword() { ready = false }
        override suspend fun selectActivationMode(mode: VoiceActivationMode) {
            check(mode != VoiceActivationMode.PERSONAL_WORD || ready)
            prefs.value = prefs.value.copy(wakeWordEnabled = mode != VoiceActivationMode.DIRECT,
                wakeWordEngine = if (mode == VoiceActivationMode.PERSONAL_WORD) WakeWordEngine.METRIC_KWS else WakeWordEngine.LEGACY_ASR)
        }
        override suspend fun voiceProfileStatus() = VoiceProfileStatus(voiceReady,
            if (voiceReady) "Профиль голоса сохранён" else "Запишите голос для Voice ID")
        override suspend fun enrollVoice() {
            recording.value = true
            try { if (hold) awaitCancellation() else voiceReady = true }
            finally { recording.value = false; if (hold) cancellations++ }
        }
        override suspend fun deleteVoice() { voiceReady = false }
        override suspend fun selectVoiceId(enabled: Boolean) { prefs.value = prefs.value.copy(voiceIdEnabled = enabled) }
    }

    @Before fun grantPermission() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
            "pm grant " + instrument.targetContext.packageName + " android.permission.RECORD_AUDIO")).use { it.readBytes() }
    }
    private fun show() = compose.setContent {
        MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (visible.value) {
                    PersonalKeywordSettings(controls, prefs.value, false)
                    VoiceProfileSettings(controls, prefs.value, false)
                }
            }
        }
    }
    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    @Test fun standaloneWordEnrollmentAndDeletionNeverSelectAnotherMode() {
        show()
        compose.onNodeWithText("Персональное слово v3").assertIsNotEnabled()
        click("Записать слово")
        click("Персональное слово v3")
        compose.onNodeWithText("Персональное слово v3").assertIsSelected()
        compose.runOnIdle { assertFalse(prefs.value.voiceIdEnabled) }
        click("Удалить слово")
        compose.onNodeWithText("Персональное слово v3").assertIsSelected()
        compose.onNodeWithText("Персональное слово не записано").assertExists()
        click("Обычный голосовой ввод")
        compose.onNodeWithText("Обычный голосовой ввод").assertIsSelected()
        compose.runOnIdle { assertEquals(1, recordings); assertFalse(ready) }
    }

    @Test fun allFourCombinationsAndNameModeKeepSeparateProfiles() {
        show()
        click("Записать голос")
        compose.onNodeWithText("Перезаписать голос").assertExists()
        compose.runOnIdle { assertTrue(voiceReady); assertFalse(ready); assertFalse(prefs.value.voiceIdEnabled) }
        compose.onNode(isToggleable()).performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("Обычный голосовой ввод").assertIsSelected()
        click("Записать слово")
        click("Персональное слово v3")
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNode(isToggleable()).performScrollTo().performClick().assertIsOff()
        compose.onNodeWithText("Персональное слово v3").assertIsSelected()
        click("Активация по имени")
        compose.runOnIdle { assertTrue(ready); assertTrue(voiceReady); assertFalse(prefs.value.voiceIdEnabled) }
        click("Персональное слово v3")
        click("Удалить голос")
        compose.runOnIdle { assertTrue(ready); assertFalse(voiceReady) }
        compose.onNodeWithText("Персональное слово v3").assertIsSelected()
    }

    @Test fun cancelAndDialogDisposalKeepThePreviousWord() {
        ready = true; hold = true
        show()
        click("Перезаписать слово")
        click("Отменить запись")
        compose.onNodeWithText("Запись остановлена. Состояние сохранённого слова обновлено.").assertExists()
        compose.runOnIdle { assertTrue(ready); assertEquals(1, cancellations) }
        click("Перезаписать слово")
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(ready); assertEquals(2, recordings); assertEquals(2, cancellations) }
    }

    @Test fun voiceEnrollmentCanBeCancelledWithoutTouchingWord() {
        ready = true; voiceReady = true; hold = true
        show()
        click("Перезаписать голос")
        click("Отменить запись голоса")
        compose.runOnIdle { assertTrue(ready); assertTrue(voiceReady); assertEquals(1, cancellations) }
        click("Перезаписать голос")
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(ready); assertTrue(voiceReady); assertEquals(2, cancellations) }
    }
}
