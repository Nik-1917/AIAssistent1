package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.aiassistent1.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InferenceSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun gpuLabelUsesTheActualApiAndLayerCount() {
        val actual = mutableStateOf(InferenceExecution(backend = InferenceBackend.OPENCL,
            deviceName = "Adreno", offloadedLayers = 29, totalLayers = 29))
        compose.setContent { MaterialTheme {
            InferenceSettingsControls(ModelProfile.CHAT, {}, ComputeMode.AUTO, {}, true, actual.value)
        } }
        compose.onNodeWithText("GPU · OpenCL").assertExists()
        compose.onNodeWithText("29 из 29").assertExists()
        compose.runOnIdle { actual.value = actual.value.copy(offloadedLayers = 1) }
        compose.onNodeWithText("GPU + CPU · OpenCL").assertExists()
        compose.runOnIdle { actual.value = actual.value.copy(backend = InferenceBackend.VULKAN) }
        compose.onNodeWithText("GPU + CPU · Vulkan").assertExists()
    }

    @Test fun computeInformationOpensFromItsOwnSettingsEntry() {
        var savedMode: ComputeMode? = null
        compose.setContent {
            MaterialTheme {
                ModelSettingsDialog(
                    profiles = ModelParameterProfiles(), initialProfile = ModelProfile.CHAT, paramsLoaded = true,
                    hideCurrentMonth = true, onHideCurrentMonthChange = {},
                    hideCurrentYear = false, onHideCurrentYearChange = {}, smoothResponseEnabled = true,
                    dialogueModeEnabled = false, autoPlaybackEnabled = false, speechRate = 1f,
                    onDismiss = {}, onParamsChange = { _, params -> savedMode = params.computeMode },
                    onSmoothResponseChange = {}, onDialogueModeChange = {}, onAutoPlaybackChange = {},
                    onSpeechRateChange = {},
                    inferenceExecution = InferenceExecution(backend = InferenceBackend.CPU,
                        modelName = "test.gguf", fallbackReason = "Тестовый отказ GPU"),
                )
            }
        }
        compose.onNodeWithText("Вычисления AI").performClick()
        compose.onNodeWithText("Вычислитель для следующих запросов").assertIsDisplayed()
        compose.onNodeWithText("Фактически используется").assertExists()
        compose.onNodeWithText("Тестовый отказ GPU").assertExists()
        compose.onNode(hasText("CPU") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(ComputeMode.CPU, savedMode) }
    }
}
