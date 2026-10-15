package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.presentation.viewmodel.ModelAvailability
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatTopBarActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chatModeUsesOneActionPosition() = verifyActionCycle(isCalendarMode = false)

    @Test fun calendarModeUsesOneActionPosition() = verifyActionCycle(isCalendarMode = true)

    private fun verifyActionCycle(isCalendarMode: Boolean) {
        var isProcessing by mutableStateOf(false)
        var isCheckingRequest by mutableStateOf(false)
        var stopCalls = 0
        var clearCalls = 0

        compose.setContent {
            MaterialTheme {
                ChatTopBar(
                    assistantName = "Ассистент",
                    modelState = ModelState.Ready,
                    isProcessing = isProcessing,
                    isCheckingRequest = isCheckingRequest,
                    hasMessages = true,
                    modelAvailability = ModelAvailability.Available,
                    selectedModel = "",
                    availableModels = emptyList(),
                    onStop = { stopCalls++ },
                    onClearChat = { clearCalls++ },
                    onLoadModel = {},
                    onSelectModel = {},
                    onOpenSettings = {},
                    onOpenConferences = {},
                    audioStatus = null,
                    isCalendarMode = isCalendarMode,
                    onModeToggle = {},
                )
            }
        }

        val trash = compose.onNodeWithContentDescription("Удалить чат")
        trash.assertIsDisplayed()
        val actionBounds = trash.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Отменить запрос").assertDoesNotExist()
        compose.onNodeWithContentDescription("Остановить генерацию").assertDoesNotExist()

        compose.runOnIdle {
            isProcessing = true
            isCheckingRequest = true
        }
        val cancel = compose.onNodeWithContentDescription("Отменить запрос")
        cancel.assertIsDisplayed()
        assertEquals(actionBounds, cancel.fetchSemanticsNode().boundsInRoot)
        trash.assertDoesNotExist()
        compose.onNodeWithContentDescription("Остановить генерацию").assertDoesNotExist()
        cancel.performClick()
        compose.runOnIdle {
            assertEquals(1, stopCalls)
            isCheckingRequest = false
        }

        val stop = compose.onNodeWithContentDescription("Остановить генерацию")
        stop.assertIsDisplayed()
        assertEquals(actionBounds, stop.fetchSemanticsNode().boundsInRoot)
        trash.assertDoesNotExist()
        cancel.assertDoesNotExist()
        stop.performClick()
        compose.runOnIdle {
            assertEquals(2, stopCalls)
            isProcessing = false
        }

        trash.assertIsDisplayed()
        assertEquals(actionBounds, trash.fetchSemanticsNode().boundsInRoot)
        cancel.assertDoesNotExist()
        stop.assertDoesNotExist()
        trash.performClick()
        compose.runOnIdle { assertEquals(1, clearCalls) }
    }
}
