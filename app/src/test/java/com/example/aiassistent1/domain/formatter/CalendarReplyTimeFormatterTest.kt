package com.example.aiassistent1.domain.formatter

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarReplyTimeFormatterTest {
    @Test
    fun `formats morning time without digits`() {
        assertEquals(
            "Событие «Встреча» запланировано с девяти часов утра до девяти часов тридцати минут.",
            CalendarReplyTimeFormatter.formatCreationReply("Встреча", "2026-08-21T09:00", 30),
        )
    }

    @Test
    fun `formats noon and evening hours`() {
        assertEquals(
            "Событие «Обед» запланировано с двенадцати часов дня до часа.",
            CalendarReplyTimeFormatter.formatCreationReply("Обед", "2026-08-21T12:00", 60),
        )
        assertEquals(
            "Событие «Прогулка» запланировано с шести часов вечера до семи часов.",
            CalendarReplyTimeFormatter.formatCreationReply("Прогулка", "2026-08-21T18:00", 60),
        )
    }

    @Test
    fun `formats transition through midnight`() {
        assertEquals(
            "Событие «Дежурство» запланировано с одиннадцати часов вечера пятидесяти минут до двенадцати часов ночи десяти минут.",
            CalendarReplyTimeFormatter.formatCreationReply("Дежурство", "2026-08-21T23:50", 20),
        )
    }
}
