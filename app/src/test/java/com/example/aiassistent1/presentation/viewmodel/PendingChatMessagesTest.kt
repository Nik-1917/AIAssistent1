package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingChatMessagesTest {
    private val user = ChatMessage("user", MessageRole.USER, "Вопрос", 100L, false, "general")
    private val answer = ChatMessage("answer", MessageRole.ASSISTANT, "Свежий текст", 200L, false, "general")

    @Test
    fun `discard removes only linked calendar reply from live and delayed stored history`() {
        val pending = PendingChatMessages()
        val request = user.copy(chatId = "calendar")
        val reply = answer.copy(chatId = "calendar")
        val otherReply = reply.copy(id = "other-answer")
        pending.put(reply)
        pending.discardCalendarReplies(setOf(reply.id))
        assertNull(pending.find(reply.id))
        val oldHistory = listOf(request, reply, otherReply)
        assertEquals(listOf(request, otherReply), pending.merge("calendar", oldHistory))
        assertEquals(listOf(request, otherReply), pending.merge("calendar", listOf(request, otherReply)))
        pending.put(reply, isStreaming = true)
        assertNull(pending.find(reply.id))
        assertEquals(listOf(request, otherReply), pending.merge("calendar", oldHistory))
    }

    @Test
    fun `discard marker cannot hide a user message or a reply in another chat`() {
        val pending = PendingChatMessages()
        pending.discardCalendarReplies(setOf(answer.id, user.id))
        val calendarUser = user.copy(chatId = "calendar")
        pending.put(calendarUser)
        pending.put(answer)
        assertEquals(listOf(calendarUser), pending.merge("calendar", listOf(calendarUser)))
        assertEquals(listOf(user, answer), pending.merge("general", listOf(user, answer)))
    }

    @Test
    fun `old Room checkpoints cannot roll back live text or show an interruption badge`() {
        val pending = PendingChatMessages()
        pending.put(answer, isStreaming = true)
        val checkpoint = answer.copy(content = "Свежий", isInterrupted = true)
        repeat(3) {
            val restored = pending.merge("general", listOf(user, checkpoint))
            assertEquals(listOf(user, answer), restored)
            assertFalse(restored.last().isInterrupted)
        }
    }

    @Test
    fun `switching chats preserves live text before and after its first checkpoint`() {
        val pending = PendingChatMessages()
        pending.put(user)
        pending.put(answer, isStreaming = true)
        val calendar = answer.copy(id = "calendar-answer", chatId = "calendar", content = "Календарь")
        assertEquals(listOf(user, answer), pending.merge("general", emptyList()))
        assertEquals(listOf(calendar), pending.merge("calendar", listOf(calendar)))
        assertEquals(listOf(user, answer), pending.merge("general", listOf(user)))
        assertEquals(listOf(user, answer), pending.merge("general", listOf(user, answer.copy(isInterrupted = true))))
    }

    @Test
    fun `terminal calendar reply stays visible until Room observes the exact final version`() {
        val pending = PendingChatMessages()
        val raw = answer.copy(chatId = "calendar", content = "{\"reply\":\"Готово\"}", isInterrupted = true)
        val reply = raw.copy(content = "Готово", isInterrupted = false)
        pending.put(reply)
        assertEquals(listOf(reply), pending.merge("calendar", listOf(raw)))
        assertEquals(reply, pending.find(reply.id))
        assertEquals(listOf(reply), pending.merge("calendar", listOf(reply)))
        assertNull(pending.find(reply.id))
        // A subsequent command result or deletion is authoritative after the final write is observed.
        val commandReply = reply.copy(content = "Событие удалено")
        assertEquals(listOf(commandReply), pending.merge("calendar", listOf(commandReply)))
        assertTrue(pending.merge("calendar", emptyList()).isEmpty())
    }

    @Test
    fun `matching checkpoints remain protected until collection has ended`() {
        val pending = PendingChatMessages()
        pending.put(answer, isStreaming = true)
        assertEquals(listOf(answer), pending.merge("general", listOf(answer)))
        assertEquals(answer, pending.find(answer.id))
        val interrupted = answer.copy(isInterrupted = true)
        pending.put(interrupted)
        assertEquals(listOf(interrupted), pending.merge("general", listOf(answer)))
        assertEquals(listOf(interrupted), pending.merge("general", listOf(interrupted)))
        assertNull(pending.find(answer.id))
    }

    @Test
    fun `retry removal and chat clear cannot restore discarded local responses`() {
        val pending = PendingChatMessages()
        val calendar = answer.copy(id = "calendar-answer", chatId = "calendar")
        pending.put(answer, isStreaming = true)
        pending.put(calendar, isStreaming = true)
        pending.remove(answer.id)
        assertEquals(listOf(user), pending.merge("general", listOf(user)))
        val retryAnswer = answer.copy(id = "retry")
        pending.put(retryAnswer, isStreaming = true)
        assertEquals(listOf(user, retryAnswer), pending.merge("general", listOf(user)))
        pending.clear("general")
        assertTrue(pending.merge("general", emptyList()).isEmpty())
        assertEquals(listOf(calendar), pending.merge("calendar", emptyList()))
    }

    @Test
    fun `a fresh process reads the checkpoint as interrupted without any in-memory overlay`() {
        val checkpoint = answer.copy(isInterrupted = true)
        val restored = PendingChatMessages().merge("general", listOf(user, checkpoint))
        assertEquals(listOf(user, checkpoint), restored)
        assertTrue(restored.last().isInterrupted)
    }
}
