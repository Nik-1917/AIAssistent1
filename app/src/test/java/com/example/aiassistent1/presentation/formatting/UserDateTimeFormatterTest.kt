package com.example.aiassistent1.presentation.formatting

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class UserDateTimeFormatterTest {
    private val today = LocalDate.of(2026, 9, 21)
    private val short = UserDateTimeFormatter(true, true, today, ZoneOffset.UTC)
    private val full = UserDateTimeFormatter(false, false, today, ZoneOffset.UTC)
    private val monthHidden = UserDateTimeFormatter(true, false, today, ZoneOffset.UTC)
    private val yearHidden = UserDateTimeFormatter(false, true, today, ZoneOffset.UTC)
    private val allModes = listOf(short, full, monthHidden, yearHidden)

    @Test fun `default shows current month and hides current year`() {
        val defaults = UserDateTimeFormatter(today = today, zoneId = ZoneOffset.UTC)
        assertEquals("21 сентября понедельник", defaults.date(today))
        assertEquals("19 октября понедельник", defaults.date(LocalDate.of(2026, 10, 19)))
        assertEquals("21 сентября понедельник", defaults.eventEditorDate(today))
    }

    @Test fun `month and year switches control current dates independently`() {
        assertEquals("21 понедельник", short.date(today))
        assertEquals("21 сентября 2026 понедельник", full.date(today))
        assertEquals("21 2026 понедельник", monthHidden.date(today))
        assertEquals("21 сентября понедельник", yearHidden.date(today))
        assertEquals("19 октября 2026 понедельник", monthHidden.date(LocalDate.of(2026, 10, 19)))
        assertEquals("19 октября понедельник", yearHidden.date(LocalDate.of(2026, 10, 19)))
        for (formatter in allModes) {
            assertEquals("19 сентября 2025 пятница", formatter.date(LocalDate.of(2025, 9, 19)))
            assertEquals("19 сентября 2027 воскресенье", formatter.date(LocalDate.of(2027, 9, 19)))
        }
    }

    @Test fun `editor headers keep month and their existing year rule regardless of month switch`() {
        for (hideMonth in listOf(false, true)) {
            val withoutYear = UserDateTimeFormatter(hideMonth, true, today, ZoneOffset.UTC)
            val withYear = UserDateTimeFormatter(hideMonth, false, today, ZoneOffset.UTC)
            assertEquals("21 сентября понедельник", withoutYear.eventEditorDate(today))
            assertEquals("21 сентября 2026 понедельник", withYear.eventEditorDate(today))
            assertEquals("19 сентября воскресенье", withoutYear.eventEditorDate(LocalDate.of(2027, 9, 19)))
            assertEquals("19 сентября 2027 воскресенье", withYear.eventEditorDate(LocalDate.of(2027, 9, 19)))
        }
    }

    @Test fun `independent switches apply to parsed dates and ranges across midnight`() {
        val raw = "2026-09-19T23:30"
        assertEquals("19 2026 суббота, 23:30", monthHidden.value(raw))
        assertEquals("19 сентября суббота, 23:30", yearHidden.value(raw))
        val start = LocalDateTime.of(2026, 9, 19, 23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("19 2026 суббота, 23:30 — 20 2026 воскресенье, 00:20", monthHidden.range(start, start + 50 * 60000))
        assertEquals("19 сентября суббота, 23:30 — 20 сентября воскресенье, 00:20", yearHidden.range(start, start + 50 * 60000))
    }

    @Test fun `compact dates omit only current month and year`() {
        assertEquals("21 понедельник", short.date(today))
        assertEquals("19 суббота", short.date(LocalDate.of(2026, 9, 19)))
        assertEquals("19 октября понедельник", short.date(LocalDate.of(2026, 10, 19)))
        assertEquals("19 октября 2027 вторник", short.date(LocalDate.of(2027, 10, 19)))
        assertEquals("19 сентября 2027 воскресенье", short.date(LocalDate.of(2027, 9, 19)))
        assertEquals("19 сентября 2025 пятница", short.date(LocalDate.of(2025, 9, 19)))
    }

    @Test fun `disabled mode always includes month year and weekday`() {
        assertEquals("21 сентября 2026 понедельник", full.date(today))
        assertEquals("19 октября 2026 понедельник", full.date(LocalDate.of(2026, 10, 19)))
        assertEquals("19 сентября 2027 воскресенье", full.date(LocalDate.of(2027, 9, 19)))
    }

    @Test fun `valid leap day and year boundary`() {
        assertEquals("29 февраля 2024 четверг", short.value("2024-02-29"))
        val newYear = UserDateTimeFormatter(true, true, LocalDate.of(2027, 1, 1), ZoneOffset.UTC)
        assertEquals("31 декабря 2026 четверг", newYear.value("2026-12-31"))
        assertEquals("1 пятница", newYear.value("2027-01-01"))
    }

    @Test fun `changing today or preference re-renders the original source`() {
        val raw = "Дата 2026-09-19T09:05:12"
        assertEquals("Дата 19 суббота, 09:05", short.assistantText(raw))
        assertEquals("Дата 19 сентября 2026 суббота, 09:05", full.assistantText(raw))
        val october = UserDateTimeFormatter(true, true, LocalDate.of(2026, 10, 1), ZoneOffset.UTC)
        assertEquals("Дата 19 сентября суббота, 09:05", october.assistantText(raw))
        assertEquals("Дата 2026-09-19T09:05:12", raw)
    }

    @Test fun `full numeric and Russian dates are supported`() {
        assertEquals("19 суббота", short.value("19.09.2026"))
        assertEquals("19 суббота", short.value("19 сентября 2026"))
        assertEquals("19 октября понедельник", short.value("19 октября 2026 понедельник"))
        assertEquals("19 суббота, 09:05", short.value("2026-09-19 09:05:59.123"))
    }

    @Test fun `incomplete invalid dates and offset timestamps stay literal`() {
        for (raw in listOf("19 октября", "2026-02-29", "31.04.2026", "2026-09-19T25:00", "2026-09-19T09:00Z", "2026-09-19T09:00+04:00")) {
            assertEquals(raw, short.value(raw))
        }
    }

    @Test fun `date time uses device zone and 24 hour clock without seconds`() {
        val samara = UserDateTimeFormatter(true, true, today, ZoneId.of("Europe/Samara"))
        val epoch = LocalDateTime.of(2026, 9, 18, 21, 5, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("19 суббота, 01:05", samara.dateTime(epoch))
        assertEquals("19 суббота, 23:05", short.dateTime(LocalDateTime.of(2026, 9, 19, 23, 5, 59)))
    }

    @Test fun `ranges disclose both dates across midnight`() {
        val start = LocalDateTime.of(2026, 9, 19, 23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("23:30–23:50", short.range(start, start + 20 * 60000))
        assertEquals("19 суббота, 23:30 — 20 воскресенье, 00:20", short.range(start, start + 50 * 60000))
        assertEquals("19 сентября 2026 суббота, 23:30 — 20 сентября 2026 воскресенье, 00:20", full.range(start, start + 50 * 60000))
    }

    @Test fun `quoted titles code JSON and URLs remain literal`() {
        val fragments = listOf(
            "«2026-09-19»", "\"2026-09-19\"", "`2026-09-19`", "```\n2026-09-19\n```",
            "https://example.test/2026-09-19", "{\"date\":\"2026-09-19\",\"nested\":{\"date\":\"2026-09-20\"}}",
        )
        for (fragment in fragments) {
            assertEquals("$fragment 19 суббота", short.assistantText("$fragment 2026-09-19"))
        }
    }

    @Test fun `search titles and multiline notes survive while subsequent event dates format`() {
        val raw = "Найдено:\n- План 2026-09-19 (2026-09-19T09:00, 60 мин)\nПримечание: 2026-09-19\nЕщё 2026-09-20\n- Другой (2026-10-19T10:00, 60 мин)"
        assertEquals("Найдено:\n- План 2026-09-19 (19 суббота, 09:00, 60 мин)\nПримечание: 2026-09-19\nЕщё 2026-09-20\n- Другой (19 октября понедельник, 10:00, 60 мин)", short.assistantText(raw))
    }

    @Test fun `only assistant messages in general chat are transformed`() {
        val raw = "Начало 2026-09-19T09:05:12, окончание 2026-09-19T10:00."
        val message = com.example.aiassistent1.domain.model.ChatMessage(
            role = com.example.aiassistent1.domain.model.MessageRole.ASSISTANT,
            content = raw,
            chatId = "calendar",
        )
        for (formatter in allModes) {
            assertEquals(raw, formatter.messageText(message))
            assertEquals(raw, formatter.messageText(message.copy(chatId = "general", role = com.example.aiassistent1.domain.model.MessageRole.USER)))
            assertEquals(
                "Начало 09:05 19 сентября 2026 года, суббота, окончание 10:00 19 сентября 2026 года, суббота.",
                formatter.messageText(message.copy(chatId = "general")),
            )
        }
    }

    @Test fun `general assistant keeps month and year for every recognized date`() {
        val message = com.example.aiassistent1.domain.model.ChatMessage(
            role = com.example.aiassistent1.domain.model.MessageRole.ASSISTANT,
            content = "2026-09-19, 2026-10-19, 2027-10-19",
            chatId = "general",
        )
        val expected = "19 сентября 2026 года, суббота, 19 октября 2026 года, понедельник, 19 октября 2027 года, вторник"
        for (formatter in allModes) {
            assertEquals(expected, formatter.messageText(message))
        }
    }

    @Test fun `general assistant normalizes numeric Russian and existing weekday dates`() {
        val message = com.example.aiassistent1.domain.model.ChatMessage(
            role = com.example.aiassistent1.domain.model.MessageRole.ASSISTANT,
            content = "19.09.2026 09:05; 19 сентября 2026 суббота; суббота 19 сентября 2026; суббота, 19 сентября 2026, 10:00; 09:05 19 сентября 2026 года, суббота; «2026-09-19»",
            chatId = "general",
        )
        val expected = "09:05 19 сентября 2026 года, суббота; 19 сентября 2026 года, суббота; 19 сентября 2026 года, суббота; 10:00 19 сентября 2026 года, суббота; 09:05 19 сентября 2026 года, суббота; «2026-09-19»"
        for (formatter in allModes) {
            assertEquals(expected, formatter.messageText(message))
        }
    }
}
