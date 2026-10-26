package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.aiassistent1.calendar.core.domain.CalendarRepeat
import com.example.aiassistent1.calendar.core.domain.CalendarTimeMode
import com.example.aiassistent1.domain.model.CalendarTimeZoneState
import com.example.aiassistent1.presentation.viewmodel.CalendarEventDraftUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class CalendarTimeModeUiTest {
    @get:Rule val compose = createComposeRule()
    private val zoneState = CalendarTimeZoneState(deviceZoneId = "Europe/Samara", selectedZoneId = "Europe/Moscow")
    private fun toggle() = compose.onNodeWithTag("calendar_time_mode_toggle").performScrollTo().performClick()
    private fun assertCollapsed() {
        compose.onNodeWithTag("calendar_time_mode_toggle").assertExists()
        compose.onNodeWithText("Фиксированный момент").assertDoesNotExist()
        compose.onNodeWithText("Всегда по местному времени").assertDoesNotExist()
        compose.onNodeWithText("Повторение: Один раз").assertDoesNotExist()
        compose.onNodeWithText("Привычное время").assertDoesNotExist()
    }

    @Test fun manualEditorStartsCollapsedAndTogglingRetainsFieldsAndSchedule() {
        var open by mutableStateOf(true)
        val saved = mutableListOf<CalendarEventEditorResult>()
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCalendarTimeZone provides zoneState) {
                if (open) CalendarEventEditorDialog(LocalDate.of(2026, 10, 20), null,
                    onDismiss = { open = false }, onSave = { _, _, _, _, _ -> }, onSaveWithSchedule = { saved.add(it) })
                else TextButton(onClick = { open = true }) { Text("Открыть") }
            }
        } }
        assertCollapsed()
        compose.onNode(hasSetTextAction() and hasText("Название")).performScrollTo().performTextReplacement("Зарядка")
        toggle()
        compose.onNodeWithText("Фиксированный момент").assertExists()
        compose.onNodeWithText("Привычное время").assertExists()
        compose.onNodeWithText("Всегда по местному времени").performScrollTo().performClick()
        compose.onNodeWithText("Повторение: Один раз").performScrollTo().performClick()
        compose.onNodeWithText("Каждый день").performClick()
        toggle()
        assertCollapsed()
        compose.onNodeWithText("Сохранить").performClick()
        compose.runOnIdle {
            assertEquals("Зарядка", saved.single().title)
            assertEquals(CalendarTimeMode.FLOATING, saved.single().scheduleInput.mode)
            assertEquals(CalendarRepeat.DAILY, saved.single().scheduleInput.repeat)
        }
        toggle()
        compose.onNodeWithText("Повторение: Каждый день").assertExists()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Открыть").performClick()
        assertCollapsed()
    }

    @Test fun chatEditorReopensCollapsedAndKeepsItsSelectedModeAndRepeat() {
        var open by mutableStateOf(true)
        var draft by mutableStateOf(CalendarEventDraftUiState(requestId = "draft", title = "Привычка",
            date = "2026-10-20", time = "09:00", timeZoneId = "Europe/Moscow", inputTimeZoneId = "Europe/Samara"))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCalendarTimeZone provides zoneState) {
                if (open) CalendarDraftEditorSheet(draft, onValueChange = { _, _ -> }, onNotesChange = {}, onFormat = {},
                    onVoiceInput = {}, onCreate = {}, onDelete = {}, onDismiss = { open = false },
                    onScheduleChange = { draft = draft.copy(scheduleInput = it) })
                else TextButton(onClick = { open = true }) { Text("Открыть") }
            }
        } }
        assertCollapsed()
        toggle()
        compose.onNodeWithText("Всегда по местному времени").performScrollTo().performClick()
        compose.onNodeWithText("Повторение: Один раз").performScrollTo().performClick()
        compose.onNodeWithText("По дням недели").performClick()
        compose.onNodeWithText("Пн").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Закрыть редактор").performClick()
        compose.onNodeWithText("Открыть").performClick()
        assertCollapsed()
        toggle()
        compose.onNodeWithText("Повторение: По дням недели").performScrollTo().assertExists()
        compose.runOnIdle {
            assertEquals(CalendarTimeMode.FLOATING, draft.scheduleInput.mode)
            assertEquals(setOf(java.time.DayOfWeek.MONDAY), draft.scheduleInput.weekdays)
            assertEquals("09:00", draft.time)
        }
    }
}
