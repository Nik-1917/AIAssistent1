package com.example.aiassistent1

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.local.ChatDatabase
import com.example.aiassistent1.data.repository.RoomChatRepository
import com.example.aiassistent1.di.AppModule
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatStorageInstrumentedTest {
    @Test
    fun productionMessagesSurviveDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = AppModule.provideChatRepository(context)
        val marker = "CHAT_DB_V2_CHECK_${UUID.randomUUID()}"
        val messages = listOf(
            ChatMessage(
                id = "$marker-general",
                role = MessageRole.USER,
                content = "$marker\nПроверка сохранения общего чата.",
                createdAtEpochMillis = System.currentTimeMillis(),
                isInterrupted = false,
                chatId = "general",
            ),
            ChatMessage(
                id = "$marker-calendar",
                role = MessageRole.ASSISTANT,
                content = "$marker\nПроверка сохранения календарного чата.",
                createdAtEpochMillis = System.currentTimeMillis() + 1,
                isInterrupted = true,
                chatId = "calendar",
            ),
        )
        var verified = false
        try {
            withTimeout(10_000L) {
                messages.forEach { repository.saveMessage(it) }
                val reopened = Room.databaseBuilder(
                    context,
                    ChatDatabase::class.java,
                    "ai_assistant.db",
                ).build()
                try {
                    assertEquals(2, reopened.openHelper.readableDatabase.version)
                    val restoredRepository = RoomChatRepository(reopened.chatMessageDao())
                    messages.forEach { expected ->
                        val restored = restoredRepository.observeMessages(expected.chatId).first()
                        assertEquals(expected, restored.single { it.id == expected.id })
                    }
                } finally {
                    reopened.close()
                }
            }
            verified = true
        } finally {
            // Retain only these probes when explicitly requested for a manual cold-start check.
            // Ordinary test runs remove their own records and leave other chat history intact.
            val keepForRestart = InstrumentationRegistry.getArguments()
                .getString("keepChatProbesForRestart") == "true"
            if (!verified || !keepForRestart) {
                messages.forEach { repository.deleteMessage(it.id) }
            }
        }
    }
}
