package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.context.ContextCapacityException
import com.example.aiassistent1.domain.context.ModelContextBuilder
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.model.ModelState
import com.llamatik.library.platform.GenStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LlamatikEngineContextTest {
    private val params = GenerationParams(contextSize = 1024, maxContextSize = 8192)
    private fun user(text: String) = ChatMessage(role = MessageRole.USER, content = text)

    @Test fun `tokenizer and generator receive the same frozen complete prompt`() = runBlocking {
        withEngine { engine, native ->
            native.tokenCount = 90
            native.messageTokenCount = 30
            val history = listOf(user("previous question"),
                ChatMessage(role = MessageRole.ASSISTANT, content = "previous reply"), user("текущий запрос 😀"))
            val messages = (listOf(ChatMessage(role = MessageRole.SYSTEM, content = "system date and instructions")) +
                ModelContextBuilder().build(history, appendChatStyleInstruction = true)).toMutableList()
            val prepared = engine.prepareGeneration(messages, userMessageForSizing = "текущий запрос 😀").getOrThrow()
            val inspected = native.inspectedPrompts.single()
            assertTrue(inspected.startsWith("<|im_start|>system\nsystem date and instructions\n<|im_end|>\n"))
            assertTrue(inspected.contains("previous question"))
            assertTrue(inspected.contains("<|im_start|>assistant\nprevious reply\n<|im_end|>"))
            assertTrue(inspected.contains("текущий запрос 😀\n\nотвечай очень вежливо используй эмодзи"))
            assertTrue(inspected.endsWith("<|im_start|>assistant\n"))
            messages[messages.lastIndex] = user("changed after tokenization")
            prepared.toList()
            assertEquals(listOf(inspected), native.prompts)
            assertEquals(1, native.inspectedPrompts.size)
            assertEquals(listOf("текущий запрос 😀"), native.inspectedUserMessages)
            assertEquals(634, engine.contextBudget.value!!.requiredTokens)
            assertEquals(32, engine.contextBudget.value!!.reserveTokens)
        }
    }

    @Test fun `a new message is classified for each request while the complete prompt stays authoritative`() = runBlocking {
        withEngine { engine, native ->
            native.tokenCount = 450
            for (size in listOf(322, 323, 322)) {
                native.messageTokenCount = size
                engine.prepareGeneration(listOf(user("current")), userMessageForSizing = "current").getOrThrow().toList()
            }
            assertEquals(listOf(1024, 2048, 1024), native.loadedSizes)
            assertEquals(listOf(512, 1024, 512), native.generatedLimits)
            native.tokenCount = 10000
            assertTrue(engine.prepareGeneration(listOf(user("current")), "current").exceptionOrNull() is ContextCapacityException)
            assertEquals(3, native.prompts.size)
        }
    }

    @Test fun `native generation receives paired limits on every step and after shrink`() = runBlocking {
        withEngine { engine, native ->
            for (tokens in listOf(100, 385, 897, 1921, 100)) {
                native.tokenCount = tokens
                engine.prepareGeneration(listOf(user("request"))).getOrThrow().toList()
                assertEquals(engine.contextBudget.value!!.responseTokens, native.current.maxTokens)
            }
            assertEquals(listOf(1024, 2048, 4096, 8192, 1024), native.loadedSizes)
            assertEquals(listOf(512, 1024, 2048, 4096, 512), native.generatedLimits)
            assertEquals(1024, params.contextSize)
            assertEquals(512, params.maxTokens)
        }
    }

    @Test fun `reloads for growth and shrink but reuses a matching context`() = runBlocking {
        withEngine { engine, native ->
            for (text in listOf("short", "LONG", "LONG again", "short again")) {
                engine.prepareGeneration(listOf(user(text))).getOrThrow().toList()
            }
            assertEquals(listOf(1024, 8192, 1024), native.loadedSizes)
            assertEquals(1024, engine.contextBudget.value!!.contextSize)
            assertEquals(4, native.prompts.size)
            assertEquals(1024, params.contextSize)
        }
    }

    @Test fun `small new message retains a long previous message until it leaves selected history`() = runBlocking {
        withEngine { engine, native ->
            val history = mutableListOf(user("LONG"))
            val builder = ModelContextBuilder()
            engine.prepareGeneration(builder.build(history)).getOrThrow().toList()
            history += ChatMessage(role = MessageRole.ASSISTANT, content = "previous reply")
            history += user("small follow-up")
            engine.prepareGeneration(builder.build(history, appendChatStyleInstruction = true)).getOrThrow().toList()
            assertEquals(8192, engine.contextBudget.value!!.contextSize)
            assertTrue(native.inspectedPrompts.last().contains("previous reply"))
            assertTrue(native.inspectedPrompts.last().contains("отвечай очень вежливо используй эмодзи"))
            history += user("another small request")
            engine.prepareGeneration(builder.build(history)).getOrThrow().toList()
            assertEquals(listOf(8192, 1024), native.loadedSizes)
            assertEquals("LONG", history.first().content)
        }
    }

    @Test fun `calendar mode can shrink on the immediate next small request`() = runBlocking {
        withEngine { engine, native ->
            val history = mutableListOf(user("LONG"))
            val builder = ModelContextBuilder()
            engine.prepareGeneration(builder.build(history, isCalendarMode = true)).getOrThrow().toList()
            history += user("short")
            engine.prepareGeneration(builder.build(history, isCalendarMode = true)).getOrThrow().toList()
            assertEquals(listOf(8192, 1024), native.loadedSizes)
            assertEquals(2, history.size)
        }
    }

    @Test fun `oversized request fails before model load and generation`() = runBlocking {
        withEngine { engine, native ->
            native.tokenCount = 10000
            val failure = engine.prepareGeneration(listOf(user("unchanged draft"))).exceptionOrNull()
            assertTrue(failure is ContextCapacityException)
            assertTrue(native.loadedSizes.isEmpty())
            assertTrue(native.prompts.isEmpty())
        }
    }

    @Test fun `settings changed after preflight affect the next request only`() = runBlocking {
        withEngine { engine, native ->
            val prepared = engine.prepareGeneration(listOf(user("LONG"))).getOrThrow()
            engine.updateParams(params.copy(autoContextEnabled = false))
            assertTrue(native.prompts.isEmpty())
            prepared.toList()
            assertEquals(listOf(8192), native.loadedSizes)
            assertTrue(engine.prepareGeneration(listOf(user("LONG"))).exceptionOrNull() is ContextCapacityException)
            assertEquals(1, native.prompts.size)
        }
    }

    @Test fun `failed enlargement releases partial load and allows retry at minimum`() = runBlocking {
        withEngine { engine, native ->
            engine.prepareGeneration(listOf(user("short"))).getOrThrow().toList()
            native.failAtSize = 8192
            assertTrue(engine.prepareGeneration(listOf(user("LONG"))).isFailure)
            assertTrue(engine.state.value is ModelState.Error)
            assertEquals(1, native.prompts.size)
            engine.prepareGeneration(listOf(user("short retry"))).getOrThrow().toList()
            assertEquals(1024, engine.contextBudget.value!!.contextSize)
            assertEquals(ModelState.Ready, engine.state.value)
        }
    }

    @Test fun `unload cancels active generation before releasing its native model`() = runBlocking {
        withEngine { engine, native ->
            val started = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            native.onGenerate = {
                started.countDown()
                check(cancelled.await(5, TimeUnit.SECONDS))
                assertEquals(0, native.shutdowns)
            }
            native.onCancel = { cancelled.countDown() }
            val pending = async(Dispatchers.Default) {
                engine.prepareGeneration(listOf(user("short"))).getOrThrow().toList()
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            engine.unload()
            pending.await()
            assertEquals(1, native.shutdowns)
            assertEquals(ModelState.Unloaded, engine.state.value)
        }
    }

    @Test fun `normal completion does not cancel a subsequent request`() = runBlocking {
        withEngine { engine, native ->
            repeat(12) {
                engine.prepareGeneration(listOf(user("short $it"))).getOrThrow().toList()
            }
            assertEquals(12, native.prompts.size)
            assertEquals(0, native.cancels)
            assertEquals(listOf(1024), native.loadedSizes)
        }
    }

    @Test fun `cancelling the collector signals a blocked native generation and allows retry`() = runBlocking {
        withEngine { engine, native ->
            val started = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            native.onGenerate = {
                started.countDown()
                check(cancelled.await(5, TimeUnit.SECONDS))
                assertEquals(0, native.shutdowns)
            }
            native.onCancel = { cancelled.countDown() }
            val pending = async(Dispatchers.Default) {
                engine.prepareGeneration(listOf(user("short"))).getOrThrow().toList()
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertTrue(native.cancels > 0)
            native.onGenerate = {}
            engine.prepareGeneration(listOf(user("retry"))).getOrThrow().toList()
            assertEquals(1024, engine.contextBudget.value!!.contextSize)
        }
    }

    private suspend fun withEngine(test: suspend (LlamatikEngine, FakeRuntime) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val native = FakeRuntime()
        val engine = LlamatikEngine(object : ModelProvider {
            override suspend fun getModelPath() = Result.success("model.gguf")
        }, params, executor, native)
        try { test(engine, native) } finally {
            engine.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private class FakeRuntime : LlamaRuntime {
        var current = GenerationParams()
        val loadedSizes = mutableListOf<Int>()
        val inspectedPrompts = mutableListOf<String>()
        val inspectedUserMessages = mutableListOf<String?>()
        val prompts = mutableListOf<String>()
        val generatedLimits = mutableListOf<Int>()
        var tokenCount: Int? = null
        var messageTokenCount = 30
        var failAtSize: Int? = null
        var shutdowns = 0
        var cancels = 0
        var onGenerate: () -> Unit = {}
        var onCancel: () -> Unit = {}
        override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) { current = params }
        override fun load(path: String): Boolean {
            loadedSizes += current.contextSize
            return current.contextSize != failAtSize
        }
        override fun inspectPrompt(path: String, prompt: String, userMessageForSizing: String?): PromptInspection {
            inspectedPrompts += prompt
            inspectedUserMessages += userMessageForSizing
            return PromptInspection(tokenCount ?: if (prompt.contains("LONG")) 3200 else 100, 32768,
                userMessageForSizing?.let { messageTokenCount })
        }
        override fun generateStream(prompt: String, stream: GenStream) {
            prompts += prompt
            generatedLimits += current.maxTokens
            onGenerate()
            stream.onDelta("response")
            stream.onComplete()
        }
        override fun cancel() { cancels++; onCancel() }
        override fun shutdown() { shutdowns++ }
    }
}
