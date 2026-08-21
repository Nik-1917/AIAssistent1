package com.example.aiassistent1.domain.context

import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelContextBuilderTest {
    private val builder = ModelContextBuilder()

    @Test
    fun `keeps only the two most recent user messages in chronological order`() {
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

        assertEquals(listOf("Второе", "Третье"), context.map(ChatMessage::content))
        assertEquals(listOf(MessageRole.USER, MessageRole.USER), context.map(ChatMessage::role))
    }

    @Test
    fun `keeps one user message when history is short`() {
        val context = builder.build(listOf(message(MessageRole.USER, "Единственное")))

        assertEquals(listOf("Единственное"), context.map(ChatMessage::content))
    }

    private fun message(role: MessageRole, content: String) = ChatMessage(role = role, content = content)
}
