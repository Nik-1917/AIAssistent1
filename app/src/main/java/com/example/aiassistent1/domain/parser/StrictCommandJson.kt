package com.example.aiassistent1.domain.parser

/** Reader for the command JSON subset: objects, strings, integer numbers and booleans.
 * No platform coercion; JVM and Android use identical grammar and duplicate-key checks.
 */
internal class StrictCommandJson private constructor(private val input: String) {
    private var position = 0

    private fun objectValue(depth: Int): Map<String, Any> {
        require(depth <= 8) { "JSON: превышена глубина вложенности" }
        expect('{')
        val result = linkedMapOf<String, Any>()
        if (take('}')) return result
        do {
            val key = string()
            require(key !in result) { "JSON: повторяющееся поле $key" }
            expect(':')
            whitespace()
            result[key] = when (input.getOrNull(position)) {
                '{' -> objectValue(depth + 1)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                '-', in '0'..'9' -> integer()
                else -> error("JSON: недопустимое значение в позиции $position; null и массивы не входят в контракт")
            }
        } while (take(','))
        expect('}')
        return result
    }
    private fun integer(): Long {
        val start = position
        if (input.getOrNull(position) == '-') position++
        require(input.getOrNull(position) in '0'..'9') { "JSON: некорректное число" }
        if (input[position] == '0') position++ else while (input.getOrNull(position) in '0'..'9') position++
        return input.substring(start, position).toLongOrNull() ?: error("JSON: целое число вне диапазона Long")
    }
    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (position < input.length) {
            val c = input[position++]
            if (c == '"') return result.toString().also(::validateSurrogates)
            require(c >= ' ') { "JSON: управляющий символ в строке" }
            if (c != '\\') { result.append(c); continue }
            require(position < input.length) { "JSON: незавершённая escape-последовательность" }
            result.append(when (val escaped = input[position++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    require(position + 4 <= input.length) { "JSON: незавершённый Unicode escape" }
                    val digits = input.substring(position, position + 4)
                    require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "JSON: некорректный Unicode escape" }
                    position += 4
                    digits.toInt(16).toChar()
                }
                else -> error("JSON: неизвестная escape-последовательность")
            })
        }
        error("JSON: незавершённая строка")
    }
    private fun validateSurrogates(value: String) {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c.isHighSurrogate()) {
                require(i < value.length && value[i++].isLowSurrogate()) { "JSON: незавершённая Unicode-пара" }
            } else require(!c.isLowSurrogate()) { "JSON: одиночный Unicode-суррогат" }
        }
    }
    private fun literal(text: String, value: Boolean): Boolean {
        require(input.startsWith(text, position)) { "JSON: некорректное логическое значение" }
        position += text.length
        return value
    }
    private fun whitespace() { while (input.getOrNull(position) in listOf(' ', '\t', '\r', '\n')) position++ }
    private fun take(c: Char): Boolean {
        whitespace()
        return if (input.getOrNull(position) == c) { position++; true } else false
    }
    private fun expect(c: Char) { require(take(c)) { "JSON: ожидался '$c' в позиции $position" } }

    companion object {
        fun read(text: String): Map<String, Any> {
            require(text.length <= 65_536) { "JSON: ответ превышает 65536 символов" }
            val reader = StrictCommandJson(text)
            val result = reader.objectValue(0)
            reader.whitespace()
            require(reader.position == text.length) { "JSON: текст после объекта" }
            return result
        }
    }
}
