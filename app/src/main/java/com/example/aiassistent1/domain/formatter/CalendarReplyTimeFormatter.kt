package com.example.aiassistent1.domain.formatter

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object CalendarReplyTimeFormatter {
    fun formatCreationReply(title: String, startsAt: String, durationMinutes: Int): String {
        require(title.isNotBlank()) { "Название события не может быть пустым." }
        require(durationMinutes > 0) { "Длительность должна быть больше нуля." }
        val start = LocalDateTime.parse(startsAt, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val end = start.plusMinutes(durationMinutes.toLong())
        if (start.minute in RELATIVE_MINUTES || end.minute in RELATIVE_MINUTES) {
            return "Событие «${title.trim()}» запланировано: начало ${start.toSpokenClock()}, окончание ${end.toSpokenClock()}."
        }
        return "Событие «${title.trim()}» запланировано с ${start.toSpokenTime()} до ${end.toSpokenTime()}."
    }

    // Presentation only: callers retain the original title, ISO start and duration.
    // Separate clauses avoid grammatically incorrect constructions such as
    // "с без четверти" when an endpoint uses a relative clock expression.
    private fun LocalDateTime.toSpokenClock(): String {
        val nextHour = (hour + 1) % 12
        return when (minute) {
            15 -> "в четверть ${NEXT_HOUR_ORDINALS[nextHour]}"
            30 -> "в половине ${NEXT_HOUR_ORDINALS[nextHour]}"
            45 -> "без четверти ${CLOCK_HOURS[nextHour]}"
            else -> "в ${EXACT_HOURS[hour % 12]}" + if (minute == 0) "" else " ${spokenMinutes(minute)}"
        }
    }

    private fun spokenMinutes(value: Int): String {
        val units = value % 10
        val words = if (value < 20) MINUTE_CARDINALS[value] else
            MINUTE_TENS[value / 10] + if (units == 0) "" else " ${MINUTE_CARDINALS[units]}"
        val noun = when {
            value in 11..14 -> "минут"
            units == 1 -> "минуту"
            units in 2..4 -> "минуты"
            else -> "минут"
        }
        return "$words $noun"
    }

    private val RELATIVE_MINUTES = setOf(15, 30, 45)
    private val NEXT_HOUR_ORDINALS = arrayOf(
        "двенадцатого", "первого", "второго", "третьего", "четвёртого", "пятого",
        "шестого", "седьмого", "восьмого", "девятого", "десятого", "одиннадцатого",
    )
    private val CLOCK_HOURS = arrayOf(
        "двенадцать", "час", "два", "три", "четыре", "пять",
        "шесть", "семь", "восемь", "девять", "десять", "одиннадцать",
    )
    private val EXACT_HOURS = arrayOf(
        "двенадцать часов", "час", "два часа", "три часа", "четыре часа", "пять часов",
        "шесть часов", "семь часов", "восемь часов", "девять часов", "десять часов", "одиннадцать часов",
    )
    private val MINUTE_CARDINALS = arrayOf(
        "", "одну", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
        "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
    )
    private val MINUTE_TENS = arrayOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят")

    private fun LocalDateTime.toSpokenTime(): String {
        val base = HOUR_FORMS[hour % 12]
        val minute = MINUTE_FORMS[minute]
        return if (minute == null) base else "$base $minute"
    }

    private val HOUR_FORMS = arrayOf(
        "двенадцати часов", "часа", "двух часов", "трёх часов", "четырёх часов", "пяти часов",
        "шести часов", "семи часов", "восьми часов", "девяти часов", "десяти часов", "одиннадцати часов",
    )

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
