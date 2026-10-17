package com.example.aiassistent1.domain.parser

/** Best-effort text presentation only. Never use this reader to validate or execute commands. */
internal class ChatReplyJson private constructor(private val input: String) {
    private data class Text(val value: String, val next: Int, val closed: Boolean)
    private class Frame(val isObject: Boolean, val metadata: Boolean = false) {
        var key: String? = null
        var expectingKey = isObject
    }

    private fun reply(): String {
        var position = whitespace(0)
        val first = input.getOrNull(position)
        if (first != '{' && first != '[') {
            val text = if (first == '"' || first == '\'') {
                val quoted = string(position)
                if (quoted.closed && input.substring(quoted.next).isNotBlank()) input.trim() else quoted.value
            } else input.trim()
            return text.takeIf(String::isNotBlank) ?: error("В ответе нет текста")
        }
        val stack = mutableListOf(Frame(first == '{'))
        position++
        val fallback = mutableListOf<String>()
        var response: String? = null
        fun accept(value: String) {
            val frame = stack.last()
            if (value.isBlank()) return
            // Field names alone must not hide prose. Only known protocol values/icons are metadata.
            if ((frame.metadata || frame.key in metadataKeys) &&
                (value in protocolValues || value.none { it.isLetterOrDigit() })) return
            fallback += value
            if (stack.size == 1 && frame.key == "response" && response == null) response = value
        }
        while (position < input.length && stack.isNotEmpty()) {
            position = whitespace(position)
            if (position >= input.length) break
            val frame = stack.last()
            when (input[position]) {
                '"', '\'' -> {
                    val token = string(position)
                    position = token.next
                    if (frame.isObject && frame.expectingKey && token.closed &&
                        input.getOrNull(whitespace(position)) == ':') {
                        frame.key = token.value
                        position = whitespace(position) + 1
                    } else {
                        // Retain the existing preferred field even in incomplete generated JSON.
                        if (stack.size == 1 && frame.key == "reply" && token.value.isNotBlank()) return token.value
                        accept(token.value)
                    }
                    frame.expectingKey = false
                }
                '{', '[' -> {
                    stack += Frame(input[position] == '{', frame.metadata || frame.key in metadataKeys)
                    frame.expectingKey = false
                    position++
                }
                '}', ']' -> { stack.removeAt(stack.lastIndex); position++ }
                ',' -> { frame.key = null; frame.expectingKey = frame.isObject; position++ }
                ':' -> { frame.expectingKey = false; position++ }
                else -> {
                    // Tolerate unquoted keys/values as presentation text, not as valid JSON.
                    val start = position
                    while (position < input.length && input[position] !in ":,{}[]\"'") position++
                    val value = input.substring(start, position).trim()
                    if (frame.isObject && frame.expectingKey && input.getOrNull(position) == ':') {
                        frame.key = value
                        position++
                    } else if (value !in setOf("null", "true", "false") && value.toDoubleOrNull() == null) {
                        accept(value)
                    }
                    frame.expectingKey = false
                }
            }
        }
        return response ?: fallback.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: error("В ответе нет непустого текста для отображения")
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
        private val metadataKeys = setOf("intent", "role", "emoji", "emoticons")
        private val protocolValues = setOf("assistant", "user", "system", "developer", "tool", "chat", "chat_reply",
            "calendar_add", "calendar_search", "calendar_sum", "calendar_update", "calendar_delete", "calendar_delete_range")
        fun read(text: String): String = ChatReplyJson(text).reply()
    }
}
