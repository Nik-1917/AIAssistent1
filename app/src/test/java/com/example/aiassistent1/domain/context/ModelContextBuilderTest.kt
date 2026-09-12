package com.example.aiassistent1.domain.context

import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelContextBuilderTest {
    private val builder = ModelContextBuilder()

    @Test
    fun `keeps two recent user messages and last assistant reply in chronological order`() {
        val context = builder.build(
            listOf(
                message(MessageRole.USER, "Первое"),
                message(MessageRole.ASSISTANT, "Ответ на первое"),
                message(MessageRole.USER, "Второе"),
                message(MessageRole.SYSTEM, "Служебное"),
                message(MessageRole.ASSISTANT, "Ответ на второе"),
                message(MessageRole.USER, "Третье"),
            ),
        )

        assertEquals(listOf("Второе", "Ответ на второе", "Третье"), context.map(ChatMessage::content))
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            context.map(ChatMessage::role),
        )
    }

    @Test
    fun `keeps one user message when history is short`() {
        val context = builder.build(listOf(message(MessageRole.USER, "Единственное")))

        assertEquals(listOf("Единственное"), context.map(ChatMessage::content))
    }

    @Test
    fun `appends chat style instruction to visible chat mode context only`() {
        val context = builder.build(
            chatHistory = listOf(
                message(MessageRole.USER, "Первое"),
                message(MessageRole.USER, "Второе"),
            ),
            appendChatStyleInstruction = true,
        )

        assertEquals(
            listOf(
                "Первое\n\nотвечай очень вежливо используй эмодзи",
                "Второе\n\nотвечай очень вежливо используй эмодзи",
            ),
            context.map(ChatMessage::content),
        )
    }

    @Test
    fun `keeps only recent user messages before appending chat style instruction`() {
        val context = builder.build(
            chatHistory = listOf(
                message(MessageRole.USER, "Первое"),
                message(MessageRole.USER, "Второе"),
                message(MessageRole.USER, "Третье"),
            ),
            appendChatStyleInstruction = true,
        )

        assertEquals(
            listOf(
                "Второе\n\nотвечай очень вежливо используй эмодзи",
                "Третье\n\nотвечай очень вежливо используй эмодзи",
            ),
            context.map(ChatMessage::content),
        )
    }

    private fun message(role: MessageRole, content: String) = ChatMessage(role = role, content = content)

    @Test
    fun `does not rewrite history or append style instruction to assistant reply`() {
        val history = listOf(
            message(MessageRole.USER, "Сохрани РЕГИСТР\n123 https://example.com"),
            message(MessageRole.ASSISTANT, "Ответ № 1\n  пробелы  "),
            message(MessageRole.USER, "Следующий вопрос"),
        )
        val original = history.toList()

        val context = builder.build(history, appendChatStyleInstruction = true)

        assertEquals(original, history)
        assertEquals(history[1], context[1])
        assertEquals(history.map(ChatMessage::id), context.map(ChatMessage::id))
        assertEquals(history.map(ChatMessage::createdAtEpochMillis), context.map(ChatMessage::createdAtEpochMillis))
        assertEquals(history[0].content + "\n\nотвечай очень вежливо используй эмодзи", context[0].content)
        assertEquals(history[2].content + "\n\nотвечай очень вежливо используй эмодзи", context[2].content)
    }

    @Test
    fun `calendar context contains only latest request while full history remains intact`() {
        val history = listOf(
            message(MessageRole.USER, "Запиши тренировку"),
            message(MessageRole.ASSISTANT, "Готово"),
            message(MessageRole.USER, "Найди встречу"),
            message(MessageRole.ASSISTANT, "Найдена встреча"),
        )

        assertEquals(listOf(history[2]), builder.build(history, isCalendarMode = true))
        assertEquals(4, history.size)
    }

    @Test
    fun `handles empty and short histories without duplicating messages`() {
        assertEquals(emptyList<ChatMessage>(), builder.build(emptyList()))
        val history = listOf(
            message(MessageRole.USER, "Один вопрос"),
            message(MessageRole.ASSISTANT, "Первый ответ"),
            message(MessageRole.ASSISTANT, "Последний ответ"),
        )
        assertEquals(listOf(history[0], history[2]), builder.build(history))
    }
}
