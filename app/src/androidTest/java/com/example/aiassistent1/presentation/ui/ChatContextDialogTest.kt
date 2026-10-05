package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.aiassistent1.domain.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatContextDialogTest {
    @get:Rule val compose = createComposeRule()
    private fun pressure(reason: ContextCapacityReason = ContextCapacityReason.MEMORY) = ChatContextPressure("current", listOf(
        ChatContextTurn(listOf(ChatMessage(id = "old", role = MessageRole.USER, content = "Старый вопрос"),
            ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = "Старый ответ")))
    ), reason)

    @Test fun automaticChoiceRequiresExplicitActionAndCanBeRemembered() {
        var result: ChatContextChoice? = null
        compose.setContent { MaterialTheme { ChatContextDialog(pressure()) { _, choice -> result = choice } } }
        compose.runOnIdle { assertNull(result) }
        compose.onNodeWithText("В дальнейшем делать это автоматически").performScrollTo().performClick()
        compose.onNodeWithTag("context_auto").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ChatContextChoice.Automatic(true), result) }
    }

    @Test fun manualChoiceSelectsTheWholeTurnAndEmptySelectionCannotProceed() {
        var result: ChatContextChoice? = null
        val state = pressure()
        compose.setContent { MaterialTheme { ChatContextDialog(state) { _, choice -> result = choice } } }
        compose.onNodeWithTag("context_manual").performScrollTo().performClick()
        compose.onNodeWithTag("context_apply").assertIsNotEnabled()
        compose.onNodeWithText("Старый вопрос").assertExists()
        compose.onNodeWithText("Старый ответ").assertExists()
        compose.onNodeWithTag("context_turn_old").performClick()
        compose.onNodeWithTag("context_apply").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ChatContextChoice.Manual(setOf("old")), result) }
    }

    @Test fun modelLimitIsNotPresentedAsRamFailureAndCancelDoesNotChooseAnyTurn() {
        var result: ChatContextChoice? = null
        val state = pressure(ContextCapacityReason.MODEL_LIMIT)
        compose.setContent { MaterialTheme { ChatContextDialog(state) { _, choice -> result = choice } } }
        compose.onNodeWithText("Достигнут предел контекста модели").assertExists()
        compose.onNodeWithTag("context_cancel").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ChatContextChoice.Cancel, result) }
    }
}
