package com.example.aiassistent1.domain.usecase

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import com.example.aiassistent1.data.repository.DataStoreSettingsRepository
import com.example.aiassistent1.data.repository.TestPreferencesDataStore
import com.example.aiassistent1.domain.interfaces.ChatRepository
import com.example.aiassistent1.domain.model.AppDestination
import com.example.aiassistent1.domain.model.AppNavigationState
import com.example.aiassistent1.domain.model.AppSession
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.ChatScrollPosition
import com.example.aiassistent1.domain.model.MessageRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveAppSessionUseCaseTest {
    @Test
    fun `delayed settings and history never emit a placeholder calendar mode or empty chat`() = runTest {
        val preferencesReady = CompletableDeferred<Unit>()
        val historyReady = CompletableDeferred<Unit>()
        val store = TestPreferencesDataStore(
            preferencesOf(booleanPreferencesKey("system_prompt_enabled") to false),
            preferencesReady,
        )
        val settings = DataStoreSettingsRepository(store, backgroundScope)
        val history = RecordingChatRepository(mapOf("general" to historyReady))
        val messages = sampleMessages("general")
        history.seed("general", messages)
        val sessions = mutableListOf<AppSession>()
        backgroundScope.launch { ObserveAppSessionUseCase(settings, history)().collect { sessions += it } }
        runCurrent()

        assertTrue(sessions.isEmpty())
        assertTrue(history.observedChats.isEmpty())
        preferencesReady.complete(Unit)
        runCurrent()
        assertTrue(sessions.isEmpty())
        assertEquals(listOf("general"), history.observedChats)

        historyReady.complete(Unit)
        runCurrent()
        assertEquals(listOf(AppSession(AppNavigationState(AppDestination.CHAT, false), messages, ChatScrollPosition())), sessions)
        assertEquals(0, history.writes)
    }

    @Test
    fun `switching pages and restarting preserves every field in both histories including on first run`() = runTest {
        val store = TestPreferencesDataStore()
        val settings = DataStoreSettingsRepository(store, backgroundScope)
        val history = RecordingChatRepository()
        val originalGeneral = sampleMessages("general")
        val originalCalendar = sampleMessages("calendar")
        history.seed("general", originalGeneral)
        history.seed("calendar", originalCalendar)
        val scroll = ChatScrollPosition(originalGeneral.first().id, 37)
        settings.setChatScrollPosition(scroll)

        for (state in listOf(
            AppNavigationState(AppDestination.CHAT, false),
            AppNavigationState(AppDestination.CHAT, true),
            AppNavigationState(AppDestination.CALENDAR, true),
            AppNavigationState(AppDestination.CALENDAR, false),
        )) {
            settings.setSystemPromptEnabled(state.isCalendarMode)
            settings.setAppDestination(state.destination)
            val reopenedSettings = DataStoreSettingsRepository(store, backgroundScope)
            val restored = ObserveAppSessionUseCase(reopenedSettings, history)().first()
            assertEquals(state, restored.navigation)
            assertEquals(if (state.isCalendarMode) originalCalendar else originalGeneral, restored.messages)
            assertEquals(scroll, restored.scrollPosition)
        }

        assertEquals(originalGeneral, history.observeMessages("general").first())
        assertEquals(originalCalendar, history.observeMessages("calendar").first())
        assertEquals(0, history.writes)
    }

    @Test
    fun `mode switch waits for matching history and cancels stale history loading`() = runTest {
        val settings = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        settings.setSystemPromptEnabled(false)
        val calendarReady = CompletableDeferred<Unit>()
        val history = RecordingChatRepository(mapOf("calendar" to calendarReady))
        history.seed("general", sampleMessages("general"))
        history.seed("calendar", sampleMessages("calendar"))
        val sessions = mutableListOf<AppSession>()
        backgroundScope.launch { ObserveAppSessionUseCase(settings, history)().collect { sessions += it } }
        runCurrent()

        settings.setSystemPromptEnabled(true)
        runCurrent()
        assertEquals(1, sessions.size)
        settings.setSystemPromptEnabled(false)
        runCurrent()
        calendarReady.complete(Unit)
        runCurrent()

        assertTrue(sessions.all { it.navigation.chatId == "general" })
        assertTrue(sessions.all { session -> session.messages.all { it.chatId == session.navigation.chatId } })
    }

    @Test
    fun `calendar history stays subscribed to appended messages and explicit deletions`() = runTest {
        val settings = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        val history = RecordingChatRepository()
        val initial = sampleMessages("calendar")
        history.seed("calendar", initial)
        val sessions = mutableListOf<AppSession>()
        backgroundScope.launch { ObserveAppSessionUseCase(settings, history)().collect { sessions += it } }
        runCurrent()
        val added = ChatMessage(role = MessageRole.USER, content = "Следующая команда", chatId = "calendar")
        history.saveMessage(added)
        runCurrent()
        assertEquals(initial + added, sessions.last().messages)

        history.deleteAllMessages("calendar")
        runCurrent()
        assertEquals(emptyList<ChatMessage>(), sessions.last().messages)
    }

    private fun sampleMessages(chatId: String) = listOf(
        ChatMessage("$chatId-user", MessageRole.USER, "Текст БЕЗ изменений\n  123 https://example.com?a=1  ", 100L, false, chatId),
        ChatMessage("$chatId-reply", MessageRole.ASSISTANT, "Ответ\nсо знаками: № 2, 3.14!", 200L, true, chatId),
    )

    private class RecordingChatRepository(
        private val readGates: Map<String, CompletableDeferred<Unit>> = emptyMap(),
    ) : ChatRepository {
        private val histories = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()
        val observedChats = mutableListOf<String>()
        var writes = 0
            private set

        private fun history(chatId: String) = histories.getOrPut(chatId) { MutableStateFlow(emptyList()) }
        fun seed(chatId: String, messages: List<ChatMessage>) { history(chatId).value = messages }

        override fun observeMessages(chatId: String): Flow<List<ChatMessage>> = flow {
            observedChats += chatId
            readGates[chatId]?.await()
            emitAll(history(chatId))
        }

        override suspend fun saveMessage(message: ChatMessage) {
            writes++
            history(message.chatId).value = history(message.chatId).value.filterNot { it.id == message.id } + message
        }

        override suspend fun deleteMessage(id: String) {
            writes++
            histories.values.forEach { it.value = it.value.filterNot { message -> message.id == id } }
        }

        override suspend fun deleteAllMessages(chatId: String) {
            writes++
            history(chatId).value = emptyList()
        }
    }
}
