package com.example.aiassistent1.presentation.formatting

import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Display only. Never pass formatted values back to commands, storage or model context. */
class UserDateTimeFormatter(
    val compact: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun messageText(message: ChatMessage): String =
        if (message.role == MessageRole.ASSISTANT && message.chatId == "general") {
            assistantText(message.content, generalChat = true)
        } else message.content

    fun todayLabel(): String = "Сегодня\n${today.format(TODAY_DATE)}"

    fun todayHeader(): String = "сегодня ${today.format(if (compact) TODAY_DATE else FULL_TODAY_DATE)}"

    fun date(value: LocalDate): String = value.format(when {
        !compact || value.year != today.year -> FULL_DATE
        value.month != today.month -> MONTH_DATE
        else -> SHORT_DATE
    })

    fun eventEditorDate(value: LocalDate): String = value.format(if (compact) MONTH_DATE else FULL_DATE)

    fun time(value: LocalTime): String = value.format(TIME)

    fun dateTime(value: LocalDateTime): String = "${date(value.toLocalDate())}, ${time(value.toLocalTime())}"

    fun dateTime(epochMillis: Long): String = dateTime(localDateTime(epochMillis))

    fun localDateTime(epochMillis: Long): LocalDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDateTime()

    fun range(startMillis: Long, endMillis: Long): String {
        val start = localDateTime(startMillis)
        val end = localDateTime(endMillis)
        return if (start.toLocalDate() == end.toLocalDate()) {
            "${time(start.toLocalTime())}–${time(end.toLocalTime())}"
        } else {
            "${dateTime(start)} — ${dateTime(end)}"
        }
    }

    /** Strict full dates only: incomplete dates and invalid values are retained verbatim. */
    fun value(raw: String): String = value(raw, generalChat = false)

    private fun value(raw: String, generalChat: Boolean): String =
        (if (generalChat) GENERAL_CHAT_DATE_TOKEN else DATE_TOKEN).replace(raw) { match ->
            runCatching {
                val rawDate = match.groupValues[1]
                val parsed = when {
                    '-' in rawDate -> LocalDate.parse(rawDate)
                    '.' in rawDate -> LocalDate.parse(rawDate, NUMERIC_DATE)
                    else -> LocalDate.parse(rawDate.lowercase(RUSSIAN), RUSSIAN_DATE)
                }
                val clock = match.groupValues[2]
                if (generalChat) {
                    val fullDate = parsed.format(GENERAL_CHAT_DATE)
                    if (clock.isEmpty()) fullDate else "${time(LocalTime.parse(clock))} $fullDate"
                } else if (clock.isEmpty()) date(parsed)
                else dateTime(LocalDateTime.of(parsed, LocalTime.parse(clock)))
            }.getOrElse { match.value }
        }

    /** Assistant prose only; quoted titles, notes, URLs, JSON and code remain literal. */
    fun assistantText(raw: String): String = assistantText(raw, generalChat = false)

    private fun assistantText(raw: String, generalChat: Boolean): String {
        val result = StringBuilder()
        var cursor = 0
        PROTECTED.findAll(raw).forEach { match ->
            result.append(value(raw.substring(cursor, match.range.first), generalChat))
            result.append(match.value)
            cursor = match.range.last + 1
        }
        result.append(value(raw.substring(cursor), generalChat))
        return result.toString()
    }

    companion object {
        private val RUSSIAN = Locale.forLanguageTag("ru-RU")
        private val TODAY_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", RUSSIAN)
        private val FULL_TODAY_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM uuuu", RUSSIAN)
        private val SHORT_DATE = DateTimeFormatter.ofPattern("d EEEE", RUSSIAN)
        private val MONTH_DATE = DateTimeFormatter.ofPattern("d MMMM EEEE", RUSSIAN)
        private val FULL_DATE = DateTimeFormatter.ofPattern("d MMMM uuuu EEEE", RUSSIAN)
        private val GENERAL_CHAT_DATE = DateTimeFormatter.ofPattern("d MMMM uuuu 'года', EEEE", RUSSIAN)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
        private val NUMERIC_DATE = DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT)
        private val RUSSIAN_DATE = DateTimeFormatter.ofPattern("d MMMM uuuu", RUSSIAN).withResolverStyle(ResolverStyle.STRICT)
        private const val MONTHS = "января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря"
        private const val WEEKDAYS = "понедельник|вторник|среда|четверг|пятница|суббота|воскресенье"
        private val DATE_PART = """(\d{4}-\d{2}-\d{2}|\d{1,2}\.\d{1,2}\.\d{4}|\d{1,2} (?:$MONTHS) \d{4})"""
        private val CLOCK_PART = """(\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?)"""
        private val DATE_BODY = """$DATE_PART(?:[T ]$CLOCK_PART)?"""
        private val GENERAL_CHAT_DATE_BODY = """$DATE_PART(?:(?:[T ]|, )$CLOCK_PART)?"""
        private val DATE_SUFFIX = """(?![\p{L}\d_:/+-])(?: (?:$WEEKDAYS))?"""
        private val GENERAL_CHAT_DATE_SUFFIX = """(?![\p{L}\d_:/+-])(?: года)?(?:,? (?:$WEEKDAYS))?"""
        private val DATE_TOKEN = Regex(
            """(?<![\p{L}\d_./-])$DATE_BODY$DATE_SUFFIX""",
            RegexOption.IGNORE_CASE,
        )
        private val GENERAL_CHAT_DATE_TOKEN = Regex(
            """(?<![\p{L}\d_./-])(?:(?:$WEEKDAYS),? )?$GENERAL_CHAT_DATE_BODY$GENERAL_CHAT_DATE_SUFFIX""",
            RegexOption.IGNORE_CASE,
        )
        private val PROTECTED = Regex(
            """```[\s\S]*?(?:```|$)|`[^`\n]*(?:`|$)|«[^»]*(?:»|$)|"[^"\n]*"|https?://[^\s]+|\{[\s\S]*?\}(?!\s*[,}])|(?m:^Примечание:[\s\S]*?(?=^- .* \(\d{4}-\d{2}-\d{2}T|\z))|(?m:^- .*?(?= \(\d{4}-\d{2}-\d{2}T))""",
        )
    }
}
