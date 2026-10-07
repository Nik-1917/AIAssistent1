package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.ChatContextRepository
import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatContextSessionTest {
    private fun history() = (1..3).flatMap { n -> listOf(
        ChatMessage(id = "u$n", role = MessageRole.USER, content = "Вопрос $n"),
        ChatMessage(id = "a$n", role = MessageRole.ASSISTANT, content = "Ответ $n"),
    ) } + ChatMessage(id = "u4", role = MessageRole.USER, content = "Текущий вопрос")

    @Test fun `complete history is offered to engine before user is asked to exclude anything`() = runBlocking {
        val source = history()
        val engine = Engine().apply { budget = 1000 }
        val store = Store()
        assertEquals(listOf("ответ"), ChatContextSession(engine, store).respond("general", source) {
            error("There is enough room")
        }.toList())
        assertEquals(source.map { it.id }, engine.inputs.single().filterNot { it.role == MessageRole.SYSTEM }.map { it.id })
        assertTrue(store.writes.isEmpty())
        assertFalse(source.any { it.content.contains("используй эмодзи") })
    }

    @Test fun `automatic release removes oldest complete turns and retokenizes each candidate`() = runBlocking {
        val source = history()
        val engine = Engine()
        val store = Store()
        var prompts = 0
        val result = ChatContextSession(engine, store).respond("general", source) {
            prompts++
            assertEquals(listOf("u1", "u2", "u3"), it.turns.map { turn -> turn.id })
            ChatContextChoice.Automatic(remember = true)
        }.toList()
        assertEquals(listOf("ответ"), result)
        assertEquals(1, prompts)
        assertEquals(listOf(listOf("u4"), listOf("u2", "a2", "u3", "a3", "u4"), listOf("u3", "a3", "u4")), engine.counted)
        assertEquals(setOf("u1", "u2"), store.value.value.excludedTurnIds)
        assertEquals(ChatHistoryPolicy.AUTOMATIC, store.value.value.policy)
        assertEquals(7, source.size)
        ChatContextSession(engine, store).respond("general", source) { error("Preference and exclusions must survive a new session") }.toList()
        assertEquals(listOf("u3", "a3", "u4"), engine.inputs.last().filterNot { it.role == MessageRole.SYSTEM }.map { it.id })
    }

    @Test fun `manual selection can exclude a middle turn without excluding its neighbours`() = runBlocking {
        val engine = Engine().apply { budget = 480 }
        val store = Store()
        ChatContextSession(engine, store).respond("general", history()) {
            ChatContextChoice.Manual(setOf("u2"))
        }.toList()
        assertEquals(listOf(listOf("u4"), listOf("u1", "a1", "u3", "a3", "u4")), engine.counted)
        assertEquals(setOf("u2"), store.value.value.excludedTurnIds)
        assertEquals(ChatHistoryPolicy.ASK, store.value.value.policy)
    }

    @Test fun `one automatic choice covers a further RAM decline without enabling future automatic requests`() = runBlocking {
        val engine = Engine().apply { shrinkOnRetry = true }
        val store = Store()
        var prompts = 0
        ChatContextSession(engine, store).respond("general", history()) {
            prompts++
            ChatContextChoice.Automatic()
        }.toList()
        assertEquals(1, prompts)
        assertEquals(ChatHistoryPolicy.ASK, store.value.value.policy)
        assertEquals(setOf("u1", "u2", "u3"), store.value.value.excludedTurnIds)
        assertEquals(listOf("u4"), engine.inputs.last().filterNot { it.role == MessageRole.SYSTEM }.map { it.id })
    }

    @Test fun `insufficient manual selection asks again and then resumes the same current request`() = runBlocking {
        val engine = Engine()
        var calls = 0
        ChatContextSession(engine, Store()).respond("general", history()) { pressure ->
            calls++
            if (calls == 2) assertEquals(listOf("u2", "u3"), pressure.turns.map { it.id })
            ChatContextChoice.Manual(setOf(if (calls == 1) "u1" else "u2"), remember = calls == 1)
        }.toList()
        assertEquals(2, calls)
        assertEquals(1, engine.inputs.last().count { it.id == "u4" })
    }

    @Test fun `current request cannot be excluded by a stale or forged selection`() = runBlocking {
        val store = Store()
        val failure = runCatching { ChatContextSession(Engine(), store).respond("general", history()) {
            ChatContextChoice.Manual(setOf("u4"))
        }.toList() }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(store.writes.isEmpty())
    }

    @Test fun `selection does not overwrite a newer history preference saved while waiting`() = runBlocking {
        val store = Store()
        val engine = Engine().apply { budget = 480 }
        ChatContextSession(engine, store).respond("general", history()) {
            store.update("general", policy = ChatHistoryPolicy.AUTOMATIC)
            ChatContextChoice.Manual(setOf("u1"))
        }.toList()
        assertEquals(ChatHistoryPolicy.AUTOMATIC, store.value.value.policy)
        assertEquals(setOf("u1"), store.value.value.excludedTurnIds)
    }

    @Test fun `cancel preserves messages and context selection without retrying or summarizing`() = runBlocking {
        val store = Store()
        val engine = Engine()
        assertTrue(runCatching { ChatContextSession(engine, store).respond("general", history()) {
            ChatContextChoice.Cancel
        }.toList() }.exceptionOrNull() is CancellationException)
        assertEquals(1, engine.inputs.size)
        assertTrue(store.writes.isEmpty())
    }

    @Test fun `current request alone that cannot fit fails without discarding its text`() = runBlocking {
        val source = history().takeLast(1)
        val engine = Engine().apply { budget = 50 }
        val failure = runCatching { ChatContextSession(engine, Store()).respond("general", source) {
            error("No old turns to exclude")
        }.toList() }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("текущий запрос"))
        assertEquals("Текущий вопрос", source.single().content)
    }

    @Test fun `an oversized protected request never asks to exclude history or saves exclusions`() = runBlocking {
        for (policy in ChatHistoryPolicy.entries) for (reason in ContextCapacityReason.entries) {
            val source = history()
            val engine = Engine().apply { budget = 50; capacityReason = reason }
            val store = Store().apply { value.value = ChatContextSettings(policy = policy) }
            val failure = runCatching { ChatContextSession(engine, store).respond("general", source) {
                error("Excluding history cannot help the protected request")
            }.toList() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains(if (reason == ContextCapacityReason.MODEL_LIMIT)
                "предел контекста модели" else "оценке оперативной памяти"))
            assertEquals(listOf(listOf("u4")), engine.counted)
            assertTrue(store.writes.isEmpty())
            assertEquals(7, source.size)
        }
    }

    @Test fun `model allocation failure preserves its cause and never changes history exclusions`() = runBlocking {
        val original = ModelAllocationException(ModelAllocationFailure.METADATA_UNAVAILABLE, 1536)
        val engine = Engine().apply { allocationFailure = original }
        val store = Store()
        val failure = runCatching { ChatContextSession(engine, store).respond("general", history()) {
            error("Metadata failure is not history pressure")
        }.toList() }.exceptionOrNull()
        assertSame(original, failure)
        assertTrue(store.writes.isEmpty())
        assertTrue(engine.counted.isEmpty())
    }

    @Test fun `an already streamed partial answer is never restarted`() = runBlocking {
        val engine = Engine().apply { partial = true }
        val received = mutableListOf<String>()
        val failure = runCatching { ChatContextSession(engine, Store()).respond("general", history()) {
            error("Cannot restart partial generation")
        }.toList(received) }.exceptionOrNull()
        assertTrue(failure is PromptCapacityException)
        assertEquals(listOf("часть"), received)
        assertEquals(1, engine.inputs.size)
    }

    @Test fun `messages of another chat are never included`() = runBlocking {
        val engine = Engine().apply { budget = 1000 }
        ChatContextSession(engine, Store()).respond("general", history() +
            ChatMessage(id = "calendar", role = MessageRole.USER, content = "Встреча", chatId = "calendar")) { error("No pressure") }.toList()
        assertFalse(engine.inputs.single().any { it.chatId == "calendar" })
    }

    private class Store : ChatContextRepository {
        val value = MutableStateFlow(ChatContextSettings())
        val writes = mutableListOf<ChatContextSettings>()
        override fun observe(chatId: String) = value
        override suspend fun update(chatId: String, excludedTurnIds: Set<String>?, policy: ChatHistoryPolicy?) {
            value.value = value.value.copy(excludedTurnIds = excludedTurnIds ?: value.value.excludedTurnIds,
                policy = policy ?: value.value.policy)
            writes += value.value
        }
    }

    private class Engine : LLMEngine {
        var budget = 300
        var partial = false
        var shrinkOnRetry = false
        var capacityReason = ContextCapacityReason.MEMORY
        var allocationFailure: ModelAllocationException? = null
        val inputs = mutableListOf<List<ChatMessage>>()
        val counted = mutableListOf<List<String>>()
        override val state = MutableStateFlow<ModelState>(ModelState.Ready)
        private fun count(messages: List<ChatMessage>) = messages.sumOf { when (it.role) {
            MessageRole.SYSTEM -> 20; MessageRole.USER -> 100; MessageRole.ASSISTANT -> 80
        } }
        override suspend fun countTokens(messages: List<ChatMessage>): Int {
            assertEquals(MessageRole.SYSTEM, messages.first().role)
            assertTrue(messages.filter { it.role == MessageRole.USER }.all { it.content.endsWith("используй эмодзи") })
            counted += messages.filterNot { it.role == MessageRole.SYSTEM }.map { it.id }
            return count(messages)
        }
        override fun generateForTask(messages: List<ChatMessage>, task: GenerationTask) = flow {
            assertEquals(GenerationTask.CHAT, task)
            inputs += messages
            allocationFailure?.let { throw it }
            if (shrinkOnRetry && inputs.size == 2) budget = 120
            if (partial) emit("часть")
            if (partial || count(messages) > budget) throw PromptCapacityException(budget, capacityReason)
            emit("ответ")
        }
        override fun generate(messages: List<ChatMessage>): Flow<String> = error("Legacy generation")
        override suspend fun ensureLoaded() = Result.success(Unit)
        override fun updateParams(params: GenerationParams) = Unit
        override fun cancelGeneration() = Unit
        override fun unload() = Unit
        override fun close() = Unit
    }
}
