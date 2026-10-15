package com.example.aiassistent1.domain.parser

/** Reads top-level reply/response text without applying the calendar schema to chat metadata. */
internal class ChatReplyJson private constructor(private val input: String) {
    private data class Text(val value: String, val next: Int, val closed: Boolean)

    private fun reply(): String {
        var position = whitespace(0)
        require(input.getOrNull(position) == '{') { "Ответ не начинается с объекта" }
        position++
        var depth = 1
        var expectingKey = true
        var response: String? = null
        while (position < input.length && depth > 0) {
            when (input[position]) {
                '"', '\'' -> {
                    val token = string(position)
                    position = token.next
                    if (depth == 1 && expectingKey && token.closed) {
                        position = whitespace(position)
                        if (input.getOrNull(position) == ':') {
                            position = whitespace(position + 1)
                            if ((token.value == "reply" || token.value == "response") &&
                                (input.getOrNull(position) == '"' || input.getOrNull(position) == '\'')) {
                                // An output limit can cut the quote or the object after useful text.
                                val value = string(position)
                                position = value.next
                                if (value.value.isNotBlank()) {
                                    // Prefer the established reply field regardless of field order.
                                    if (token.value == "reply") return value.value
                                    if (response == null) response = value.value
                                }
                            }
                        }
                        expectingKey = false
                    }
                }
                '{', '[' -> { depth++; position++ }
                '}', ']' -> { depth--; position++ }
                ',' -> { if (depth == 1) expectingKey = true; position++ }
                else -> position++
            }
        }
        return response ?: error("В ответе отсутствует непустое текстовое поле reply или response")
    }

    private fun string(start: Int): Text {
        val quote = input[start]
        val result = StringBuilder()
        var position = start + 1
        while (position < input.length) {
            val c = input[position++]
            if (c == quote) return Text(result.toString(), position, true)
            if (c != '\\') { result.append(c); continue }
            if (position == input.length) break
            when (val escaped = input[position++]) {
                '"', '\'', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    if (position + 4 > input.length) break
                    val code = input.substring(position, position + 4).toIntOrNull(16)
                    if (code == null) result.append("\\u") else {
                        result.append(code.toChar())
                        position += 4
                    }
                }
                else -> result.append('\\').append(escaped)
            }
        }
        // Do not display half an emoji when generation ends in the middle of a Unicode pair.
        if (result.isNotEmpty() && result.last().isHighSurrogate()) result.setLength(result.length - 1)
        return Text(result.toString(), position, false)
    }

    private fun whitespace(start: Int): Int {
        var position = start
        while (position < input.length && (input[position].isWhitespace() || input[position] == '\uFEFF')) position++
        return position
    }

    companion object {
        fun read(text: String): String = ChatReplyJson(text).reply()
    }
}
