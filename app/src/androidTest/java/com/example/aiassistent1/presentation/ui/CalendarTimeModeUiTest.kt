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
import com.example.aiassistent1.domain.model.CalendarCreationMode
import com.example.aiassistent1.domain.model.CalendarScheduleInput
import com.example.aiassistent1.presentation.viewmodel.CalendarEventDraftUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class CalendarTimeModeUiTest {
    @get:Rule val compose = createComposeRule()
    private val zoneState = CalendarTimeZoneState(deviceZoneId = "Europe/Samara", selectedZoneId = "Europe/Moscow")
    private fun assertModeControlsAbsent() {
        compose.onNodeWithText("Режим времени").assertDoesNotExist()
        compose.onNodeWithText("Фиксированный момент").assertDoesNotExist()
        compose.onNodeWithText("Всегда по местному времени").assertDoesNotExist()
        compose.onNodeWithText("По времени телефона").assertDoesNotExist()
        compose.onNodeWithText("Домашний пояс").assertDoesNotExist()
    }

    @Test fun manualEditorCapturesPolicyOnceAndReopeningTakesNewPolicy() {
        var open by mutableStateOf(true)
        var zones by mutableStateOf(zoneState.copy(creationMode = CalendarCreationMode.LOCAL_TIME))
        val saved = mutableListOf<CalendarEventEditorResult>()
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCalendarTimeZone provides zones) {
                if (open) CalendarEventEditorDialog(LocalDate.of(2026, 10, 20), null,
                    onDismiss = { open = false }, onSave = { _, _, _, _, _ -> }, onSaveWithSchedule = { saved.add(it) })
                else TextButton(onClick = { open = true }) { Text("Открыть") }
            }
        } }
        assertModeControlsAbsent()
        compose.onNode(hasSetTextAction() and hasText("Название")).performScrollTo().performTextReplacement("Зарядка")
        compose.runOnIdle { zones = zoneState }
        compose.onNodeWithText("Повторение: Один раз").performScrollTo().performClick()
        compose.onNodeWithText("Каждый день").performClick()
        compose.onNodeWithText("Сохранить").performClick()
        compose.runOnIdle {
            assertEquals("Зарядка", saved.single().title)
            assertEquals(CalendarTimeMode.FLOATING, saved.single().scheduleInput.mode)
            assertEquals(CalendarRepeat.DAILY, saved.single().scheduleInput.repeat)
        }
        compose.onNodeWithText("Повторение: Каждый день").assertExists()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Открыть").performClick()
        assertModeControlsAbsent()
        compose.onNode(hasSetTextAction() and hasText("Название")).performScrollTo().performTextReplacement("Встреча")
        compose.onNodeWithText("Сохранить").performClick()
        compose.runOnIdle {
            assertEquals(CalendarTimeMode.FIXED, saved.last().scheduleInput.mode)
            assertEquals("Europe/Samara", saved.last().inputTimeZoneId)
        }
    }

    @Test fun manualEditorOpenedDuringFirstReadUsesRestoredCalendarZoneAndKeepsTypedTitle() {
        var zones by mutableStateOf(CalendarTimeZoneState(deviceZoneId = "Europe/Moscow", isLoaded = false))
        var editorZone by mutableStateOf(zones.zoneId)
        var result: CalendarEventEditorResult? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCalendarTimeZone provides zones) {
                CalendarEventEditorDialog(LocalDate.of(2026, 10, 20), null, onDismiss = {}, onSave = { _, _, _, _, _ -> },
                    zoneId = editorZone, onZoneSelected = { editorZone = it }, onSaveWithSchedule = { result = it })
            }
        } }
        compose.onNode(hasSetTextAction() and hasText("Название")).performScrollTo().performTextReplacement("Звонок")
        compose.runOnIdle { zones = zones.copy(isLoaded = true, selectedZoneId = "Europe/Samara",
            creationMode = CalendarCreationMode.FIXED_MOMENT) }
        compose.waitUntil(5_000) { editorZone.id == "Europe/Samara" }
        compose.onNodeWithText("Часовой пояс изменился").assertDoesNotExist()
        compose.onNodeWithTag("calendar_input_time_preview").assertDoesNotExist()
        compose.onNodeWithText("Сохранить").performClick()
        compose.runOnIdle {
            assertEquals("Звонок", result?.title)
            assertEquals("Europe/Samara", result?.inputTimeZoneId)
        }
    }

    @Test fun chatEditorKeepsSavedModeAndRepeatAcrossReopening() {
        var open by mutableStateOf(true)
        var draft by mutableStateOf(CalendarEventDraftUiState(requestId = "draft", title = "Привычка",
            date = "2026-10-20", time = "09:00", timeZoneId = "Europe/Moscow", inputTimeZoneId = "Europe/Samara",
            scheduleInput = CalendarScheduleInput(mode = CalendarTimeMode.FLOATING)))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCalendarTimeZone provides zoneState) {
                if (open) CalendarDraftEditorSheet(draft, onValueChange = { _, _ -> }, onNotesChange = {}, onFormat = {},
                    onVoiceInput = {}, onCreate = {}, onDelete = {}, onDismiss = { open = false },
                    onScheduleChange = { draft = draft.copy(scheduleInput = it) })
                else TextButton(onClick = { open = true }) { Text("Открыть") }
            }
        } }
        assertModeControlsAbsent()
        compose.onNodeWithText("Повторение: Один раз").performScrollTo().performClick()
        compose.onNodeWithText("По дням недели").performClick()
        compose.onNodeWithText("Пн").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Закрыть редактор").performClick()
        compose.onNodeWithText("Открыть").performClick()
        assertModeControlsAbsent()
        compose.onNodeWithText("Повторение: По дням недели").performScrollTo().assertExists()
        compose.runOnIdle {
            assertEquals(CalendarTimeMode.FLOATING, draft.scheduleInput.mode)
            assertEquals(setOf(java.time.DayOfWeek.MONDAY), draft.scheduleInput.weekdays)
            assertEquals("09:00", draft.time)
        }
    }
}
