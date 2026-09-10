package com.example.aiassistent1.domain.formatter

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarReplyTimeFormatterTest {
    @Test
    fun `formats morning time without digits`() {
        assertEquals(
            "Событие «Встреча» запланировано: начало в девять часов, окончание в половине десятого.",
            CalendarReplyTimeFormatter.formatCreationReply("Встреча", "2026-08-21T09:00", 30),
        )
    }

    @Test
    fun `formats noon and evening hours`() {
        assertEquals(
            "Событие «Обед» запланировано с двенадцати часов до часа.",
            CalendarReplyTimeFormatter.formatCreationReply("Обед", "2026-08-21T12:00", 60),
        )
        assertEquals(
            "Событие «Прогулка» запланировано с шести часов до семи часов.",
            CalendarReplyTimeFormatter.formatCreationReply("Прогулка", "2026-08-21T18:00", 60),
        )
    }

    @Test
    fun `formats transition through midnight`() {
        assertEquals(
            "Событие «Дежурство» запланировано с одиннадцати часов пятидесяти минут до двенадцати часов десяти минут.",
            CalendarReplyTimeFormatter.formatCreationReply("Дежурство", "2026-08-21T23:50", 20),
        )
    }

    @Test
    fun `speaks quarter and half of the upcoming hour`() {
        assertEquals(
            "Событие «Проверка» запланировано: начало в четверть десятого, окончание в половине десятого.",
            CalendarReplyTimeFormatter.formatCreationReply("Проверка", "2027-04-13T09:15", 15),
        )
    }

    @Test
    fun `speaks quarter to without an extra preposition`() {
        assertEquals(
            "Событие «Чтение» запланировано: начало без четверти семь, окончание в семь часов.",
            CalendarReplyTimeFormatter.formatCreationReply("Чтение", "2027-04-13T18:45", 15),
        )
        assertEquals(
            "Событие «Обход» запланировано: начало без четверти час, окончание в час.",
            CalendarReplyTimeFormatter.formatCreationReply("Обход", "2027-04-13T00:45", 15),
        )
    }

    @Test
    fun `omits dayparts at noon and midnight`() {
        assertEquals(
            "Событие «Приём» запланировано: начало в половине двенадцатого, окончание в половине первого.",
            CalendarReplyTimeFormatter.formatCreationReply("Приём", "2027-04-13T11:30", 60),
        )
        assertEquals(
            "Событие «Дежурство» запланировано: начало в половине двенадцатого, окончание в половине первого.",
            CalendarReplyTimeFormatter.formatCreationReply("Дежурство", "2027-12-31T23:30", 60),
        )
    }

    @Test
    fun `keeps an ordinary minute accurate next to a relative endpoint`() {
        assertEquals(
            "Событие «Замер» запланировано: начало в девять часов двадцать одну минуту, окончание в половине десятого.",
            CalendarReplyTimeFormatter.formatCreationReply("Замер", "2027-04-13T09:21", 9),
        )
        assertEquals(
            "Событие «Замер» запланировано: начало в четверть десятого, окончание в девять часов двадцать две минуты.",
            CalendarReplyTimeFormatter.formatCreationReply("Замер", "2027-04-13T09:15", 7),
        )
    }

    @Test
    fun `retains title content across a daypart boundary`() {
        assertEquals(
            "Событие «Отчёт 2032 — 09:15» запланировано: начало в четверть четвёртого, окончание в половине шестого.",
            CalendarReplyTimeFormatter.formatCreationReply("Отчёт 2032 — 09:15", "2027-04-13T03:15", 135),
        )
    }

    @Test
    fun `preserves daypart words inside the event title`() {
        assertEquals(
            "Событие «Итоги дня и планы вечера» запланировано: начало в четверть третьего, окончание в половине третьего.",
            CalendarReplyTimeFormatter.formatCreationReply("Итоги дня и планы вечера", "2027-09-01T14:15", 15),
        )
    }

    @Test
    fun `no clock form adds a daypart at any hour`() {
        val daypart = Regex("\\b(утра|дня|вечера|ночи)\\b")
        for (hour in 0..23) {
            for (minute in 0..59) {
                val reply = CalendarReplyTimeFormatter.formatCreationReply(
                    "Проверка", java.time.LocalDateTime.of(2027, 12, 31, hour, minute).toString(), 17,
                )
                org.junit.Assert.assertFalse("$hour:$minute -> $reply", daypart.containsMatchIn(reply))
            }
        }
    }
}
