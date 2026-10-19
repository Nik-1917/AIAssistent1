package com.example.aiassistent1.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.local.ChatDatabase
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarDraftReplyStorageTest {
    @Test fun deletingReplyPreservesUserAndOtherRepliesAfterDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "draft-reply-test-${UUID.randomUUID()}.db"
        val user = ChatMessage("user", MessageRole.USER, "Создай встречу", 1, false, "calendar")
        val reply = ChatMessage("reply", MessageRole.ASSISTANT, "Новое событие: Встреча.", 2, false, "calendar")
        val otherReply = reply.copy(id = "other-reply", content = "Другой ответ", createdAtEpochMillis = 3)
        val generalReply = reply.copy(id = "general-reply", chatId = "general")
        try {
            val database = Room.databaseBuilder(context, ChatDatabase::class.java, databaseName).build()
            try {
                val repository = RoomChatRepository(database.chatMessageDao())
                listOf(user, reply, otherReply, generalReply).forEach { repository.saveMessage(it) }
                repository.deleteCalendarDraftReply(reply.id)
                repository.deleteCalendarDraftReply(reply.id) // Recovery must be idempotent.
                repository.deleteCalendarDraftReply(user.id)
                repository.deleteCalendarDraftReply(generalReply.id)
                repository.deleteCalendarDraftReply("missing")
                assertEquals(listOf(user, otherReply), repository.observeMessages("calendar").first())
                assertEquals(listOf(generalReply), repository.observeMessages("general").first())
            } finally { database.close() }
            val reopened = Room.databaseBuilder(context, ChatDatabase::class.java, databaseName).build()
            try {
                val repository = RoomChatRepository(reopened.chatMessageDao())
                assertEquals(listOf(user, otherReply), repository.observeMessages("calendar").first())
                assertEquals(listOf(generalReply), repository.observeMessages("general").first())
            } finally { reopened.close() }
        } finally { context.deleteDatabase(databaseName) }
    }
}
