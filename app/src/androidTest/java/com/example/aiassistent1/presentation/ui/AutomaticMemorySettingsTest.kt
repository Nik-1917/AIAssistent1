package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.aiassistent1.domain.model.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString

class AutomaticMemorySettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun actualAllocationIsVisibleWithoutManualMemoryControls() {
        compose.setContent {
            MaterialTheme {
                ModelSettingsDialog(
                    profiles = ModelParameterProfiles(), initialProfile = ModelProfile.CHAT, paramsLoaded = true,
                    compactDatesEnabled = true, onCompactDatesChange = {}, smoothResponseEnabled = true,
                    dialogueModeEnabled = false, autoPlaybackEnabled = false, speechRate = 1f,
                    onDismiss = {}, onParamsChange = { _, _ -> }, onSmoothResponseChange = {},
                    onDialogueModeChange = {}, onAutoPlaybackChange = {}, onSpeechRateChange = {},
                    automaticGeneration = AutomaticGenerationState(GenerationTask.CHAT, 2048, 64, 100, 512, 1932,
                        generatedTokens = 120, stopReason = GenerationStopReason.EOS),
                )
            }
        }
        compose.onNodeWithText("Модель", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Подбирать контекст и пакет автоматически").assertExists()
        compose.onNodeWithText("вход: 100", substring = true).assertExists()
        compose.onNodeWithText("Доступно под ответ: 1932", substring = true).assertExists()
        compose.onNodeWithText("Лимит ответа: 256 токенов").assertDoesNotExist()
    }
    @Test fun firstManualEntryShowsFixedPresetAndHidesAutomaticDescription() {
        compose.setContent {
            MaterialTheme {
                ModelSettingsDialog(
                    profiles = ModelParameterProfiles(), initialProfile = ModelProfile.CHAT, paramsLoaded = true,
                    compactDatesEnabled = true, onCompactDatesChange = {}, smoothResponseEnabled = true,
                    dialogueModeEnabled = false, autoPlaybackEnabled = false, speechRate = 1f,
                    onDismiss = {}, onParamsChange = { _, _ -> }, onSmoothResponseChange = {},
                    onDialogueModeChange = {}, onAutoPlaybackChange = {}, onSpeechRateChange = {},
                )
            }
        }
        compose.onNodeWithText("Модель", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Подбирать контекст и пакет автоматически").performClick()
        compose.onNodeWithText("Лимит ответа: 256 токенов").assertExists()
        compose.onNodeWithText("Контекст: 1024 токенов").assertExists()
        compose.onNodeWithText("1/4").assertExists()
        compose.onNodeWithText("Приложение измеряет полный запрос", substring = true).assertDoesNotExist()
    }
    @Test fun pastedLongChatTextReachesSendWithoutTruncation() {
        val text = "Длинный текст 🙂 12345. ".repeat(180)
        var sent: String? = null
        compose.setContent {
            MaterialTheme {
                InputPanel(true, false, false, false, onSend = { sent = it }, onStop = {},
                    onVoiceTap = {}, onVoiceLongPress = {})
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertEquals(text, sent) }
    }
    @Test fun oversizedDraftRemainsWholeAndCannotBeSent() {
        val text = "01234567890123456"
        var sent: String? = null
        compose.setContent {
            MaterialTheme {
                InputPanel(true, false, false, false, onSend = { sent = it }, onStop = {},
                    onVoiceTap = {}, onVoiceLongPress = {}, maximumMessageLength = 16)
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
        compose.onNodeWithText("Не более 16 символов. Текст сохранён в поле.").assertExists()
        compose.runOnIdle { assertNull(sent) }
    }
}
