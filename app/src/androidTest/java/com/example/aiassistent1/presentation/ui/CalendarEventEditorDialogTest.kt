package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.aiassistent1.calendar.core.domain.CalendarEvent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CalendarEventEditorDialogTest {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.of(2030, 9, 12)
    private val saved = mutableListOf<Saved>()

    @Test fun creationAllowsMissingOptionalFieldsAndRetainsExplicitZeroAndNotes() {
        show()
        field("Название").performTextReplacement("Встреча")
        field("Длительность, минут").performScrollTo().performTextClearance()
        field("Окончание (ЧЧ:ММ)").assert(hasText("", substring = false))
        save()
        compose.runOnIdle {
            assertEquals(1, saved.size)
            assertNull(saved.last().end)
            assertNull(saved.last().value)
            assertEquals("", saved.last().notes)
        }
        field("Ценность события").performScrollTo().performTextReplacement("0")
        field("Примечание").performScrollTo().performTextReplacement("  Документы\n12500  ")
        field("Длительность, минут").performScrollTo().performTextReplacement("50")
        field("Начало (ЧЧ:ММ)").performScrollTo().performTextReplacement("23:30")
        save()
        compose.runOnIdle {
            assertEquals(0L, saved.last().value)
            assertEquals("  Документы\n12500  ", saved.last().notes)
            assertEquals(LocalTime.of(0, 20), saved.last().end)
        }
    }

    @Test fun editingPrefillsChangesAndClearsOptionalFields() {
        val start = date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        show(CalendarEvent("old", "Встреча", start, start + 60 * 60_000L, 0, 0, value = 0, notes = "Прежний текст"))
        field("Ценность события").performScrollTo().assert(hasText("0"))
        field("Примечание").performScrollTo().assert(hasText("Прежний текст"))
        field("Ценность события").performScrollTo().performTextReplacement("-25")
        field("Примечание").performScrollTo().performTextReplacement("Новый текст")
        save()
        compose.runOnIdle {
            assertEquals(-25L, saved.last().value)
            assertEquals("Новый текст", saved.last().notes)
        }
        field("Ценность события").performScrollTo().performTextClearance()
        field("Примечание").performScrollTo().performTextClearance()
        field("Окончание (ЧЧ:ММ)").performScrollTo().performTextClearance()
        save()
        compose.runOnIdle {
            assertNull(saved.last().value)
            assertNull(saved.last().end)
            assertEquals("", saved.last().notes)
        }
    }

    @Test fun titleAndTimeAreRequiredAndInvalidFilledValueCannotBeSaved() {
        show()
        save()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        field("Название").performScrollTo().performTextReplacement("Встреча")
        field("Начало (ЧЧ:ММ)").performScrollTo().performTextClearance()
        save()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        field("Начало (ЧЧ:ММ)").performScrollTo().performTextReplacement("24:00")
        save()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        field("Начало (ЧЧ:ММ)").performScrollTo().performTextReplacement("09:00")
        for (input in listOf("1.5", "не число", "9223372036854775808")) {
            field("Ценность события").performScrollTo().performTextReplacement(input)
            save()
            compose.runOnIdle { assertTrue(saved.isEmpty()) }
        }
    }

    private fun show(event: CalendarEvent? = null) {
        compose.setContent {
            MaterialTheme {
                CalendarEventEditorDialog(date, event, onDismiss = {}, onSave = { title, start, end, value, notes ->
                    saved.add(Saved(title, start, end, value, notes))
                })
            }
        }
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private fun save() = compose.onNodeWithText("Сохранить").performClick()
    private data class Saved(val title: String, val start: LocalTime, val end: LocalTime?, val value: Long?, val notes: String)
}
