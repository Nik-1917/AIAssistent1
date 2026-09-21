package com.example.aiassistent1.domain.parser

import java.time.LocalDate
import java.util.Locale

/** Context is read only; it never changes the history sent to the model. */
data class CalendarDateContext(
    val today: LocalDate = LocalDate.now(),
    val userMessagesNewestFirst: List<String> = emptyList(),
    val assistantMessagesNewestFirst: List<String> = emptyList(),
)

/** The sole overflow exception: February 29..31. All other validation stays strict. */
internal object CalendarFebruaryNormalizer {
    fun normalize(input: Map<String, Any>, context: CalendarDateContext): Map<String, Any> {
        val paths = when (input["intent"]) {
            "calendar_add" -> listOf("date", "starts_at", "ends_at")
            "calendar_search", "calendar_sum" -> listOf("range_start", "range_end")
            "calendar_update" -> listOf("target.range_start", "target.range_end", "changes.date")
            "calendar_delete" -> listOf("range_start", "range_end", "target.range_start", "target.range_end")
            "calendar_delete_range" -> listOf("start", "end")
            else -> return input
        }
        val params = input["params"] as? Map<*, *> ?: return input
        fun at(path: String): String? {
            var node: Any? = params
            for (part in path.split('.')) node = (node as? Map<*, *>)?.get(part)
            return node as? String
        }
        val sources = paths.mapNotNull { path -> at(path)?.let { path to it } }.toMap()
        val reply = input["reply"] as? String ?: return input
        val replyProse = unprotected(reply).joinToString(" ") { it.second }
        val explicitYears = sources.values.mapNotNull(::fieldYear).toSet()

        fun yearFor(day: Int): Int {
            // A matching endpoint is more specific than unrelated dates elsewhere in the command.
            val matching = sources.values.mapNotNull(::february)
                .filter { it.day == day }.mapNotNull { it.year }.toSet()
            uniqueYear(matching)?.let { return it }
            uniqueYear(explicitYears)?.let { return it }
            uniqueYear(contextYears(replyProse, context.today.year))?.let { return it }
            for (message in context.userMessagesNewestFirst) {
                uniqueYear(contextYears(message, context.today.year))?.let { return it }
            }
            for (message in context.assistantMessagesNewestFirst) {
                val prose = unprotected(message).joinToString(" ") { it.second }
                uniqueYear(contextYears(prose, context.today.year))?.let { return it }
            }
            return checkedYear(context.today.year)
        }

        val replacements = sources.mapNotNull { (path, raw) ->
            val feb = february(raw) ?: return@mapNotNull null
            val year = checkedYear(feb.year ?: yearFor(feb.day))
            path to (rolledDate(year, feb.day).toString() + feb.suffix)
        }.toMap()

        fun rewrite(map: Map<*, *>, prefix: String = ""): Map<String, Any> = map.entries.associate { (key, value) ->
            val name = key as String
            val path = if (prefix.isEmpty()) name else "$prefix.$name"
            name to (replacements[path] ?: if (value is Map<*, *>) rewrite(value, path) else requireNotNull(value))
        }

        val correctedReply = StringBuilder(reply)
        // Process right to left so protected spans and original character offsets remain valid.
        for ((offset, prose) in unprotected(reply).asReversed()) {
            for (match in REPLY_DATE.findAll(prose).toList().asReversed()) {
                val feb = february(match.value) ?: continue
                val year = checkedYear(feb.year ?: yearFor(feb.day))
                val date = rolledDate(year, feb.day)
                if (date.monthValue == 2) continue
                val replacement = when (feb.style) {
                    Style.ISO -> if (feb.year != null) date.toString() else "03-${date.dayOfMonth.toString().padStart(2, '0')}"
                    Style.NUMERIC -> "${date.dayOfMonth.toString().padStart(2, '0')}.03" + (feb.year?.let { ".$it" } ?: "")
                    Style.RUSSIAN -> "${date.dayOfMonth} марта" + (feb.year?.let { " $it" } ?: "")
                }
                correctedReply.replace(offset + match.range.first, offset + match.range.last + 1, replacement)
            }
        }
        return input + ("params" to rewrite(params)) + ("reply" to correctedReply.toString())
    }

    private enum class Style { ISO, NUMERIC, RUSSIAN }
    private data class February(val year: Int?, val day: Int, val suffix: String, val style: Style)

