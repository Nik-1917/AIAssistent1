package com.example.aiassistent1.presentation.ui

import android.text.format.Formatter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.domain.model.StoredModel
import com.example.aiassistent1.presentation.viewmodel.ChatUiState
import com.example.aiassistent1.presentation.viewmodel.ModelAvailability
import com.example.aiassistent1.presentation.viewmodel.ModelStartup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ModelStartupDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedModelStartsOnceAndOnlyReadinessClosesTheDialog() {
        val model = StoredModel("calendar-assistant-v12.67-Qwen3-0.6B-Q8_0.gguf", 639_442_784L)
        val other = StoredModel("Other-1.5B.GGUF", 1_500_000_000L)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelLabel = "Qwen3-06B ${Formatter.formatShortFileSize(context, model.sizeBytes)}"
        val otherLabel = "Other-1.5B ${Formatter.formatShortFileSize(context, other.sizeBytes)}"
        val state = mutableStateOf(ChatUiState(modelAvailability = ModelAvailability.Missing,
            availableModels = listOf(other, model)))
        var starts = 0
        compose.setContent {
            MaterialTheme {
                ModelStartupDialog(state.value, onStart = {
                    starts++
                    state.value = state.value.copy(modelStartup = ModelStartup.Loading(state.value.selectedModel))
                }, onSelect = {
                    state.value = state.value.copy(selectedModel = it, modelAvailability = ModelAvailability.Available)
                }, onDelete = {}, onImport = {})
            }
        }
        val hint = compose.onNodeWithText("Первый запуск может занять до минуты.")
        hint.assertIsDisplayed()
        val chooseButton = compose.onNodeWithText("Выбрать модель").assertIsDisplayed()
        val startButton = compose.onNodeWithText("Запустить").assertIsDisplayed()
        val left = chooseButton.fetchSemanticsNode().boundsInRoot
        val right = startButton.fetchSemanticsNode().boundsInRoot
        assertTrue(left.right <= right.left)
        assertEquals(left.center.y, right.center.y, 1f)
        for (label in listOf("Выбрать модель", "Запустить")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            assertEquals(label, 1, layout.lineCount)
            assertFalse(label, layout.hasVisualOverflow)
        }
        startButton.performClick()
        compose.onNodeWithText(other.displayName).performClick()
        compose.onNodeWithText("Выбрана модель - $otherLabel").assertIsDisplayed()
        chooseButton.performClick()
        compose.onNodeWithText(model.displayName).performClick()
        val selected = compose.onNodeWithText("Выбрана модель - $modelLabel").assertIsDisplayed()
        val hintBounds = hint.fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("ИИ не запущен")
        val titleBounds = title.fetchSemanticsNode().boundsInRoot
        val selectedBounds = selected.fetchSemanticsNode().boundsInRoot
        val buttonBounds = startButton.fetchSemanticsNode().boundsInRoot
        val dialogBounds = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.bottom <= hintBounds.top)
        assertTrue(hintBounds.bottom <= selectedBounds.top)
        startButton.performClick()
        title.assertIsDisplayed()
        hint.assertIsDisplayed()
        selected.assertIsDisplayed()
        startButton.assertDoesNotExist()
        chooseButton.assertDoesNotExist()
        val progress = compose.onNode(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        ))
        progress.assertIsDisplayed()
        val progressBounds = progress.fetchSemanticsNode().boundsInRoot
        assertTrue(progressBounds.top >= buttonBounds.top)
        assertTrue(progressBounds.bottom <= buttonBounds.bottom)
        assertEquals(buttonBounds.center.y, progressBounds.center.y, 1f)
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        assertEquals(dialogBounds, compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot)
        assertEquals(titleBounds, title.fetchSemanticsNode().boundsInRoot)
        assertEquals(hintBounds, hint.fetchSemanticsNode().boundsInRoot)
        assertEquals(selectedBounds, selected.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            assertEquals(1, starts)
            state.value = state.value.copy(selectedModel = other.fileName)
        }
        selected.assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(modelStartup = ModelStartup.Failed("Ошибка чтения GGUF")) }
        compose.onNodeWithText("Ошибка чтения GGUF").assertIsDisplayed()
        startButton.performClick()
        compose.onNodeWithText("Выбрана модель - $otherLabel").assertIsDisplayed()
        progress.assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, starts)
            state.value = state.value.copy(modelStartup = ModelStartup.Ready)
        }
        title.assertDoesNotExist()
        hint.assertDoesNotExist()
        chooseButton.assertDoesNotExist()
        progress.assertDoesNotExist()
    }
}
