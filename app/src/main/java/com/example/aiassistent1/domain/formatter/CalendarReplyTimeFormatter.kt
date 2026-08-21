package com.example.aiassistent1.domain.formatter

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object CalendarReplyTimeFormatter {
    fun formatCreationReply(title: String, startsAt: String, durationMinutes: Int): String {
        require(title.isNotBlank()) { "Название события не может быть пустым." }
        require(durationMinutes > 0) { "Длительность должна быть больше нуля." }
        val start = LocalDateTime.parse(startsAt, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val end = start.plusMinutes(durationMinutes.toLong())
        return "Событие «${title.trim()}» запланировано с ${start.toSpokenTime(includePeriod = true)} до ${end.toSpokenTime(includePeriod = start.hourPeriod != end.hourPeriod)}."
    }

    private fun LocalDateTime.toSpokenTime(includePeriod: Boolean): String {
        val hour = HOUR_FORMS[hour]
        val minute = MINUTE_FORMS[minute]
        val base = if (includePeriod) "${hour.words} ${hour.period}" else hour.words
        return if (minute == null) base else "$base $minute"
    }

    private val HOUR_FORMS = arrayOf(
        HourForm("двенадцати часов", "ночи"), HourForm("часа", "ночи"), HourForm("двух часов", "ночи"), HourForm("трёх часов", "ночи"),
        HourForm("четырёх часов", "ночи"), HourForm("пяти часов", "утра"), HourForm("шести часов", "утра"), HourForm("семи часов", "утра"),
        HourForm("восьми часов", "утра"), HourForm("девяти часов", "утра"), HourForm("десяти часов", "утра"), HourForm("одиннадцати часов", "утра"),
        HourForm("двенадцати часов", "дня"), HourForm("часа", "дня"), HourForm("двух часов", "дня"), HourForm("трёх часов", "дня"),
        HourForm("четырёх часов", "дня"), HourForm("пяти часов", "дня"), HourForm("шести часов", "вечера"), HourForm("семи часов", "вечера"),
        HourForm("восьми часов", "вечера"), HourForm("девяти часов", "вечера"), HourForm("десяти часов", "вечера"), HourForm("одиннадцати часов", "вечера"),
    )

    private val LocalDateTime.hourPeriod: String
        get() = HOUR_FORMS[hour].period

    private data class HourForm(val words: String, val period: String)

    private val MINUTE_FORMS = arrayOf<String?>(
        null,
        "одной минуты", "двух минут", "трёх минут", "четырёх минут", "пяти минут",
        "шести минут", "семи минут", "восьми минут", "девяти минут", "десяти минут",
        "одиннадцати минут", "двенадцати минут", "тринадцати минут", "четырнадцати минут", "пятнадцати минут",
        "шестнадцати минут", "семнадцати минут", "восемнадцати минут", "девятнадцати минут", "двадцати минут",
        "двадцати одной минуты", "двадцати двух минут", "двадцати трёх минут", "двадцати четырёх минут", "двадцати пяти минут",
        "двадцати шести минут", "двадцати семи минут", "двадцати восьми минут", "двадцати девяти минут", "тридцати минут",
        "тридцати одной минуты", "тридцати двух минут", "тридцати трёх минут", "тридцати четырёх минут", "тридцати пяти минут",
        "тридцати шести минут", "тридцати семи минут", "тридцати восьми минут", "тридцати девяти минут", "сорока минут",
        "сорока одной минуты", "сорока двух минут", "сорока трёх минут", "сорока четырёх минут", "сорока пяти минут",
        "сорока шести минут", "сорока семи минут", "сорока восьми минут", "сорока девяти минут", "пятидесяти минут",
        "пятидесяти одной минуты", "пятидесяти двух минут", "пятидесяти трёх минут", "пятидесяти четырёх минут", "пятидесяти пяти минут",
        "пятидесяти шести минут", "пятидесяти семи минут", "пятидесяти восьми минут", "пятидесяти девяти минут",
    )
}