    private fun february(raw: String): February? {
        ISO.find(raw)?.let { return February(it.groupValues[1].toIntOrNull(), it.groupValues[2].toInt(), raw.substring(it.value.length), Style.ISO) }
        NUMERIC.find(raw)?.let { return February(it.groupValues[2].toIntOrNull(), it.groupValues[1].toInt(), raw.substring(it.value.length), Style.NUMERIC) }
        RUSSIAN.find(raw)?.let { return February(it.groupValues[2].toIntOrNull(), it.groupValues[1].toInt(), raw.substring(it.value.length), Style.RUSSIAN) }
        return null
    }

    private fun rolledDate(year: Int, day: Int): LocalDate = LocalDate.of(year, 2, 1).plusDays(day - 1L)
    private fun checkedYear(year: Int): Int = year.also { require(it in 1..9999) { "Год вне диапазона 0001–9999" } }
    private fun uniqueYear(years: Set<Int>): Int? {
        require(years.size <= 1) { "Неоднозначный год для даты февраля. Уточните год." }
        return years.singleOrNull()?.let(::checkedYear)
    }
    private fun fieldYear(raw: String): Int? =
        FULL_DATE_YEAR.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: february(raw)?.year

    private fun contextYears(text: String, currentYear: Int): Set<Int> = buildSet {
        addAll(yearsInText(text))
        RELATIVE_YEAR.findAll(text).forEach {
            val adjective = it.groupValues[1].lowercase(Locale.ROOT)
            val offset = when {
                adjective.startsWith("позапрошл") -> -2
                adjective.startsWith("следующ") || adjective.startsWith("будущ") -> 1
                adjective.startsWith("прошл") || adjective.startsWith("предыдущ") -> -1
                else -> 0
            }
            add(checkedYear(currentYear + offset))
        }
    }

    private fun yearsInText(text: String): Set<Int> = buildSet {
        text.trim().takeIf { it.matches(Regex("\\d{4}")) }?.let { add(it.toInt()) }
        YEAR_LABEL.findAll(text).forEach { add(it.groupValues[1].toInt()) }
        ISO_YEAR.findAll(text).forEach { add(it.groupValues[1].toInt()) }
        WRITTEN_YEAR.findAll(text).forEach { add(it.groupValues.drop(1).first(String::isNotEmpty).toInt()) }
    }

    private fun unprotected(text: String): List<Pair<Int, String>> = buildList {
        var cursor = 0
        PROTECTED.findAll(text).forEach {
            add(cursor to text.substring(cursor, it.range.first))
            cursor = it.range.last + 1
        }
        add(cursor to text.substring(cursor))
    }

    private val ISO = Regex("^(?:(\\d{4})-)?02-(29|30|31)(?=T|$)")
    private val NUMERIC = Regex("^(29|30|31)\\.02(?:\\.(\\d{4}))?(?=T|$)")
    private val RUSSIAN = Regex("^(29|30|31) февраля(?: (\\d{4}))?(?=T|$)", RegexOption.IGNORE_CASE)
    private val FULL_DATE_YEAR = Regex("^(\\d{4})-\\d{2}-\\d{2}(?=T|$)")
    private val REPLY_DATE = Regex(
        """(?<![\p{L}\d_./-])(?:(?:\d{4}-)?02-(?:29|30|31)|(?:29|30|31)\.02(?:\.\d{4})?|(?:29|30|31) февраля(?: \d{4})?)(?![\d/-])""",
        RegexOption.IGNORE_CASE,
    )
    private val ISO_YEAR = Regex("""(?<!\d)(\d{4})-\d{2}-\d{2}(?!\d)""")
    private val WRITTEN_YEAR = Regex(
        """(?:\d{1,2}\.\d{1,2}\.|\d{1,2} (?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря) |(?<![\p{L}\d_])(?:в|на|за)\s+)(\d{4})(?!\d)|(?<!\d)(\d{4})\s+год""",
        RegexOption.IGNORE_CASE,
    )
    private val YEAR_LABEL = Regex("""(?<![\p{L}\d_])год(?:а|у|ом)?\s*[:=]?\s*(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)
    private val RELATIVE_YEAR = Regex("""(?<![\p{L}\d_])(следующ(?:ем|ий|его)|будущ(?:ем|ий|его)|прошл(?:ом|ый|ого)|позапрошл(?:ом|ый|ого)|предыдущ(?:ем|ий|его)|эт(?:ом|от|ого)|текущ(?:ем|ий|его))\s+год""", RegexOption.IGNORE_CASE)
    private val PROTECTED = Regex("""```[\s\S]*?(?:```|$)|`[^`\n]*(?:`|$)|«[^»]*(?:»|$)|"[^"\n]*"|https?://[^\s]+|(?m:^Примечание:[\s\S]*$)""")
}
