package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.parser.AssistantResponseParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Keeps a structured chat response out of UI, checkpoints, history and speech. */
internal class ChatReplyTextStream(
    private val parser: AssistantResponseParser,
    private val onInvalidReply: (String) -> Unit = {},
) {
    fun display(response: Flow<String>): Flow<String> = flow {
        val pending = StringBuilder()
        var shape = Shape.UNDECIDED
        response.collect { delta ->
            if (delta.isEmpty()) return@collect
            when (shape) {
                Shape.UNDECIDED -> {
                    pending.append(delta)
                    shape = classify(pending.toString())
                    if (shape == Shape.PLAIN) {
                        emit(pending.toString())
                        pending.setLength(0)
                    }
                }
                Shape.PLAIN -> emit(delta)
                Shape.STRUCTURED -> pending.append(delta)
            }
        }
        when (shape) {
            Shape.UNDECIDED -> if (pending.isNotEmpty()) emit(pending.toString())
            Shape.STRUCTURED -> emit(plainReply(pending.toString()))
            Shape.PLAIN -> Unit
        }
    }

    private fun classify(prefix: String): Shape {
        val text = prefix.trimStart { it.isWhitespace() || it == '\uFEFF' }
        if (text.isEmpty()) return Shape.UNDECIDED
        if (text[0] == '{') return Shape.STRUCTURED
        if (text[0] == '`') {
            if (text.length < 3 && text.all { it == '`' }) return Shape.UNDECIDED
            if (text.startsWith("```")) return Shape.STRUCTURED
        }
        return Shape.PLAIN
    }

    private fun plainReply(raw: String): String {
        val trimmed = raw.trim { it.isWhitespace() || it == '\uFEFF' }
        val candidate = if (trimmed.startsWith("```")) {
            val fenced = trimmed.removePrefix("```").trimStart()
            val body = when {
                fenced.startsWith("json", ignoreCase = true) &&
                    (fenced.length == 4 || fenced[4].isWhitespace() || fenced[4] == '{') -> fenced.drop(4).trimStart()
                fenced.startsWith('{') -> fenced
                else -> return raw
            }
            body.removeSuffix("```").trim()
        } else {
            trimmed
        }
        return parser.parseChatReply(candidate).getOrElse {
            onInvalidReply(it.message ?: "Неизвестная ошибка извлечения reply")
            INVALID_RESPONSE
        }
    }

    private enum class Shape { UNDECIDED, PLAIN, STRUCTURED }

    private companion object {
        const val INVALID_RESPONSE = "Не удалось разобрать ответ модели. Попробуйте изменить запрос."
    }
}
