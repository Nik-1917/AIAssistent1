package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.example.aiassistent1.domain.model.ModelParameterProfiles
import com.example.aiassistent1.domain.model.ModelProfile
import com.example.aiassistent1.domain.model.ScreenOrientation
import com.example.aiassistent1.domain.model.ScreenPreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScreenSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectorIsUnavailableWhileUnlockedAndRemembersItsChoice() {
        val preferences = mutableStateOf(ScreenPreferences())
        compose.setContent {
            MaterialTheme {
                ScreenSettingsControls(
                    preferences.value,
                    onOrientationLockedChange = { preferences.value = preferences.value.copy(orientationLocked = it) },
                    onOrientationChange = { preferences.value = preferences.value.copy(orientation = it) },
                    onProximityScreenOffChange = { preferences.value = preferences.value.copy(proximityScreenOffEnabled = it) },
                )
            }
        }
        val lock = compose.onNodeWithContentDescription("Фиксация ориентации экрана")
        val orientation = compose.onNodeWithContentDescription("Ориентация экрана")
        val proximity = compose.onNodeWithContentDescription("Гасить экран у уха")
        proximity.assertIsEnabled().assertIsOn()
        orientation.assertIsNotEnabled().assertIsOff().performTouchInput { click() }
        compose.runOnIdle { assertEquals(ScreenPreferences(), preferences.value) }
        proximity.performClick().assertIsOff()

        lock.performClick()
        orientation.assertIsEnabled().performClick().assertIsOn()
        compose.onNodeWithText("Горизонтально").assertExists()
        lock.performClick()
        orientation.assertIsNotEnabled().assertIsOn().performTouchInput { click() }
        proximity.assertIsEnabled().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(ScreenOrientation.LANDSCAPE, preferences.value.orientation) }
        lock.performClick()
        orientation.assertIsEnabled().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("Вертикально").assertExists()
    }

    @Test
    fun controlsWaitForSavedPreferences() {
        compose.setContent {
            MaterialTheme {
                ScreenSettingsControls(null, {}, {}, {})
            }
        }
        compose.onNodeWithContentDescription("Фиксация ориентации экрана").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Ориентация экрана").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Гасить экран у уха").assertIsNotEnabled()
    }

    @Test
    fun screenSectionRemainsExpandedAfterStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                ModelSettingsDialog(
                    profiles = ModelParameterProfiles(), initialProfile = ModelProfile.CHAT, paramsLoaded = true,
                    hideCurrentMonth = true, onHideCurrentMonthChange = {},
                    hideCurrentYear = false, onHideCurrentYearChange = {}, smoothResponseEnabled = false,
                    dialogueModeEnabled = false, autoPlaybackEnabled = false, speechRate = 1f,
                    onDismiss = {}, onParamsChange = { _, _ -> }, onSmoothResponseChange = {},
                    onDialogueModeChange = {}, onAutoPlaybackChange = {}, onSpeechRateChange = {},
                    screenPreferences = ScreenPreferences(true, ScreenOrientation.LANDSCAPE),
                )
            }
        }
        compose.onNodeWithContentDescription("Ориентация экрана").assertDoesNotExist()
        compose.onNodeWithText("Экран").performClick()
        compose.onNodeWithContentDescription("Ориентация экрана").assertIsEnabled().assertIsOn()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Ориентация экрана").assertIsEnabled().assertIsOn()
    }
}
