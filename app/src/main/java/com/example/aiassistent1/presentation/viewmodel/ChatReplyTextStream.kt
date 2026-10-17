package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.parser.AssistantResponseParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keeps a structured chat response out of UI, checkpoints, history and speech. */
internal class ChatReplyTextStream(
    private val parser: AssistantResponseParser,
    private val onInvalidReply: (raw: String, reason: String) -> Unit = { _, _ -> },
) {
    fun display(response: Flow<String>, retry: (suspend () -> Flow<String>)? = null): Flow<String> = flow {
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
        if (shape == Shape.PLAIN) return@flow
        val raw = pending.toString()
        val parsed = if (shape == Shape.STRUCTURED || raw.trimStart().startsWith('[')) plainReply(raw) else
            raw.takeIf { it.isNotBlank() && it.any { c -> !c.isWhitespace() && c != '\uFEFF' } }
        if (parsed != null) {
            emit(parsed)
        } else {
            currentCoroutineContext().ensureActive()
            onInvalidReply(raw, "В ответе нет непустого текста для отображения")
            if (retry != null) {
                currentCoroutineContext().ensureActive()
                // No retry callback on the second attempt; no error/checkpoint is emitted before it.
                display(retry()).collect { emit(it) }
            } else emit(INVALID_RESPONSE)
        }
    }

    private fun classify(prefix: String): Shape {
        val text = prefix.trimStart { it.isWhitespace() || it == '\uFEFF' }
        if (text.isEmpty()) return Shape.UNDECIDED
        if (text[0] == '{') return Shape.STRUCTURED
        if (text[0] == '"' || text[0] == '\'') return Shape.STRUCTURED
        if (text[0] == '[') {
            val body = text.drop(1).trimStart()
            if (body.isEmpty()) return Shape.UNDECIDED
            if (body.first() in "{[\"']") return Shape.STRUCTURED
            // A numeric/list-marker prefix is ambiguous until the next separator arrives.
            val scalar = body.takeWhile { it != ',' && it != ']' }.trim()
            if (scalar.toDoubleOrNull() != null || listOf("true", "false", "null").any { it.startsWith(scalar) } ||
                scalar.matches(Regex("[-0-9.eE+]*"))) {
                if (',' in body) return Shape.STRUCTURED
                if (']' !in body || body.substringAfter(']').isBlank()) return Shape.UNDECIDED
            }
        }
        if (text[0] == '`') {
            if (text.length < 3 && text.all { it == '`' }) return Shape.UNDECIDED
            if (text.startsWith("```")) return Shape.STRUCTURED
        }
        return Shape.PLAIN
    }

    private fun plainReply(raw: String): String? {
        val trimmed = raw.trim { it.isWhitespace() || it == '\uFEFF' }
        val candidate = if (trimmed.startsWith("```")) {
            val fenced = trimmed.removePrefix("```").trimStart()
            val body = when {
                fenced.startsWith("json", ignoreCase = true) &&
                    (fenced.length == 4 || fenced[4].isWhitespace() || fenced[4] in "{[\"") -> fenced.drop(4).trimStart()
                fenced.startsWith('{') || fenced.startsWith('[') || fenced.startsWith('"') -> fenced
                else -> return raw
            }
            body.removeSuffix("```").trim()
        } else {
            trimmed
        }
        return parser.parseChatReply(candidate).getOrNull()
    }

    private enum class Shape { UNDECIDED, PLAIN, STRUCTURED }

    companion object {
        const val RETRY_INSTRUCTION = "Ответь на последний запрос пользователя обычным непустым текстом, без JSON и служебных полей."
        const val INVALID_RESPONSE = "Не удалось разобрать ответ модели. Попробуйте изменить запрос."
    }
}
