package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.aiassistent1.calendar.core.domain.*
import java.time.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CalendarFloatingScheduleUiTest {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.of(2030, 9, 12)
    private val results = mutableListOf<CalendarEventEditorResult>()
    private fun show(event: CalendarEvent? = null) {
        compose.setContent { MaterialTheme {
            CalendarEventEditorDialog(date, event, onDismiss = {}, onSave = { _, _, _, _, _ -> },
                onSaveWithSchedule = { results.add(it) })
        } }
    }
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo()
    private fun repeat(label: String) {
        compose.onNodeWithText("Повторение: Один раз").performScrollTo().performClick()
        compose.onNodeWithText(label).performClick()
    }
    private fun save() = compose.onNodeWithText("Сохранить").performClick()

    @Test fun floatingWeekdaysRequireSelectionAndRetainNineAndInclusiveUntil() {
        show()
        field("Название").performTextReplacement("Зарядка")
        compose.onNodeWithText("Всегда по местному времени").performScrollTo().performClick()
        repeat("По дням недели")
        save()
        compose.runOnIdle { assertTrue(results.isEmpty()) }
        compose.onNodeWithText("Выберите дни недели.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Пн").performScrollTo().performClick()
        compose.onNodeWithText("Ср").performScrollTo().performClick()
        compose.onNodeWithText("Пт").performScrollTo().performClick()
        compose.onNodeWithText("До даты").performScrollTo().performClick()
        field("Последний день (ГГГГ-ММ-ДД)").performTextReplacement("2030-10-31")
        save()
        compose.runOnIdle {
            val result = results.single()
            assertEquals(LocalTime.of(9, 0), result.startTime)
            assertEquals(CalendarTimeMode.FLOATING, result.scheduleInput.mode)
            val schedule = result.scheduleInput.build(date.atTime(result.startTime), date.atTime(result.endTime!!), ZoneId.of("Europe/Samara"))!!
            assertNull(schedule.zoneId)
            assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), schedule.recurrence.weekdays)
            assertEquals(LocalDate.of(2030, 10, 31), schedule.recurrence.until)
        }
    }

    @Test fun selectedDatesRejectInvalidTextWithoutSavingOlderValues() {
        show()
        field("Название").performTextReplacement("Привычка")
        compose.onNodeWithText("Всегда по местному времени").performScrollTo().performClick()
        repeat("В выбранные даты")
        field("Даты через запятую (ГГГГ-ММ-ДД)").performTextReplacement("2030-09-13, 2030-09-15")
        save()
        compose.runOnIdle { assertEquals(1, results.size) }
        field("Даты через запятую (ГГГГ-ММ-ДД)").performTextReplacement("2030-02-30")
        save()
        compose.runOnIdle { assertEquals(1, results.size) }
    }

    @Test fun oneOccurrenceEditorCannotCreateAnotherSeries() {
        val schedule = CalendarSchedule(CalendarTimeMode.FLOATING, date.atTime(9, 0), recurrence = CalendarRecurrence(CalendarRepeat.DAILY))
        val source = CalendarEvent("series", "Привычка", 0, 0, 0, 0, schedule = schedule)
        show(schedule.project(source, date, ZoneId.systemDefault())!!)
        compose.onNodeWithText("Изменяется только это повторение.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Повторение: Один раз").assertDoesNotExist()
        save()
        compose.runOnIdle { assertEquals(CalendarRepeat.NONE, results.single().scheduleInput.repeat) }
    }
}
