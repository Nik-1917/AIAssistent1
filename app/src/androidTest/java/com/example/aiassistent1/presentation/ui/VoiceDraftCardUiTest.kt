package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.aiassistent1.presentation.viewmodel.VoiceDraftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoiceDraftCardUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun counterUpdatesWithRecognizedTextAndFullDraftReopensWithoutRecording() {
        val state = mutableStateOf(VoiceDraftState().opened())
        compose.setContent {
            MaterialTheme {
                VoiceDraftCard(state.value, onDelete = {}, onSend = {}, onNoiseSuppressionChange = {},
                    onOpenDrafts = {}, onSave = {})
            }
        }
        compose.onNodeWithText("0 / 3000").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.appendTranscript("Текст") }
        compose.onNodeWithText("5 / 3000").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.appendTranscript("а".repeat(4000)).opened() }
        compose.onNodeWithText("3000 / 3000").assertIsDisplayed()
        compose.onNodeWithText("Голосовой черновик").assertIsDisplayed()
        compose.onNodeWithText("Запись").assertDoesNotExist()
    }

    @Test fun noiseSwitchAppearsAboveRecordingAndUpdatesItsState() {
        val state = mutableStateOf(VoiceDraftState().opened())
        compose.setContent {
            MaterialTheme {
                VoiceDraftCard(state.value, onDelete = {}, onSend = {}, onNoiseSuppressionChange = {
                    state.value = state.value.copy(noiseSuppressionEnabled = it)
                }, onOpenDrafts = {}, onSave = {})
            }
        }
        val label = compose.onNodeWithText("Шумоподавление: вкл").fetchSemanticsNode().boundsInRoot
        val recording = compose.onNodeWithText("Запись").fetchSemanticsNode().boundsInRoot
        assertTrue(label.bottom <= recording.top)
        compose.onNodeWithContentDescription("Шумоподавление голосового черновика").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Шумоподавление голосового черновика").assertIsOff()
        compose.onNodeWithText("Шумоподавление: выкл").assertIsDisplayed()
        compose.onNodeWithText("Запись").assertIsDisplayed()
    }

    @Test fun fullDraftCanBeOpenedWhenStartingTheMicrophoneIsUnavailable() {
        var openings = 0
        compose.setContent {
            MaterialTheme {
                InputPanel(textInputEnabled = false, microphoneEnabled = false,
                    isProcessing = false, isVoiceMode = false, text = "", onTextChange = {},
                    onSend = {}, onStop = {}, onVoiceTap = {}, onVoiceDraft = { openings++ },
                    isVoiceDraftFull = true)
            }
        }
        compose.onNodeWithContentDescription("Открыть голосовой черновик").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, openings) }
    }

    @Test fun draftsButtonIsBottomLeftAndSaveIsNextToSendWithIndependentActions() {
        var opens = 0
        var saves = 0
        var sends = 0
        compose.setContent {
            MaterialTheme {
                VoiceDraftCard(VoiceDraftState(text = "Неотправленный текст", isVisible = true),
                    onDelete = {}, onSend = { sends++ }, onNoiseSuppressionChange = {},
                    onOpenDrafts = { opens++ }, onSave = { saves++ })
            }
        }
        val drafts = compose.onNodeWithText("Дневники").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithText("Сохранить").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithContentDescription("Отправить голосовой черновик").fetchSemanticsNode().boundsInRoot
        assertTrue(drafts.right < save.left)
        assertTrue(save.right <= send.left)
        compose.onNodeWithText("Дневники").performClick()
        compose.onNodeWithText("Сохранить").performClick()
        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, saves)
            assertEquals(0, sends)
        }
    }

    @Test fun saveIsDisabledForEmptyTextAndAllTextMutationsWaitForStorage() {
        val busy = mutableStateOf(false)
        val state = mutableStateOf(VoiceDraftState(isVisible = true))
        compose.setContent {
            MaterialTheme {
                VoiceDraftCard(state.value, onDelete = {}, onSend = {}, onNoiseSuppressionChange = {},
                    onOpenDrafts = {}, onSave = {}, isStorageBusy = busy.value)
            }
        }
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.onNodeWithText("Дневники").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(text = "Текст"); busy.value = true }
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.onNodeWithText("Дневники").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Отправить голосовой черновик").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Удалить голосовой черновик").assertIsNotEnabled()
    }
}
