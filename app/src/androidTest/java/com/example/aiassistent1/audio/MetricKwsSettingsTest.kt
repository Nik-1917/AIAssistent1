package com.example.aiassistent1.audio

import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.domain.interfaces.*
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.presentation.ui.VoiceActivationSettings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Compose interactions; controlled keyword capture/status, no physical microphone. */
class MetricKwsSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val prefs = mutableStateOf(AudioPreferences())
    private val name = mutableStateOf("AI Assistant")
    private val visible = mutableStateOf(true)
    private val busy = mutableStateOf(false)
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
            recordings++; recording.value = true
            try {
                if (hold) awaitCancellation()
                ready = true
                name.value = "Алиса"
            } finally { recording.value = false; if (hold) cancellations++ }
        }
        override suspend fun deleteKeyword() { ready = false }
        override suspend fun selectActivationMode(mode: VoiceActivationMode) {
            prefs.value = prefs.value.copy(wakeWordEnabled = mode != VoiceActivationMode.DIRECT,
                wakeWordEngine = if (mode == VoiceActivationMode.PERSONAL_WORD) WakeWordEngine.METRIC_KWS else WakeWordEngine.LEGACY_ASR)
        }
    }

    @Before fun grantPermission() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
            "pm grant " + instrument.targetContext.packageName + " android.permission.RECORD_AUDIO")).use { it.readBytes() }
    }

    private fun show() = compose.setContent {
        val scope = rememberCoroutineScope()
        MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (visible.value) VoiceActivationSettings(
                    controls, prefs.value, name.value, busy.value,
                    onSelectMode = { mode -> scope.launch { controls.selectActivationMode(mode) } },
                    onSaveName = { name.value = it },
                    onInterruptionChanged = { prefs.value = prefs.value.copy(allowVoiceInterruption = it) },
                    onBusyChanged = { busy.value = it },
                )
            }
        }
    }
    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
    private fun assertDescription(mode: VoiceActivationMode) {
        for (candidate in VoiceActivationMode.entries) {
            val node = compose.onNodeWithTag("voice_mode_description_${candidate.name}")
            if (candidate == mode) node.assertExists() else node.assertDoesNotExist()
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("Voice ID").assertDoesNotExist()
        compose.onNodeWithText("Записать голос").assertDoesNotExist()
        compose.onNodeWithText("Слово для режима «Активация по имени»").assertDoesNotExist()
        compose.onNodeWithText("Выбрать звук").assertDoesNotExist()
        compose.onNodeWithText("Звук активации: выбранный файл").assertDoesNotExist()
    }

    @Test fun everyModeShowsOnlyItsOwnDescriptionAndSettings() {
        prefs.value = prefs.value.copy(soundUri = "content://legacy/activation")
        show()
        assertDescription(VoiceActivationMode.DIRECT)
        compose.onNodeWithText("Записать слово").assertDoesNotExist()
        compose.onNodeWithText("Звук активации: как при отправке сообщения").assertDoesNotExist()
        compose.onNodeWithText("Прерывать ответ голосом").assertDoesNotExist()
        click("Активация по имени")
        assertDescription(VoiceActivationMode.NAME)
        compose.onNodeWithText("Записать слово").assertDoesNotExist()
        compose.onNodeWithText("Звук активации: как при отправке сообщения").assertExists()
        compose.onNodeWithText("Прерывать ответ голосом").assertExists()
        click("Персональное слово v3")
        assertDescription(VoiceActivationMode.PERSONAL_WORD)
        compose.onNodeWithText("Персональное слово v3").assertIsSelected()
        compose.onNodeWithText("Записать слово").assertExists()
        compose.onNodeWithText("Состояние: не готово к активации").assertExists()
        compose.onNodeWithText("Звук активации: как при отправке сообщения").assertExists()
        compose.onNodeWithText("Прерывать ответ голосом").assertExists()
        click("Обычный голосовой ввод")
        assertDescription(VoiceActivationMode.DIRECT)
        compose.onNodeWithText("Записать слово").assertDoesNotExist()
        compose.onNodeWithText("Звук активации: как при отправке сообщения").assertDoesNotExist()
    }

    @Test fun firstEnrollmentDeletionAndModeRoundTripRemainAccessible() {
        show()
        click("Персональное слово v3")
        click("Записать слово")
        compose.onNodeWithText("Состояние: готово к активации").assertExists()
        compose.onNode(hasText("Алиса") and hasSetTextAction()).assertExists()
        click("Активация по имени")
        click("Персональное слово v3")
        compose.onNodeWithText("Перезаписать слово").assertExists()
        click("Удалить слово")
        compose.onNodeWithText("Персональное слово v3").assertIsSelected()
        compose.onNodeWithText("Состояние: не готово к активации").assertExists()
        compose.onNodeWithText("Записать слово").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, recordings); assertFalse(ready) }
    }

    @Test fun cancelAndDialogDisposalKeepThePreviousWord() {
        ready = true; hold = true
        show()
        click("Персональное слово v3")
        click("Перезаписать слово")
        compose.onNodeWithText("Обычный голосовой ввод").assertIsNotEnabled()
        click("Отменить запись")
        compose.onNodeWithText("Запись остановлена. Состояние сохранённого слова обновлено.").assertExists()
        compose.runOnIdle { assertTrue(ready); assertEquals(1, cancellations) }
        compose.onNodeWithText("Обычный голосовой ввод").assertIsEnabled()
        click("Перезаписать слово")
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(ready); assertEquals(2, recordings); assertEquals(2, cancellations) }
    }

    @Test fun recordingRefreshesEvenAnUnsavedNameDraftAndTextEditsKeepTheWord() {
        show()
        click("Персональное слово v3")
        click("Записать слово")
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Черновик")
        click("Перезаписать слово")
        compose.onNode(hasText("Алиса") and hasSetTextAction()).assertExists()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("новое имя")
        click("Сохранить имя")
        compose.runOnIdle { assertEquals("Новое имя", name.value); assertTrue(ready); assertEquals(2, recordings) }
    }
}
