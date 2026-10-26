package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.aiassistent1.calendar.core.domain.CalendarTimeMode
import com.example.aiassistent1.domain.model.CalendarScheduleInput
import com.example.aiassistent1.domain.model.CalendarTimeZoneState
import com.example.aiassistent1.presentation.viewmodel.CalendarEventDraftUiState
import com.example.aiassistent1.presentation.viewmodel.CalendarEventField
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CalendarCompactTimeUiTest {
    @get:Rule val compose = createComposeRule()
    private val moscow = ZoneId.of("Europe/Moscow")
    private fun draft() = CalendarEventDraftUiState(requestId = "compact", title = "Встреча", date = "2026-10-20", time = "09:00",
        durationMinutes = 60, timeZoneId = moscow.id, inputTimeZoneId = moscow.id)

    @Test fun cardShowsOnlyUsefulConversionAndKeepsItsEnteredTime() {
        var draft by mutableStateOf(draft())
        compose.setContent { MaterialTheme { CalendarDraftCard(draft, onOpen = {}, onOpenSavedEvent = {}) } }
        compose.onNodeWithTag("calendar_input_time_preview").assertDoesNotExist()
        compose.runOnIdle { draft = draft.copy(inputTimeZoneId = "Europe/Samara") }
        compose.onNodeWithText("09:00 Самара → 08:00 Москва").assertIsDisplayed()
        compose.onNodeWithText("Ввод:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Событие:", substring = true).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("09:00", draft.time)
            draft = draft.copy(scheduleInput = CalendarScheduleInput(mode = CalendarTimeMode.FLOATING))
        }
        compose.onNodeWithTag("calendar_input_time_preview").assertDoesNotExist()
    }

    @Test fun manualEditorDoesNotRepeatItsMatchingZoneInsideOrOutsideSettings() {
        val state = CalendarTimeZoneState(deviceZoneId = moscow.id, homeZoneId = moscow.id)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCalendarTimeZone provides state) {
                CalendarEventEditorDialog(LocalDate.of(2026, 10, 20), null, onDismiss = {}, onSave = { _, _, _, _, _ -> })
            }
        } }
        compose.onNodeWithTag("calendar_input_time_preview").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("Окончание (ЧЧ:ММ)")).performScrollTo().assertTextContains("10:00")
        compose.onNodeWithTag("calendar_time_mode_toggle").performScrollTo().performClick()
        compose.onNodeWithText("Домашний пояс: Москва").performScrollTo().assertExists()
        compose.onNodeWithText("Пояс ввода этого черновика:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Пояс события:", substring = true).assertDoesNotExist()
    }

    @Test fun chatEditorRetainsEndWhenConversionIsHiddenAndCompactsBothEndpointsOtherwise() {
        var draft by mutableStateOf(draft())
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCalendarTimeZone provides CalendarTimeZoneState(deviceZoneId = moscow.id)) {
                CalendarDraftEditorSheet(draft, onValueChange = { _, _ -> }, onNotesChange = {}, onFormat = {}, onVoiceInput = {},
                    onCreate = {}, onDelete = {}, onDismiss = {})
            }
        } }
        fun scrollTo(text: String) {
            compose.onNodeWithTag("calendar_draft_editor_fields").performScrollToNode(hasText(text))
        }
        scrollTo("Окончание: 10:00")
        compose.onNodeWithText("Окончание: 10:00").assertIsDisplayed()
        compose.onNodeWithTag("calendar_input_time_preview").assertDoesNotExist()
        compose.runOnIdle { draft = draft.copy(scheduleInput = CalendarScheduleInput(mode = CalendarTimeMode.FLOATING)) }
        scrollTo("Окончание: 10:00")
        compose.onNodeWithText("Окончание: 10:00").assertIsDisplayed()
        compose.runOnIdle { draft = draft.copy(inputTimeZoneId = "Europe/Samara", scheduleInput = CalendarScheduleInput()) }
        scrollTo("09:00 Самара → 08:00 Москва")
        compose.onNodeWithText("09:00 Самара → 08:00 Москва").assertIsDisplayed()
        compose.onNodeWithText("Окончание: 10:00 Самара → 09:00 Москва").assertExists()
        compose.onNodeWithText("Окончание: 10:00").assertDoesNotExist()
        compose.runOnIdle { assertEquals("09:00", draft.fieldText(CalendarEventField.Time)) }
    }

    @Test fun hidingMatchingZonesKeepsStrictTimeErrorsVisibleWhileFloatingTimeNeedsNoConversion() {
        val berlin = ZoneId.of("Europe/Berlin")
        var start by mutableStateOf(LocalDateTime.parse("2026-03-29T02:30"))
        var end by mutableStateOf<LocalDateTime?>(null)
        var mode by mutableStateOf(CalendarTimeMode.FIXED)
        compose.setContent { MaterialTheme { CalendarInputTimePreview(start, end, berlin, berlin, mode) } }
        compose.onNodeWithTag("calendar_input_time_preview").assertDoesNotExist()
        compose.onNodeWithTag("calendar_input_time_error").assertIsDisplayed()
        compose.runOnIdle { start = LocalDateTime.parse("2026-03-29T01:30"); end = LocalDateTime.parse("2026-03-29T02:30") }
        compose.onNodeWithTag("calendar_input_time_error").assertDoesNotExist()
        compose.onNodeWithTag("calendar_input_end_time_error").assertIsDisplayed()
        compose.runOnIdle { mode = CalendarTimeMode.FLOATING }
        compose.onNodeWithTag("calendar_input_time_preview").assertDoesNotExist()
        compose.onNodeWithTag("calendar_input_end_time_error").assertDoesNotExist()
    }

    @Test fun matchingInputAndEventZonesDoNotHideRequiredCalendarZoneConfirmation() {
        val draft = draft().copy(timeZoneId = "Europe/Samara", inputTimeZoneId = "Europe/Samara")
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCalendarTimeZone provides CalendarTimeZoneState(deviceZoneId = moscow.id)) {
                CalendarDraftEditorSheet(draft, onValueChange = { _, _ -> }, onNotesChange = {}, onFormat = {}, onVoiceInput = {},
                    onCreate = {}, onDelete = {}, onDismiss = {})
            }
        } }
        compose.onNodeWithText("Создать событие").assertIsNotEnabled()
        compose.onNodeWithText("Требуется подтверждение пояса. Откройте «Режим времени».").assertExists()
        compose.onNodeWithTag("calendar_time_mode_toggle").performClick()
        compose.onNodeWithTag("calendar_draft_editor_fields").performScrollToNode(hasText("Оставить прежний пояс события"))
        compose.onNodeWithText("Оставить прежний пояс события").assertIsDisplayed()
        compose.onNodeWithText("Показывать в поясе: Москва").assertExists()
    }
}
