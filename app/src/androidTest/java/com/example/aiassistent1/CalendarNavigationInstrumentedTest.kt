package com.example.aiassistent1

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class CalendarNavigationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun opensCalendarFromChatAndReturnsBack() {
        returnToConversationIfNeeded()
        composeRule.onNodeWithContentDescription("Открыть календарь")
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithContentDescription("Вернуться в чат")
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithContentDescription("Открыть календарь").assertIsDisplayed()
    }

    @Test
    fun calendarPageSurvivesActivityRecreation() {
        returnToConversationIfNeeded()
        composeRule.onNodeWithContentDescription("Открыть календарь").performClick()
        composeRule.onNodeWithContentDescription("Вернуться в чат").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithContentDescription("Вернуться в чат").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Открыть календарь").assertIsDisplayed()
    }

    private fun returnToConversationIfNeeded() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Вернуться в чат").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Открыть календарь").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithContentDescription("Вернуться в чат").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithContentDescription("Вернуться в чат").performClick()
        }
    }
}
