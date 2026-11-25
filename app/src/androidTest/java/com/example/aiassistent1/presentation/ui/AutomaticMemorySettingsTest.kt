package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.aiassistent1.domain.model.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString

class AutomaticMemorySettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun actualAllocationIsVisibleWithoutManualMemoryControls() {
        compose.setContent {
            MaterialTheme {
                ModelSettingsDialog(
                    profiles = ModelParameterProfiles(), initialProfile = ModelProfile.CHAT, paramsLoaded = true,
                    hideCurrentMonth = true, onHideCurrentMonthChange = {},
                    hideCurrentYear = false, onHideCurrentYearChange = {}, smoothResponseEnabled = true,
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
        compose.onNodeWithText("Размер ответа (входит в контекст):", substring = true).assertDoesNotExist()
    }
    @Test fun firstManualEntryShowsFixedPresetAndHidesAutomaticDescription() {
        compose.setContent {
            MaterialTheme {
                ModelSettingsDialog(
                    profiles = ModelParameterProfiles(), initialProfile = ModelProfile.CHAT, paramsLoaded = true,
                    hideCurrentMonth = true, onHideCurrentMonthChange = {},
                    hideCurrentYear = false, onHideCurrentYearChange = {}, smoothResponseEnabled = true,
                    dialogueModeEnabled = false, autoPlaybackEnabled = false, speechRate = 1f,
                    onDismiss = {}, onParamsChange = { _, _ -> }, onSmoothResponseChange = {},
                    onDialogueModeChange = {}, onAutoPlaybackChange = {}, onSpeechRateChange = {},
                )
            }
        }
        compose.onNodeWithText("Модель", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Подбирать контекст и пакет автоматически").performClick()
        compose.onNodeWithText("Размер ответа (входит в контекст): 1024 токенов").assertExists()
        compose.onNodeWithText("Контекст: 4096 токенов").assertExists()
        compose.onNodeWithText("По умолчанию: 4096").assertExists()
        compose.onNodeWithText("По умолчанию: 1024").assertExists()
        compose.onNodeWithContentDescription("Контекст: 4096 токенов")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(6144f) }
        compose.onNodeWithText("1/2").performScrollTo().performClick()
        compose.onNodeWithText("По умолчанию: 2048").assertExists()
        compose.onNodeWithText("По умолчанию: 1024").assertExists()
        compose.onNodeWithText("Контекст: 2048 токенов").assertExists()
        compose.onNodeWithContentDescription("Размер ответа (входит в контекст): 1024 токенов")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1536f) }
        compose.onNodeWithText("1/4").performScrollTo().performClick()
        compose.onNodeWithText("Контекст: 6144 токенов").assertExists()
        compose.onNodeWithText("Размер ответа (входит в контекст): 1536 токенов").assertExists()
        compose.onNodeWithContentDescription("Сбросить контекст по умолчанию").performScrollTo().performClick()
        compose.onNodeWithText("Контекст: 4096 токенов").assertExists()
        compose.onNodeWithText("1/2").performScrollTo().performClick()
        compose.onNodeWithText("Контекст: 3072 токенов").assertExists()
        compose.onNodeWithText("Размер ответа (входит в контекст): 1536 токенов").assertExists()
        compose.onNodeWithText("Размер пакета: 64 токенов").assertExists()
        compose.onNodeWithText("По умолчанию: 64 токенов").assertExists()
        compose.onNodeWithText("Приложение измеряет полный запрос", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Календарь").performScrollTo().performClick()
        compose.onNodeWithText("Автоматический подбор контекста").assertExists()
        compose.onNodeWithText("Размер пакета для календаря составляет 64 токена.", substring = true).assertExists()
        compose.onNodeWithContentDescription("Подбирать контекст и пакет автоматически").assertDoesNotExist()
        compose.onNodeWithText("Доля контекста для ответа").assertDoesNotExist()
        compose.onNodeWithText("Размер ответа (входит в контекст):", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Размер пакета:", substring = true).assertDoesNotExist()
        compose.onNode(hasText("Чат") and isSelectable()).performScrollTo().performClick()
        compose.onNodeWithText("Размер пакета: 64 токенов").assertExists()
    }
    @Test fun pastedLongChatTextReachesSendWithoutTruncation() {
        val text = "Длинный текст 🙂 12345. ".repeat(180)
        var sent: String? = null
        compose.setContent {
            MaterialTheme {
                var input by remember { mutableStateOf("") }
                InputPanel(true, false, false, false, text = input, onTextChange = { input = it },
                    onSend = { sent = input }, onStop = {},
                    onVoiceTap = {}, onVoiceDraft = {})
            }
        }
        showKeyboardForSending()
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.onNodeWithContentDescription("Отправить сообщение").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(text, sent) }
    }
    @Test fun oversizedDraftRemainsWholeAndCannotBeSent() {
        val text = "01234567890123456"
        var sent: String? = null
        compose.setContent {
            MaterialTheme {
                var input by remember { mutableStateOf("") }
                InputPanel(true, false, false, false, text = input, onTextChange = { input = it },
                    onSend = { sent = input }, onStop = {},
                    onVoiceTap = {}, onVoiceDraft = {}, maximumMessageLength = 16)
            }
        }
        showKeyboardForSending()
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.onNodeWithContentDescription("Отправить сообщение").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
        compose.onNodeWithText("Не более 16 символов. Текст сохранён в поле.").assertExists()
        compose.runOnIdle { assertNull(sent) }
    }

    private fun showKeyboardForSending() {
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Отправить сообщение").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
