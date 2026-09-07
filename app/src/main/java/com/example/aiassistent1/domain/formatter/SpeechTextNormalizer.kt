package com.example.aiassistent1.domain.formatter

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Converts message text to a Russian-friendly form used only by speech synthesis. */
object SpeechTextNormalizer {
    private val markdownLink = Regex("(!)?\\[([^]]+)]\\((https?://[^)\\s]+)\\)", RegexOption.IGNORE_CASE)
    private val url = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>()\\[\\]{}]+")
    private val atxHeading = Regex("(?m)^[\\t ]{0,3}#{1,6}[\\t ]+(.+?)(?:[\\t ]+#+)?[\\t ]*$")
    private val starredHeading = Regex(
        "(?m)^[\\t ]*(?:\\d{1,3}[\\t ]*(?:[.)]|[-—–:])[\\t ]+)?" +
            "(\\*{1,3})[\\t ]*(.+?)[\\t ]*\\1",
    )
    private val setextHeading = Regex("(?m)^([^\\r\\n]+)\\r?\\n[\\t ]*(?:=+|-{3,})[\\t ]*$")
    private val englishPhrase = Regex(
        "(?<![\\p{L}\\p{N}_])" +
            "((?:[А-Яа-яЁё][\\t ]+)?[('\\[\"«]?[A-Za-z][A-Za-z0-9]*(?:[-'][A-Za-z0-9]+)*" +
            "(?:[\\t ]+[A-Za-z][A-Za-z0-9]*(?:[-'][A-Za-z0-9]+)*)*" +
            "[)'\\]\"»]*[,;:.!?]?)" +
            "(?![A-Za-z0-9_])",
    )
    private val numberedListAfterLineBreak = Regex("\\r?\\n(?:[\\t ]*\\r?\\n)*[\\t ]*\\d{1,3}[\\t ]*(?:[.)]|[-—–:])[\\t ]+")
    private val numberedListAtStart = Regex("^[\\t ]*\\d{1,3}[\\t ]*(?:[.)]|[-—–:])[\\t ]+")
    private val isoDate = Regex("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)")
    private val dottedDate = Regex("(?<!\\d)(\\d{1,2})[./](\\d{1,2})[./](\\d{4})(?!\\d)")
    private val textualDateWithYear = Regex(
        "(?iu)(?<![\\p{L}\\p{N}_])(\\d{1,2})\\s+" +
            "(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+" +
            "(\\d{4})(?:\\s*(?:г\\.|года|год))?(?![\\p{L}\\p{N}_])",
    )
    private val textualDateWithoutYear = Regex(
        "(?iu)(?<![\\p{L}\\p{N}_])(\\d{1,2})\\s+" +
            "(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)" +
            "(?![\\p{L}\\p{N}_])",
    )
    private val yearWithPrepositionalContext = Regex(
        "(?iu)(?<![\\p{L}\\p{N}_])(в|во)\\s+(\\d{4})\\s+(?:году|г\\.)(?![\\p{L}\\p{N}_])",
    )
    private val yearWithDativeContext = Regex(
        "(?iu)(?<![\\p{L}\\p{N}_])(к|ко)\\s+(\\d{4})\\s+(?:году|г\\.)(?![\\p{L}\\p{N}_])",
    )
    private val yearWithWord = Regex(
        "(?iu)(?<![\\p{L}\\p{N}_])(\\d{4})\\s+(год|года|году|г\\.)(?![\\p{L}\\p{N}_])",
    )
    private val number = Regex("(?<![\\p{L}\\p{N}_])([+-]?(?:\\d{1,3}(?:[ _]\\d{3})+|\\d+))(?:[,.](\\d+))?(%?)(?![\\p{L}\\p{N}_])")
    private val uppercaseAbbreviation = Regex("(?<![A-ZА-ЯЁ])[A-ZА-ЯЁ]{2,}(?![A-ZА-ЯЁ])")

    private val abbreviations = linkedMapOf(
        "т. д." to "так далее",
        "т.д." to "так далее",
        "т. п." to "тому подобное",
        "т.п." to "тому подобное",
        "г." to "город",
        "ул." to "улица",
        "пр." to "проспект",
        "д." to "дом",
        "кв." to "квартира",
        "стр." to "страница",
        "рис." to "рисунок",
        "см." to "смотри",
        "ч." to "час",
        "мин." to "минута",
        "сек." to "секунда",
    )

    fun normalize(source: String): String {
        if (source.isBlank()) return source
        val protected = mutableListOf<String>()
        fun protect(value: String): String {
            protected += value
            return "\uE000${marker(protected.lastIndex)}\uE001"
        }

        var text = removeEmojiAndIcons(source)
        text = Regex("(?s)(```|~~~)[^\\r\\n]*\\r?\\n?.*?\\1").replace(text, " ")
        text = Regex("`[^`\\r\\n]+`").replace(text, " ")
        text = removeBalancedCurlyBlocks(text)
        text = markdownLink.replace(text) { match ->
            if (match.groupValues[1] == "!") {
                " "
            } else {
                val spokenTitle = markEnglishPhrases("${match.groupValues[2]}.")
                protect("ссылка: $spokenTitle адрес: ${speakUrl(match.groupValues[3])}")
            }
        }
        text = url.replace(text) { match ->
            val raw = match.value
            val trailing = raw.takeLastWhile { it in ".,!?;:" }
            protect(speakUrl(raw.dropLast(trailing.length))) + trailing
        }
        text = protectParentheticals(text) { value -> protect(value) }

        text = setextHeading.replace(text) { match -> speechSection(match.groupValues[1]) }
        text = atxHeading.replace(text) { match -> speechSection(match.groupValues[1]) }
        text = starredHeading.replace(text) { match -> speechSection(match.groupValues[2]) }
        text = numberedListAfterLineBreak.replace(text, ". ")
        text = numberedListAtStart.replace(text, "")
        text = text.replace(Regex("([.!?…:;])\\s*\\.\\s*"), "$1 ")
        text = text
            .replace(Regex("(?m)^[\\t ]{0,3}#{1,6}[\\t ]+"), "")
            .replace(Regex("(?m)^[\\t ]*[-*+][\\t ]+"), "")
            .replace(Regex("\\*+"), "")
            .replace(Regex("(__|~~)"), "")
            .replace(Regex("(?<!\\w)_(?!\\w)"), "")
        text = isoDate.replace(text) { match ->
            protect(speakDate(match.groupValues[1], match.groupValues[2], match.groupValues[3]))
        }
        text = dottedDate.replace(text) { match ->
            protect(speakDate(match.groupValues[3], match.groupValues[2], match.groupValues[1]))
        }
        text = textualDateWithYear.replace(text) { match ->
            protect(
                speakTextualDate(
                    day = match.groupValues[1],
                    month = match.groupValues[2],
                    year = match.groupValues[3],
                ) ?: match.value,
            )
        }
        text = textualDateWithoutYear.replace(text) { match ->
            protect(
                speakTextualDate(
                    day = match.groupValues[1],
                    month = match.groupValues[2],
                    year = null,
                ) ?: match.value,
            )
        }
        text = yearWithPrepositionalContext.replace(text) { match ->
            protect("${match.groupValues[1]} ${speakYear(match.groupValues[2].toInt(), YearCase.PREPOSITIONAL)} году")
        }
        text = yearWithDativeContext.replace(text) { match ->
            protect("${match.groupValues[1]} ${speakYear(match.groupValues[2].toInt(), YearCase.DATIVE)} году")
        }
        text = yearWithWord.replace(text) { match ->
            val grammaticalCase = when (match.groupValues[2].lowercase(Locale.ROOT)) {
                "года" -> YearCase.GENITIVE
                "году" -> YearCase.PREPOSITIONAL
                else -> YearCase.NOMINATIVE
            }
            val yearWord = if (match.groupValues[2].equals("г.", ignoreCase = true)) {
                "год"
            } else {
                match.groupValues[2]
            }
            protect("${speakYear(match.groupValues[1].toInt(), grammaticalCase)} $yearWord")
        }
        abbreviations.forEach { (abbreviation, spoken) ->
            val escaped = Regex.escape(abbreviation)
            text = Regex("(?iu)(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])").replace(text, spoken)
        }
        text = uppercaseAbbreviation.replace(text) { match ->
            match.value.map(::speakLetter).joinToString(" ")
        }
        text = number.replace(text) { match ->
            speakNumber(match.groupValues[1], match.groupValues[2].ifEmpty { null }, match.groupValues[3] == "%")
        }
        text = text.replace(Regex("\\s*$SPEECH_SECTION_BOUNDARY\\s*"), SPEECH_SECTION_BOUNDARY)
        text = markEnglishPhrases(text)
        text = text.replace(Regex("\\s+"), " ").trim()
        protected.indices.reversed().forEach { index ->
            text = text.replace("\uE000${marker(index)}\uE001", protected[index])
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun removeEmojiAndIcons(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            val codePointLength = Character.charCount(codePoint)
            if (isKeycapStart(codePoint)) {
                var nextIndex = index + codePointLength
                if (nextIndex < value.length && Character.codePointAt(value, nextIndex) == VARIATION_SELECTOR_16) {
                    nextIndex += Character.charCount(VARIATION_SELECTOR_16)
                }
                if (nextIndex < value.length && Character.codePointAt(value, nextIndex) == COMBINING_ENCLOSING_KEYCAP) {
                    index = nextIndex + Character.charCount(COMBINING_ENCLOSING_KEYCAP)
                    continue
                }
            }
            if (!isEmojiOrIcon(codePoint)) result.appendCodePoint(codePoint)
            index += codePointLength
        }
        return result.toString()
    }

    private fun isKeycapStart(codePoint: Int): Boolean =
        codePoint == '#'.code || codePoint == '*'.code || codePoint in '0'.code..'9'.code

    private fun isEmojiOrIcon(codePoint: Int): Boolean =
        codePoint == ZERO_WIDTH_JOINER ||
            codePoint == COMBINING_ENCLOSING_KEYCAP ||
            codePoint in VARIATION_SELECTORS ||
            codePoint in EMOJI_MODIFIERS ||
            codePoint in EMOJI_TAG_CHARACTERS ||
            MISCELLANEOUS_EMOJI_AND_ICONS.any { codePoint in it } ||
            codePoint in SUPPLEMENTARY_EMOJI

    private fun speechSection(value: String): String =
        "$SPEECH_SECTION_BOUNDARY${value.trim()}$SPEECH_SECTION_BOUNDARY"

    private fun markEnglishPhrases(value: String): String = englishPhrase.replace(value) { match ->
        "$SPEECH_ENGLISH_PHRASE_BOUNDARY${match.value}$SPEECH_ENGLISH_PHRASE_BOUNDARY"
    }

    private fun protectParentheticals(text: String, protect: (String) -> String): String {
        val result = StringBuilder(text.length)
        var copiedUntil = 0
        var scan = 0
        while (scan < text.length) {
            if (text[scan] != '(') {
                scan++
                continue
            }

            val openingIndex = scan
            var depth = 0
            var closingIndex = -1
            var current = openingIndex
            while (current < text.length) {
                when (text[current]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            closingIndex = current
                            break
                        }
                    }
                }
                current++
            }
            if (closingIndex < 0) {
                scan = openingIndex + 1
                continue
            }

            var endExclusive = closingIndex + 1
            while (endExclusive < text.length && text[endExclusive] in PARENTHETICAL_TRAILING_PUNCTUATION) {
                endExclusive++
            }
            result.append(text, copiedUntil, openingIndex)
            result.append(protect(speechSection(text.substring(openingIndex, endExclusive))))
            copiedUntil = endExclusive
            scan = endExclusive
        }
        result.append(text, copiedUntil, text.length)
        return result.toString()
    }

    private fun removeBalancedCurlyBlocks(text: String): String {
        val result = StringBuilder(text.length)
        var copiedUntil = 0
        var scan = 0
        while (scan < text.length) {
            if (text[scan] != '{') {
                scan++
                continue
            }

            val openingIndex = scan
            var depth = 0
            var closingIndex = -1
            var current = openingIndex
            while (current < text.length) {
                when (text[current]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            closingIndex = current
                            break
                        }
                    }
                }
                current++
            }
            if (closingIndex < 0) {
                scan = openingIndex + 1
                continue
            }

            result.append(text, copiedUntil, openingIndex)
            if (result.isNotEmpty() && !result.last().isWhitespace()) result.append(' ')
            copiedUntil = closingIndex + 1
            scan = copiedUntil
        }
        result.append(text, copiedUntil, text.length)
        return result.toString()
    }

    private fun speakDate(year: String, month: String, day: String): String = runCatching {
        val date = LocalDate.parse("$year-$month-$day", DateTimeFormatter.ISO_LOCAL_DATE)
        "${DAY_GENITIVE_FORMS[date.dayOfMonth - 1]} ${MONTH_FORMS[date.monthValue - 1]} " +
            "${speakYear(date.year, YearCase.GENITIVE)} года"
    }.getOrElse { "$day.$month.$year" }

    private fun speakTextualDate(day: String, month: String, year: String?): String? = runCatching {
        val dayValue = day.toInt()
        val monthIndex = MONTH_FORMS.indexOf(month.lowercase(Locale.ROOT)) + 1
        require(monthIndex > 0)
        if (year != null) {
            val yearValue = year.toInt()
            val date = LocalDate.of(yearValue, monthIndex, dayValue)
            "${DAY_GENITIVE_FORMS[date.dayOfMonth - 1]} ${MONTH_FORMS[date.monthValue - 1]} " +
                "${speakYear(date.year, YearCase.GENITIVE)} года"
        } else {
            require(dayValue in 1..YearMonth.of(2024, monthIndex).lengthOfMonth())
            "${DAY_GENITIVE_FORMS[dayValue - 1]} ${MONTH_FORMS[monthIndex - 1]}"
        }
    }.getOrNull()

    private fun speakYear(year: Int, grammaticalCase: YearCase): String {
        val ordinal = if (year != 0 && year % 1_000 == 0) {
            THOUSAND_ORDINALS[year / 1_000]
        } else {
            null
        }
        if (ordinal != null) return inflectOrdinal(ordinal, grammaticalCase)

        val cardinal = speakInteger(year.toLong()).let { value ->
            if (value.startsWith("одна тысяча ")) {
                "тысяча ${value.removePrefix("одна тысяча ")}"
            } else {
                value
            }
        }
        val last = cardinal.substringAfterLast(' ')
        val nominativeOrdinal = YEAR_ORDINALS[last] ?: return cardinal
        return cardinal.removeSuffix(last) + inflectOrdinal(nominativeOrdinal, grammaticalCase)
    }

    private fun inflectOrdinal(nominative: String, grammaticalCase: YearCase): String {
        if (grammaticalCase == YearCase.NOMINATIVE) return nominative
        if (nominative == "третий") {
            return when (grammaticalCase) {
                YearCase.GENITIVE -> "третьего"
                YearCase.PREPOSITIONAL -> "третьем"
                YearCase.DATIVE -> "третьему"
                YearCase.NOMINATIVE -> nominative
            }
        }
        val stem = nominative.dropLast(2)
        return stem + when (grammaticalCase) {
            YearCase.GENITIVE -> "ого"
            YearCase.PREPOSITIONAL -> "ом"
            YearCase.DATIVE -> "ому"
            YearCase.NOMINATIVE -> nominative.takeLast(2)
        }
    }

    private fun speakNumber(integerPart: String, fractionPart: String?, isPercent: Boolean): String {
        val normalizedInteger = integerPart.replace(" ", "").replace("_", "")
        val value = normalizedInteger.toLongOrNull() ?: return integerPart
        val integerWords = speakInteger(value)
        if (fractionPart == null) return integerWords + if (isPercent) " ${form(value, "процент", "процента", "процентов")}" else ""
        val fractionValue = fractionPart.toLongOrNull() ?: return integerPart
        val denominator = when (fractionPart.length) {
            1 -> "десятых"
            2 -> "сотых"
            3 -> "тысячных"
            else -> "десять в степени минус ${speakInteger(fractionPart.length.toLong())}"
        }
        val whole = when (kotlin.math.abs(value)) {
            1L -> "целая"
            else -> "целых"
        }
        val result = "$integerWords $whole ${speakInteger(fractionValue)} $denominator"
        return result + if (isPercent) " процента" else ""
    }

    private fun speakInteger(value: Long): String {
        if (value == 0L) return "ноль"
        val prefix = if (value < 0) "минус " else ""
        var rest = kotlin.math.abs(value)
        val groups = arrayOf(
            Scale("", "", "", false),
            Scale("тысяча", "тысячи", "тысяч", true),
            Scale("миллион", "миллиона", "миллионов", false),
            Scale("миллиард", "миллиарда", "миллиардов", false),
            Scale("триллион", "триллиона", "триллионов", false),
        )
        val parts = mutableListOf<String>()
        var group = 0
        while (rest > 0 && group < groups.size) {
            val current = (rest % 1_000).toInt()
            if (current != 0) {
                val scale = groups[group]
                val words = speakUnderThousand(current, scale.feminine)
                parts += listOf(words, form(current.toLong(), scale.one, scale.few, scale.many)).filter(String::isNotEmpty).joinToString(" ")
            }
            rest /= 1_000
            group++
        }
        return prefix + parts.asReversed().joinToString(" ")
    }

    private fun speakUnderThousand(value: Int, feminine: Boolean): String {
        val parts = mutableListOf<String>()
        if (value >= 100) parts += HUNDREDS[value / 100]
        val remainder = value % 100
        if (remainder in 10..19) parts += TEENS[remainder - 10]
        else {
            if (remainder >= 20) parts += TENS[remainder / 10]
            val unit = remainder % 10
            if (unit > 0) parts += if (feminine) FEMININE_UNITS[unit] else UNITS[unit]
        }
        return parts.joinToString(" ")
    }

    private fun speakUrl(value: String): String {
        val withoutScheme = value.removePrefix("https://").removePrefix("http://")
        val scheme = when {
            value.startsWith("https://", true) -> "эйч ти ти пи эс двоеточие двойной слэш "
            value.startsWith("http://", true) -> "эйч ти ти пи двоеточие двойной слэш "
            else -> ""
        }
        return scheme + withoutScheme.flatMap { char ->
            when (char) {
                '.' -> listOf(" точка ")
                '/' -> listOf(" слэш ")
                ':' -> listOf(" двоеточие ")
                '?' -> listOf(" вопросительный знак ")
                '&' -> listOf(" и ")
                '=' -> listOf(" равно ")
                '-' -> listOf(" дефис ")
                '_' -> listOf(" подчёркивание ")
                '#' -> listOf(" решётка ")
                else -> if (char.isDigit()) listOf(" ${speakLetter(char)} ") else listOf(char.toString())
            }
        }.joinToString("").replace(Regex("\\s+"), " ").trim()
    }

    private fun speakCode(value: String): String = value
        .replace(Regex("([a-zа-яё])([A-ZА-ЯЁ])"), "$1 $2")
        .flatMap { char ->
            when {
                char.isLetterOrDigit() -> listOf(if (char.isDigit()) speakLetter(char) else char.toString())
                char.isWhitespace() -> listOf(" ")
                else -> listOf(" ${CODE_SYMBOLS[char] ?: char.toString()} ")
            }
        }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun speakLetter(char: Char): String = LETTERS[char.uppercaseChar()] ?: char.toString()
    private fun marker(index: Int): String {
        var value = index
        val result = StringBuilder()
        do {
            result.append(('а'.code + value % 26).toChar())
            value = value / 26 - 1
        } while (value >= 0)
        return result.toString()
    }

    private fun form(value: Long, one: String, few: String, many: String): String {
        val mod100 = kotlin.math.abs(value % 100)
        val mod10 = kotlin.math.abs(value % 10)
        return when {
            mod100 in 11..14 -> many
            mod10 == 1L -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    private data class Scale(val one: String, val few: String, val many: String, val feminine: Boolean)
    private enum class YearCase { NOMINATIVE, GENITIVE, PREPOSITIONAL, DATIVE }

    private val UNITS = arrayOf("", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
    private val FEMININE_UNITS = arrayOf("", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
    private val TEENS = arrayOf("десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать")
    private val TENS = arrayOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val HUNDREDS = arrayOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")
    private val DAY_GENITIVE_FORMS = arrayOf("первого", "второго", "третьего", "четвёртого", "пятого", "шестого", "седьмого", "восьмого", "девятого", "десятого", "одиннадцатого", "двенадцатого", "тринадцатого", "четырнадцатого", "пятнадцатого", "шестнадцатого", "семнадцатого", "восемнадцатого", "девятнадцатого", "двадцатого", "двадцать первого", "двадцать второго", "двадцать третьего", "двадцать четвёртого", "двадцать пятого", "двадцать шестого", "двадцать седьмого", "двадцать восьмого", "двадцать девятого", "тридцатого", "тридцать первого")
    private val MONTH_FORMS = arrayOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val YEAR_ORDINALS = mapOf(
        "ноль" to "нулевой",
        "один" to "первый", "два" to "второй", "три" to "третий", "четыре" to "четвёртый",
        "пять" to "пятый", "шесть" to "шестой", "семь" to "седьмой", "восемь" to "восьмой", "девять" to "девятый",
        "десять" to "десятый", "одиннадцать" to "одиннадцатый", "двенадцать" to "двенадцатый",
        "тринадцать" to "тринадцатый", "четырнадцать" to "четырнадцатый", "пятнадцать" to "пятнадцатый",
        "шестнадцать" to "шестнадцатый", "семнадцать" to "семнадцатый", "восемнадцать" to "восемнадцатый",
        "девятнадцать" to "девятнадцатый", "двадцать" to "двадцатый", "тридцать" to "тридцатый",
        "сорок" to "сороковой", "пятьдесят" to "пятидесятый", "шестьдесят" to "шестидесятый",
        "семьдесят" to "семидесятый", "восемьдесят" to "восьмидесятый", "девяносто" to "девяностый",
        "сто" to "сотый", "двести" to "двухсотый", "триста" to "трёхсотый", "четыреста" to "четырёхсотый",
        "пятьсот" to "пятисотый", "шестьсот" to "шестисотый", "семьсот" to "семисотый",
        "восемьсот" to "восьмисотый", "девятьсот" to "девятисотый",
    )
    private val THOUSAND_ORDINALS = mapOf(
        1 to "тысячный", 2 to "двухтысячный", 3 to "трёхтысячный", 4 to "четырёхтысячный",
        5 to "пятитысячный", 6 to "шеститысячный", 7 to "семитысячный", 8 to "восьмитысячный",
        9 to "девятитысячный",
    )
    private val LETTERS = mapOf('А' to "а", 'Б' to "бэ", 'В' to "вэ", 'Г' to "гэ", 'Д' to "дэ", 'Е' to "е", 'Ё' to "ё", 'Ж' to "жэ", 'З' to "зэ", 'И' to "и", 'Й' to "й", 'К' to "ка", 'Л' to "эл", 'М' to "эм", 'Н' to "эн", 'О' to "о", 'П' to "пэ", 'Р' to "эр", 'С' to "эс", 'Т' to "тэ", 'У' to "у", 'Ф' to "эф", 'Х' to "ха", 'Ц' to "цэ", 'Ч' to "че", 'Ш' to "ша", 'Щ' to "ща", 'Ы' to "ы", 'Э' to "э", 'Ю' to "ю", 'Я' to "я", 'A' to "эй", 'B' to "би", 'C' to "си", 'D' to "ди", 'E' to "и", 'F' to "эф", 'G' to "джи", 'H' to "эйч", 'I' to "ай", 'J' to "джей", 'K' to "кей", 'L' to "эл", 'M' to "эм", 'N' to "эн", 'O' to "оу", 'P' to "пи", 'Q' to "кью", 'R' to "ар", 'S' to "эс", 'T' to "ти", 'U' to "ю", 'V' to "ви", 'W' to "дабл ю", 'X' to "икс", 'Y' to "уай", 'Z' to "зэд", '0' to "ноль", '1' to "один", '2' to "два", '3' to "три", '4' to "четыре", '5' to "пять", '6' to "шесть", '7' to "семь", '8' to "восемь", '9' to "девять")
    private val CODE_SYMBOLS = mapOf('=' to "равно", '+' to "плюс", '-' to "минус", '*' to "звёздочка", '/' to "слэш", '\\' to "обратный слэш", '_' to "подчёркивание", '.' to "точка", ':' to "двоеточие", ';' to "точка с запятой", ',' to "запятая", '(' to "открывающая скобка", ')' to "закрывающая скобка", '{' to "открывающая фигурная скобка", '}' to "закрывающая фигурная скобка", '[' to "открывающая квадратная скобка", ']' to "закрывающая квадратная скобка", '"' to "кавычка", '\'' to "апостроф", '<' to "меньше", '>' to "больше")
    private val PARENTHETICAL_TRAILING_PUNCTUATION = setOf(',', ';', ':', '.', '!', '?', '…')
    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val VARIATION_SELECTOR_16 = 0xFE0F
    private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3
    private val VARIATION_SELECTORS = 0xFE00..0xFE0F
    private val EMOJI_MODIFIERS = 0x1F3FB..0x1F3FF
    private val EMOJI_TAG_CHARACTERS = 0xE0020..0xE007F
    private val MISCELLANEOUS_EMOJI_AND_ICONS = listOf(
        0x00A9..0x00A9,
        0x00AE..0x00AE,
        0x203C..0x203C,
        0x2049..0x2049,
        0x2122..0x2122,
        0x2139..0x2139,
        0x2190..0x21FF,
        0x2300..0x23FF,
        0x2460..0x24FF,
        0x25A0..0x27BF,
        0x2934..0x2935,
        0x2B00..0x2BFF,
        0x3030..0x3030,
        0x303D..0x303D,
        0x3297..0x3297,
        0x3299..0x3299,
    )
    private val SUPPLEMENTARY_EMOJI = 0x1F000..0x1FAFF
}
