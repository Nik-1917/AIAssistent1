package com.example.aiassistent1.presentation.formatting

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class UserDateTimeFormatterTest {
    private val today = LocalDate.of(2026, 9, 21)
    private val short = UserDateTimeFormatter(true, today, ZoneOffset.UTC)
    private val full = UserDateTimeFormatter(false, today, ZoneOffset.UTC)

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
        val newYear = UserDateTimeFormatter(true, LocalDate.of(2027, 1, 1), ZoneOffset.UTC)
        assertEquals("31 декабря 2026 четверг", newYear.value("2026-12-31"))
        assertEquals("1 пятница", newYear.value("2027-01-01"))
    }

    @Test fun `changing today or preference re-renders the original source`() {
        val raw = "Дата 2026-09-19T09:05:12"
        assertEquals("Дата 19 суббота, 09:05", short.assistantText(raw))
        assertEquals("Дата 19 сентября 2026 суббота, 09:05", full.assistantText(raw))
        val october = UserDateTimeFormatter(true, LocalDate.of(2026, 10, 1), ZoneOffset.UTC)
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
        val samara = UserDateTimeFormatter(true, today, ZoneId.of("Europe/Samara"))
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
        for (formatter in listOf(short, full)) {
            assertEquals(raw, formatter.messageText(message))
            assertEquals(raw, formatter.messageText(message.copy(chatId = "general", role = com.example.aiassistent1.domain.model.MessageRole.USER)))
        }
        assertEquals("Начало 19 суббота, 09:05, окончание 19 суббота, 10:00.", short.messageText(message.copy(chatId = "general")))
        assertEquals("Начало 19 сентября 2026 суббота, 09:05, окончание 19 сентября 2026 суббота, 10:00.", full.messageText(message.copy(chatId = "general")))
    }
}
