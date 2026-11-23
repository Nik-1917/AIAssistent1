package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelParameterProfiles
import com.example.aiassistent1.domain.model.ModelProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ModelParameterResetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun calendarResetsEachParameterAndSavesOnClose() = verifyReset(ModelProfile.CALENDAR)

    @Test fun chatResetsEachParameterAndSavesOnClose() = verifyReset(ModelProfile.CHAT)

    private fun verifyReset(profile: ModelProfile) {
        val initial = ModelParameterProfiles(
            calendar = ModelProfile.CALENDAR.defaults.copy(temperature = 0.5f, topP = 0.6f,
                topK = 35, repeatPenalty = 1.4f),
            chat = ModelProfile.CHAT.defaults.copy(temperature = 0.9f, topP = 0.5f,
                topK = 45, repeatPenalty = 1.3f),
        )
        val saved = ModelProfile.entries.associateWith { initial[it] }.toMutableMap()
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    ModelSettingsDialog(
                        profiles = ModelParameterProfiles(saved.getValue(ModelProfile.CALENDAR), saved.getValue(ModelProfile.CHAT)),
                        initialProfile = profile, paramsLoaded = true,
                        hideCurrentMonth = true, onHideCurrentMonthChange = {},
                        hideCurrentYear = false, onHideCurrentYearChange = {},
                        smoothResponseEnabled = true, dialogueModeEnabled = false,
                        autoPlaybackEnabled = false, speechRate = 1f,
                        onDismiss = { visible.value = false },
                        onParamsChange = { changedProfile, params -> saved[changedProfile] = params },
                        onSmoothResponseChange = {}, onDialogueModeChange = {},
                        onAutoPlaybackChange = {}, onSpeechRateChange = {},
                    )
                }
            }
        }
        val defaults = profile.defaults
        val resets: List<Pair<String, (GenerationParams) -> GenerationParams>> = listOf(
            "Temperature" to { it.copy(temperature = defaults.temperature) },
            "Top P" to { it.copy(topP = defaults.topP) },
            "Top K" to { it.copy(topK = defaults.topK) },
            "Repeat Penalty" to { it.copy(repeatPenalty = defaults.repeatPenalty) },
        )
        var expected = initial[profile]
        val otherProfile = ModelProfile.entries.first { it != profile }
        for ((name, reset) in resets) {
            compose.onNodeWithText("Модель", useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription("Сбросить $name по умолчанию")
                .performScrollTo().performClick()
            expected = reset(expected)
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(expected, saved.getValue(profile))
                assertEquals(initial[otherProfile], saved.getValue(otherProfile))
                visible.value = true
            }
            compose.waitForIdle()
        }
    }
}
