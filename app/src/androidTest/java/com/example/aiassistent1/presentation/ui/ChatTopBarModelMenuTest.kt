package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.domain.model.StoredModel
import com.example.aiassistent1.presentation.viewmodel.ModelAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ChatTopBarModelMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyMenuOpensTheExistingModelPicker() {
        var pickerOpens = 0
        compose.setContent {
            MaterialTheme {
                ChatTopBar(
                    assistantName = "Ассистент", modelState = ModelState.Unloaded,
                    isProcessing = false, hasMessages = false,
                    modelAvailability = ModelAvailability.Missing,
                    selectedModel = "", availableModels = emptyList(),
                    onStop = {}, onClearChat = {}, onLoadModel = { pickerOpens++ },
                    onSelectModel = {}, onOpenSettings = {}, onOpenConferences = {},
                    audioStatus = null, isCalendarMode = false, onModeToggle = {},
                )
            }
        }

        compose.onNodeWithText("Ассистент").performClick()
        compose.onNodeWithText("Загрузить модель .gguf").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, pickerOpens) }
    }

    @Test fun fullMenuExplainsTheLimitAndRequiresConfirmationBeforeDeletion() {
        val models = (5 downTo 1).map { StoredModel("model$it.gguf", it * 1024L) }
        var pickerOpens = 0
        var deleted: String? = null
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                ChatTopBar(
                    assistantName = "Ассистент", modelState = ModelState.Ready,
                    isProcessing = false, hasMessages = false,
                    modelAvailability = ModelAvailability.Available,
                    selectedModel = "model5.gguf", availableModels = models,
                    onStop = {}, onClearChat = {}, onLoadModel = { pickerOpens++ },
                    onSelectModel = { selected = it }, onOpenSettings = {}, onOpenConferences = {},
                    audioStatus = null, isCalendarMode = false, onModeToggle = {},
                    onDeleteModel = { deleted = it },
                )
            }
        }

        compose.onNodeWithText("Ассистент").performClick()
        compose.onNodeWithText("model5").assertIsDisplayed()
        compose.onNodeWithText("model5.gguf").assertDoesNotExist()
        compose.onNodeWithText("Загрузить модель .gguf").performClick()
        compose.onNodeWithText("Достигнут предел моделей").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, pickerOpens) }
        compose.onNodeWithText("Понятно").performClick()

        compose.onNodeWithText("Ассистент").performClick()
        compose.onNodeWithContentDescription("Удалить модель model5").performClick()
        compose.runOnIdle { assertNull(deleted); assertNull(selected) }
        compose.onNodeWithText("Удалить модель?").assertIsDisplayed()
        compose.onNodeWithText("Удалить").performClick()
        compose.runOnIdle { assertEquals("model5.gguf", deleted) }
    }
}
