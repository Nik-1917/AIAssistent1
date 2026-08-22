package com.example.aiassistent1.domain.formatter

import java.time.LocalDate
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
        "(?<![A-Za-z0-9_])" +
            "([('\\[\"«]?[A-Za-z][A-Za-z0-9]*(?:[-'][A-Za-z0-9]+)*" +
            "(?:[\\t ]+[A-Za-z][A-Za-z0-9]*(?:[-'][A-Za-z0-9]+)*)*" +
            "[)'\\]\"»]*[,;:.!?]?)" +
            "(?![A-Za-z0-9_])",
    )
    private val numberedListAfterLineBreak = Regex("\\r?\\n(?:[\\t ]*\\r?\\n)*[\\t ]*\\d{1,3}[\\t ]*(?:[.)]|[-—–:])[\\t ]+")
    private val numberedListAtStart = Regex("^[\\t ]*\\d{1,3}[\\t ]*(?:[.)]|[-—–:])[\\t ]+")
    private val isoDate = Regex("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)")
    private val dottedDate = Regex("(?<!\\d)(\\d{1,2})[./](\\d{1,2})[./](\\d{4})(?!\\d)")
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

        var text = source
        text = Regex("(?s)```[^\\n`]*\\n?(.*?)```").replace(text) { match ->
            protect("код: ${speakCode(match.groupValues[1])}")
        }
        text = Regex("`([^`]+)`").replace(text) { match ->
            protect("код: ${speakCode(match.groupValues[1])}")
        }
        text = markdownLink.replace(text) { match ->
            val kind = if (match.groupValues[1] == "!") "изображение" else "ссылка"
            val spokenTitle = markEnglishPhrases("${match.groupValues[2]}.")
            protect("$kind: $spokenTitle адрес: ${speakUrl(match.groupValues[3])}")
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

    private fun speakDate(year: String, month: String, day: String): String = runCatching {
        val date = LocalDate.parse("$year-$month-$day", DateTimeFormatter.ISO_LOCAL_DATE)
        "${DAY_FORMS[date.dayOfMonth - 1]} ${MONTH_FORMS[date.monthValue - 1]} ${speakYear(date.year)} года"
    }.getOrElse { "$day.$month.$year" }

    private fun speakYear(year: Int): String {
        val cardinal = speakInteger(year.toLong())
        val last = cardinal.substringAfterLast(' ')
        return cardinal.removeSuffix(last) + (YEAR_ENDINGS[last] ?: last)
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

    private val UNITS = arrayOf("", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
    private val FEMININE_UNITS = arrayOf("", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
    private val TEENS = arrayOf("десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать")
    private val TENS = arrayOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val HUNDREDS = arrayOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")
    private val DAY_FORMS = arrayOf("первое", "второе", "третье", "четвёртое", "пятое", "шестое", "седьмое", "восьмое", "девятое", "десятое", "одиннадцатое", "двенадцатое", "тринадцатое", "четырнадцатое", "пятнадцатое", "шестнадцатое", "семнадцатое", "восемнадцатое", "девятнадцатое", "двадцатое", "двадцать первое", "двадцать второе", "двадцать третье", "двадцать четвёртое", "двадцать пятое", "двадцать шестое", "двадцать седьмое", "двадцать восьмое", "двадцать девятое", "тридцатое", "тридцать первое")
    private val MONTH_FORMS = arrayOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val YEAR_ENDINGS = mapOf("один" to "первого", "два" to "второго", "три" to "третьего", "четыре" to "четвёртого", "пять" to "пятого", "шесть" to "шестого", "семь" to "седьмого", "восемь" to "восьмого", "девять" to "девятого", "десять" to "десятого", "двадцать" to "двадцатого", "тридцать" to "тридцатого", "сорок" to "сорокового", "пятьдесят" to "пятидесятого", "сто" to "сотого", "двести" to "двухсотого")
    private val LETTERS = mapOf('А' to "а", 'Б' to "бэ", 'В' to "вэ", 'Г' to "гэ", 'Д' to "дэ", 'Е' to "е", 'Ё' to "ё", 'Ж' to "жэ", 'З' to "зэ", 'И' to "и", 'Й' to "й", 'К' to "ка", 'Л' to "эл", 'М' to "эм", 'Н' to "эн", 'О' to "о", 'П' to "пэ", 'Р' to "эр", 'С' to "эс", 'Т' to "тэ", 'У' to "у", 'Ф' to "эф", 'Х' to "ха", 'Ц' to "цэ", 'Ч' to "че", 'Ш' to "ша", 'Щ' to "ща", 'Ы' to "ы", 'Э' to "э", 'Ю' to "ю", 'Я' to "я", 'A' to "эй", 'B' to "би", 'C' to "си", 'D' to "ди", 'E' to "и", 'F' to "эф", 'G' to "джи", 'H' to "эйч", 'I' to "ай", 'J' to "джей", 'K' to "кей", 'L' to "эл", 'M' to "эм", 'N' to "эн", 'O' to "оу", 'P' to "пи", 'Q' to "кью", 'R' to "ар", 'S' to "эс", 'T' to "ти", 'U' to "ю", 'V' to "ви", 'W' to "дабл ю", 'X' to "икс", 'Y' to "уай", 'Z' to "зэд", '0' to "ноль", '1' to "один", '2' to "два", '3' to "три", '4' to "четыре", '5' to "пять", '6' to "шесть", '7' to "семь", '8' to "восемь", '9' to "девять")
    private val CODE_SYMBOLS = mapOf('=' to "равно", '+' to "плюс", '-' to "минус", '*' to "звёздочка", '/' to "слэш", '\\' to "обратный слэш", '_' to "подчёркивание", '.' to "точка", ':' to "двоеточие", ';' to "точка с запятой", ',' to "запятая", '(' to "открывающая скобка", ')' to "закрывающая скобка", '{' to "открывающая фигурная скобка", '}' to "закрывающая фигурная скобка", '[' to "открывающая квадратная скобка", ']' to "закрывающая квадратная скобка", '"' to "кавычка", '\'' to "апостроф", '<' to "меньше", '>' to "больше")
    private val PARENTHETICAL_TRAILING_PUNCTUATION = setOf(',', ';', ':', '.', '!', '?', '…')
}
