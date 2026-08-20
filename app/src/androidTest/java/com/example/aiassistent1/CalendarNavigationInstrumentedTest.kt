package com.example.aiassistent1

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class CalendarNavigationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun opensCalendarFromChatAndReturnsBack() {
        composeRule.onNodeWithContentDescription("Открыть календарь")
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithText("Мой календарь").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Вернуться в чат")
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithContentDescription("Открыть календарь").assertIsDisplayed()
    }
}
