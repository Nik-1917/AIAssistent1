package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.presentation.viewmodel.ModelAvailability
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatRequestCheckingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun checkingCanBeCancelledAndGenerationControlsAppearOnlyAfterReadiness() {
        val checking = mutableStateOf(true)
        var cancellations = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    ChatTopBar(
                        assistantName = "Ассистент", modelState = if (checking.value) ModelState.Unloaded else ModelState.Ready,
                        isProcessing = true, hasMessages = false, modelAvailability = ModelAvailability.Available,
                        selectedModel = "", availableModels = emptyList(), onStop = { cancellations++ },
                        onClearChat = {}, onLoadModel = {}, onSelectModel = {}, onOpenSettings = {},
                        onOpenConferences = {}, audioStatus = null, isCalendarMode = false, onModeToggle = {},
                        isCheckingRequest = checking.value,
                    )
                    InputPanel(
                        textInputEnabled = false, microphoneEnabled = false, isProcessing = true,
                        isVoiceMode = false, onSend = {}, onStop = { cancellations++ },
                        onVoiceTap = {}, onVoiceLongPress = {}, isCheckingRequest = checking.value,
                    )
                }
            }
        }
        compose.onAllNodesWithContentDescription("Отменить запрос").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Остановить генерацию").assertCountEquals(0)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Отменить запрос")[0].performClick()
        compose.runOnIdle { assertEquals(1, cancellations); checking.value = false }
        compose.onAllNodesWithContentDescription("Отменить запрос").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Остановить генерацию").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Остановить генерацию")[1].performClick()
        compose.runOnIdle { assertEquals(2, cancellations) }
    }
}
