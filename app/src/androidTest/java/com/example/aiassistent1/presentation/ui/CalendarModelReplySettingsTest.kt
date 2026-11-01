package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.aiassistent1.domain.model.ModelParameterProfiles
import com.example.aiassistent1.domain.model.ModelProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CalendarModelReplySettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun calendarSectionSwitchUsesCurrentPreferenceAfterReopening() {
        var showSettings by mutableStateOf(true)
        var enabled by mutableStateOf(true)
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                if (showSettings) {
                    ModelSettingsDialog(
                        profiles = ModelParameterProfiles(), initialProfile = ModelProfile.CALENDAR, paramsLoaded = true,
                        hideCurrentMonth = false, onHideCurrentMonthChange = {},
                        hideCurrentYear = true, onHideCurrentYearChange = {}, smoothResponseEnabled = false,
                        dialogueModeEnabled = false, autoPlaybackEnabled = true, speechRate = 1f,
                        onDismiss = { showSettings = false }, onParamsChange = { _, _ -> },
                        onSmoothResponseChange = {}, onDialogueModeChange = {}, onAutoPlaybackChange = {},
                        onSpeechRateChange = {}, calendarModelReplyEnabled = enabled,
                        onCalendarModelReplyChange = { enabled = it; changes += it },
                    )
                }
            }
        }
        val toggle = compose.onNodeWithContentDescription("Включить реплику модели при ответе")
        compose.onNodeWithText("Чат календаря").performScrollTo().performClick()
        toggle.performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { showSettings = false }
        toggle.assertDoesNotExist()
        compose.runOnIdle { showSettings = true }
        compose.onNodeWithText("Чат календаря").performScrollTo().performClick()
        toggle.performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(listOf(false, true), changes) }
    }
}
