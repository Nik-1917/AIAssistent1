package com.example.aiassistent1.presentation.ui

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.aiassistent1.domain.model.StoredModel
import com.example.aiassistent1.presentation.viewmodel.ChatUiState
import com.example.aiassistent1.presentation.viewmodel.ModelAvailability
import com.example.aiassistent1.presentation.viewmodel.ModelStartup
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ModelStartupDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedModelStartsOnceAndOnlyReadinessClosesTheDialog() {
        val name = "test.gguf"
        val state = mutableStateOf(ChatUiState(modelAvailability = ModelAvailability.Missing,
            availableModels = listOf(StoredModel(name, 100))))
        var starts = 0
        compose.setContent {
            MaterialTheme {
                ModelStartupDialog(state.value, onStart = {
                    starts++
                    state.value = state.value.copy(modelStartup = ModelStartup.Loading(name, SystemClock.elapsedRealtime()))
                }, onSelect = {
                    state.value = state.value.copy(selectedModel = it, modelAvailability = ModelAvailability.Available)
                }, onDelete = {}, onImport = {})
            }
        }
        compose.onNodeWithText("Первый запуск может занять до минуты.").assertIsDisplayed()
        compose.onNodeWithText("Выбрать модель").performClick()
        compose.onNodeWithText("test").performClick()
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText("Запустить ИИ").performClick()
        compose.onNodeWithText("Запускаем модель — «test.gguf»").assertIsDisplayed()
        compose.onNodeWithText("Запустить ИИ").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, starts)
            state.value = state.value.copy(modelStartup = ModelStartup.Loading(name, SystemClock.elapsedRealtime() - 60_000))
        }
        compose.onNodeWithText("Загрузка продолжается…").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(modelStartup = ModelStartup.Failed("Ошибка чтения GGUF")) }
        compose.onNodeWithText("Ошибка чтения GGUF").assertIsDisplayed()
        compose.onNodeWithText("Запустить ИИ").performClick()
        compose.runOnIdle {
            assertEquals(2, starts)
            state.value = state.value.copy(modelStartup = ModelStartup.Ready)
        }
        compose.onNodeWithText("Запускаем модель", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Выбрать модель").assertDoesNotExist()
    }
}
