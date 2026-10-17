package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.parser.AssistantResponseParser
import com.example.aiassistent1.domain.parser.ChatReplyJson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile

/** Chat-only text snapshots. The calendar continues to consume the original, unmodified flow. */
internal class ChatReplyTextStream(
    private val parser: AssistantResponseParser,
    private val onInvalidReply: (raw: String, reason: String) -> Unit = { _, _ -> },
) {
    fun snapshots(
        response: Flow<String>,
        onRepetition: () -> Unit = {},
        retry: (suspend () -> Flow<String>)? = null,
    ): Flow<String> = flow {
        val pending = StringBuilder()
        val repetitions = ChatRepetitionGuard()
        var displayed = ""
        suspend fun publish(finished: Boolean) {
            val candidate = readable(pending.toString(), finished) ?: return
            if (candidate.isNotBlank() && candidate != displayed) {
                displayed = candidate
                emit(candidate)
            }
        }
        response.takeWhile { delta ->
            pending.append(delta.take(repetitions.accept(delta)))
            if (repetitions.stopped) onRepetition()
            publish(finished = false)
            // Cancels and joins the upstream producer, without cancelling the chat request.
            !repetitions.stopped
        }.collect {}
        currentCoroutineContext().ensureActive()
        publish(finished = true)
        if (displayed.isNotBlank()) return@flow
        if (repetitions.stopped) {
            emit(REPETITION_STOP)
            return@flow
        }
        onInvalidReply(pending.toString(), "В ответе нет непустого текста для отображения")
        if (retry != null) {
            currentCoroutineContext().ensureActive()
            snapshots(retry(), onRepetition).collect { emit(it) }
        } else emit(INVALID_RESPONSE)
    }

    private fun readable(raw: String, finished: Boolean): String? {
        var text = raw.trimStart { it.isWhitespace() || it == '\uFEFF' }
        if (text.isEmpty()) return null
        if (text.startsWith('`')) {
            if (!finished && text.length < 3) return null
            if (text.startsWith("```")) {
                text = text.drop(3)
                val body = text.indexOfFirst { it == '\n' || it == '{' || it == '[' || it == '"' || it == '\'' }
                if (body < 0) return if (finished) text.removeSuffix("```").trim().takeIf(String::isNotEmpty) else null
                text = text.substring(body).trimStart().trimEnd().removeSuffix("```").trimEnd()
            }
        }
        // Also accept field/value envelopes without outer braces, with arbitrary field names.
        val field = BARE_FIELD.find(text)
        if (field != null) {
            val separator = field.range.last
            text = "{" + text.substring(0, separator) + ":" + text.substring(separator + 1)
        }
        // A single opening quote on a field name must not swallow its actual value.
        val body = if (text.startsWith('{')) text.drop(1).trimStart() else text
        val brokenKey = BROKEN_FIELD.find(body)
        if (brokenKey != null) {
            text = "{\"" + brokenKey.groupValues[1] + "\":" + body.substring(brokenKey.range.last + 1)
        }
        // XML-like envelopes are display data only, never executable markup.
        if (text.startsWith('<') && XML_OPEN.containsMatchIn(text)) {
            text = text.replace(XML_TAG, "").replace(Regex("<[^>]*$"), "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
        }
        val structured = text.startsWith('{') || text.startsWith('"') || text.startsWith('\'') ||
            (text.startsWith('[') && !LIST_MARKER.containsMatchIn(text))
        if (!structured) return text
        return if (finished) parser.parseChatReply(text).getOrNull() else ChatReplyJson.preview(text)
    }

    companion object {
        private val BARE_FIELD = Regex("^(?:\"[^\"\\n]+\"|'[^'\\n]+'|[\\p{L}\\p{N}_-]+)\\s*[:=]")
        private val BROKEN_FIELD = Regex("^[\"']?([\\p{L}\\p{N}_-]+)[\"']?\\s*[:=]")
        private val LIST_MARKER = Regex("^\\[\\d+][ \\t]+\\S")
        private val XML_OPEN = Regex("^<[\\p{L}_][^>]*>")
        private val XML_TAG = Regex("</?[\\p{L}_][^>]*>")
        const val RETRY_INSTRUCTION = "Ответь на последний запрос пользователя обычным непустым текстом, без JSON и служебных полей."
        const val INVALID_RESPONSE = "Не удалось разобрать ответ модели. Попробуйте изменить запрос."
        const val REPETITION_STOP = "Генерация остановлена: обнаружено пять повторов подряд."
    }
}
