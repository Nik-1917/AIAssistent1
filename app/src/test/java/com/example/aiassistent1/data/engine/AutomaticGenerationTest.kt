package com.example.aiassistent1.data.engine

import com.example.aiassistent1.data.model.GgufTestFile
import com.example.aiassistent1.data.model.ModelMemoryGuard
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.*
import com.llamatik.library.platform.GenStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutomaticGenerationTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun `1200 token output completes and subsequent low RAM does not reload`() = runBlocking {
        fixture { engine, runtime, guard ->
            assertEquals(listOf("complete"), engine.generateForTask(emptyList(), GenerationTask.CHAT).toList())
            assertEquals(1932, runtime.params.maxTokens)
            assertEquals(1200, engine.automaticState.value?.generatedTokens)
            guard.limit = DeviceContextLimit(0, MemoryLimitStatus.LOW_MEMORY)
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(1, runtime.loads)
            assertEquals(2048, engine.automaticState.value?.contextSize)
            assertEquals(ModelState.Ready, engine.state.value)
        }
    }
    @Test fun `real context limit grows and continues without replaying text`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.limitFirst = true
            assertEquals(listOf("first", "continued"), engine.generateForTask(emptyList(), GenerationTask.CHAT).toList())
            assertEquals(listOf(4096), runtime.resizes)
            assertEquals(GenerationStopReason.EOS, engine.automaticState.value?.stopReason)
            runtime.limitFirst = false
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(4096, engine.automaticState.value?.contextSize)
            assertEquals(1, runtime.loads)
        }
    }
    @Test fun `failed resize never leaves Ready with a freed native context`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.limitFirst = true
            runtime.resizeSucceeds = false
            val failure = runCatching { engine.generateForTask(emptyList(), GenerationTask.CHAT).toList() }
            assertTrue(failure.isFailure)
            assertTrue(engine.state.value is ModelState.Error)
            assertTrue(runtime.shutdowns > 0)
        }
    }
    @Test fun `rejected and unavailable estimates retain their causes without a fake 512 capacity`() = runBlocking {
        for ((status, reason) in listOf(
            MemoryLimitStatus.LOW_MEMORY to ModelAllocationFailure.LOW_MEMORY,
            MemoryLimitStatus.INSUFFICIENT_MEMORY to ModelAllocationFailure.INSUFFICIENT_MEMORY,
            MemoryLimitStatus.MEMORY_UNAVAILABLE to ModelAllocationFailure.MEMORY_UNAVAILABLE,
            MemoryLimitStatus.METADATA_UNAVAILABLE to ModelAllocationFailure.METADATA_UNAVAILABLE,
        )) for (task in GenerationTask.entries) fixture { engine, runtime, guard ->
            guard.limit = DeviceContextLimit(0, status)
            var started = false
            val early = runCatching { engine.checkModelAvailability(task) }.exceptionOrNull()
            assertTrue(early is ModelAllocationException)
            val failure = runCatching { engine.generateForTask(emptyList(), task) { started = true }.toList() }.exceptionOrNull()
            assertTrue(failure is ModelAllocationException)
            failure as ModelAllocationException
            assertEquals(reason, failure.reason)
            assertNull(failure.requiredContext) // The input was not tokenized.
            assertFalse(failure.message!!.contains("по частям"))
            assertEquals(0, runtime.loads)
            assertEquals(0, runtime.generations)
            assertEquals(0, runtime.preparations)
            assertEquals(0, runtime.tokenizations)
            assertEquals(0, runtime.begins)
            assertFalse(started)
        }
    }

    @Test fun `ready notification follows successful validation and load before decoding`() = runBlocking {
        fixture { engine, runtime, _ ->
            var starts = 0
            runtime.beforeLoad = { assertEquals(0, starts) }
            runtime.beforeGeneration = { assertEquals(1, starts) }
            engine.generateForTask(emptyList(), GenerationTask.CHAT) {
                assertEquals(ModelState.Ready, engine.state.value)
                assertTrue(engine.automaticState.value!!.availableAnswerTokens >= 1024)
                starts++
            }.toList()
            assertEquals(1, starts)
        }
    }

    @Test fun `capacity rejection never announces generation`() = runBlocking {
        fixture { engine, runtime, guard ->
            guard.limit = DeviceContextLimit(512, MemoryLimitStatus.ESTIMATED)
            var started = false
            val failure = runCatching { engine.generateForTask(emptyList(), GenerationTask.CHAT) { started = true }.toList() }.exceptionOrNull()
            assertTrue(failure is PromptCapacityException)
            assertFalse(started)
            assertEquals(0, runtime.loads)
            assertEquals(0, runtime.generations)
        }
    }

    @Test fun `cancellation in ready notification prevents decoding and preserves the allocation`() = runBlocking {
        fixture { engine, runtime, _ ->
            val failure = runCatching { engine.generateForTask(emptyList(), GenerationTask.CHAT) {
                throw kotlinx.coroutines.CancellationException("cancel before decode")
            }.toList() }.exceptionOrNull()
            assertTrue(failure is kotlinx.coroutines.CancellationException)
            assertEquals(0, runtime.generations)
            assertEquals(ModelState.Ready, engine.state.value)
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(1, runtime.loads)
        }
    }

    @Test fun `preflight preserves a proven compatible allocation despite rejected RAM`() = runBlocking {
        fixture { engine, runtime, guard ->
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            guard.limit = DeviceContextLimit(0, MemoryLimitStatus.LOW_MEMORY)
            val before = runtime.preparations
            engine.checkModelAvailability(GenerationTask.CHAT)
            assertEquals(before, runtime.preparations)
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(1, runtime.loads)
        }
    }

    @Test fun `an actual 512 RAM limit remains a capacity failure for chat without native decoding`() = runBlocking {
        fixture { engine, runtime, guard ->
            guard.limit = DeviceContextLimit(512, MemoryLimitStatus.ESTIMATED)
            val failure = runCatching { engine.generateForTask(emptyList(), GenerationTask.CHAT).toList() }.exceptionOrNull()
            assertTrue(failure is PromptCapacityException)
            failure as PromptCapacityException
            assertEquals(ContextCapacityReason.MEMORY, failure.reason)
            assertEquals(1536, failure.requiredContext)
            assertEquals(512, failure.availableContext)
            assertEquals(1024, failure.minimumAnswerTokens)
            assertEquals(0, runtime.loads)
        }
    }

    @Test fun `long calendar system input gets a fitting initial context`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.tokens = 1200
            val system = "Правила календаря ".repeat(120)
            engine.generateForTask(listOf(ChatMessage(role = MessageRole.SYSTEM, content = system),
                ChatMessage(role = MessageRole.USER, content = "Создай встречу завтра")), GenerationTask.CALENDAR).toList()
            assertEquals(1536, runtime.params.contextSize)
            assertEquals(64, runtime.params.batchSize)
            assertTrue(runtime.lastPrompt.contains(system))
            assertTrue(runtime.params.maxTokens >= 64)
        }
    }
    @Test fun `failed context allocation retries smallest fitting context once`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.failFirstLoad = true
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(2, runtime.loads)
            assertEquals(1536, runtime.params.contextSize)
            assertTrue(runtime.params.maxTokens >= 1024)
            assertEquals(64, runtime.params.batchSize)
        }
    }
    @Test fun `JNI load exception also retries a fitting context`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.throwFirstLoad = true
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(2, runtime.loads)
            assertEquals(1536, runtime.params.contextSize)
            assertTrue(runtime.params.maxTokens >= 1024)
        }
    }

    @Test fun `smaller batch is checked even when rejected estimate already names the required context`() = runBlocking {
        fixture { engine, runtime, guard ->
            runtime.tokens = 1200
            guard.limit = DeviceContextLimit(0, MemoryLimitStatus.INSUFFICIENT_MEMORY)
            guard.limitsByBatch = mapOf(64 to DeviceContextLimit(4096, MemoryLimitStatus.ESTIMATED))
            engine.generateForTask(emptyList(), GenerationTask.SUMMARY).toList()
            assertEquals(2560, runtime.params.contextSize)
            assertEquals(64, runtime.params.batchSize)
            assertTrue(runtime.params.maxTokens >= 1024)
        }
    }

    @Test fun `native failure at the smallest configuration keeps its cause without retrying the same allocation`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.tokens = 1200
            runtime.throwFirstLoad = true
            val failure = runCatching { engine.generateForTask(emptyList(), GenerationTask.CALENDAR).toList() }.exceptionOrNull()
            assertTrue(failure is ModelAllocationException)
            assertEquals(ModelAllocationFailure.NATIVE_ALLOCATION_FAILED, (failure as ModelAllocationException).reason)
            assertEquals("Context allocation failed", failure.cause?.message)
            assertEquals(1, runtime.loads)
            assertEquals(0, runtime.generations)
            assertEquals(ModelState.Unloaded, engine.state.value)
        }
    }

    @Test fun `fresh memory rejection stops the retry after a failed larger native load`() = runBlocking {
        fixture { engine, runtime, guard ->
            runtime.failFirstLoad = true
            runtime.beforeLoad = { guard.limit = DeviceContextLimit(0, MemoryLimitStatus.LOW_MEMORY) }
            val failure = runCatching { engine.generateForTask(emptyList(), GenerationTask.CHAT).toList() }.exceptionOrNull()
            assertTrue(failure is ModelAllocationException)
            assertEquals(ModelAllocationFailure.LOW_MEMORY, (failure as ModelAllocationException).reason)
            assertEquals(1, runtime.loads)
            assertEquals(ModelState.Unloaded, engine.state.value)
        }
    }

    @Test fun `summary budget preserves memory assessment failure instead of returning zero`() = runBlocking {
        fixture { engine, _, guard ->
            guard.limit = DeviceContextLimit(0, MemoryLimitStatus.METADATA_UNAVAILABLE)
            val failure = runCatching { engine.promptTokenBudget(GenerationTask.SUMMARY) }.exceptionOrNull()
            assertTrue(failure is ModelAllocationException)
            assertEquals(ModelAllocationFailure.METADATA_UNAVAILABLE, (failure as ModelAllocationException).reason)
        }
    }
    @Test fun `summary allocation does not replace remembered chat context`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.tokens = 600
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(4096, runtime.params.contextSize)
            runtime.tokens = 100
            engine.generateForTask(emptyList(), GenerationTask.CALENDAR).toList()
            assertEquals(512, runtime.params.contextSize)
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(4096, runtime.params.contextSize)
        }
    }
    @Test fun `no room to grow preserves partial response as an error and next request still works`() = runBlocking {
        fixture { engine, runtime, guard ->
            guard.limit = DeviceContextLimit(2048, MemoryLimitStatus.ESTIMATED)
            runtime.limitFirst = true
            val received = mutableListOf<String>()
            val error = runCatching { engine.generateForTask(emptyList(), GenerationTask.CHAT).toList(received) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message!!.contains("не завершена"))
            assertEquals(listOf("first"), received)
            assertTrue(runtime.resizes.isEmpty())
            runtime.limitFirst = false
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(1, runtime.loads)
        }
    }
    @Test fun `cancellation reaches native work and does not prevent the next request`() = runBlocking {
        fixture { engine, runtime, _ ->
            val started = CountDownLatch(1)
            val released = CountDownLatch(1)
            runtime.beforeGeneration = { started.countDown(); check(released.await(5, TimeUnit.SECONDS)) }
            runtime.onCancel = { released.countDown() }
            val job = launch(Dispatchers.Default) { engine.generateForTask(emptyList(), GenerationTask.CHAT).toList() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            runtime.beforeGeneration = {}
            assertEquals(listOf("complete"), engine.generateForTask(emptyList(), GenerationTask.CHAT).toList())
            assertEquals(1, runtime.loads)
        }
    }
    @Test fun `task switch under pressure uses an existing allocation when the new minimum cannot fit input`() = runBlocking {
        fixture { engine, runtime, guard ->
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            guard.limit = DeviceContextLimit(0, MemoryLimitStatus.LOW_MEMORY)
            runtime.tokens = 600
            engine.generateForTask(emptyList(), GenerationTask.CALENDAR).toList()
            assertEquals(2048, runtime.params.contextSize)
            assertEquals(1, runtime.loads)
        }
    }
    @Test fun `stop signal does not wait for the model loading lock`() = runBlocking {
        fixture { engine, runtime, _ ->
            val started = CountDownLatch(1)
            val released = CountDownLatch(1)
            runtime.beforeLoad = { started.countDown(); check(released.await(5, TimeUnit.SECONDS)) }
            runtime.onCancel = { released.countDown() }
            val job = launch(Dispatchers.Default) { engine.generateForTask(emptyList(), GenerationTask.CHAT).toList() }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val before = System.nanoTime()
                engine.cancelGeneration()
                assertTrue("Stop waited for the native loading lock", System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1))
            } finally {
                released.countDown()
                job.cancelAndJoin()
            }
        }
    }

    @Test fun `1023 is blocked and 1024 and 1025 are accepted before chat or summary decoding`() = runBlocking {
        for (task in listOf(GenerationTask.CHAT, GenerationTask.SUMMARY)) {
            for (remaining in listOf(1023, 1024, 1025)) fixture { engine, runtime, guard ->
                guard.limit = DeviceContextLimit(2048, MemoryLimitStatus.ESTIMATED)
                runtime.tokens = 2048 - 16 - remaining
                val failure = runCatching { engine.generateForTask(emptyList(), task).toList() }.exceptionOrNull()
                if (remaining < 1024) {
                    assertTrue(failure is PromptCapacityException)
                    assertEquals(1008, (failure as PromptCapacityException).promptBudget)
                    assertEquals(0, runtime.generations)
                } else {
                    assertNull(failure)
                    assertEquals(remaining, runtime.params.maxTokens)
                }
            }
        }
    }

    @Test fun `growing history expands chat before decoding when memory permits`() = runBlocking {
        fixture { engine, runtime, _ ->
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            runtime.tokens = 1009
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(4096, runtime.params.contextSize)
            assertEquals(3071, runtime.params.maxTokens)
            assertEquals(2, runtime.generations)
        }
    }

    @Test fun `existing allocation under pressure cannot bypass the chat answer reserve`() = runBlocking {
        fixture { engine, runtime, guard ->
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            guard.limit = DeviceContextLimit(0, MemoryLimitStatus.LOW_MEMORY)
            runtime.tokens = 1009
            val failure = runCatching { engine.generateForTask(emptyList(), GenerationTask.CHAT).toList() }.exceptionOrNull()
            assertTrue(failure is PromptCapacityException)
            assertEquals(1008, (failure as PromptCapacityException).promptBudget)
            assertEquals(ContextCapacityReason.MEMORY, failure.reason)
            assertEquals(1, runtime.generations)
            assertEquals(1, runtime.loads)
            runtime.tokens = 100
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(1, runtime.loads)
            assertEquals(1008, engine.promptTokenBudget(GenerationTask.SUMMARY))
        }
    }

    @Test fun `summary chunk budget reserves 1024 with the technical margin`() = runBlocking {
        fixture { engine, _, guard ->
            guard.limit = DeviceContextLimit(2048, MemoryLimitStatus.ESTIMATED)
            assertEquals(1008, engine.promptTokenBudget(GenerationTask.SUMMARY))
            assertEquals(1520, engine.promptTokenBudget(GenerationTask.CALENDAR))
        }
    }

    @Test fun `GGUF limit is reported separately from memory pressure`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.tokens = 7153
            val failure = runCatching { engine.generateForTask(emptyList(), GenerationTask.CHAT).toList() }.exceptionOrNull()
            assertTrue(failure is PromptCapacityException)
            assertEquals(ContextCapacityReason.MODEL_LIMIT, (failure as PromptCapacityException).reason)
            assertEquals(7152, failure.promptBudget)
            assertEquals(0, runtime.generations)
        }
    }

    @Test fun `failed native allocations release stale configuration without inventing a smaller usable budget`() = runBlocking {
        fixture { engine, runtime, _ ->
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            runtime.failAllLoads = true
            runtime.tokens = 600
            val failure = runCatching { engine.generateForTask(emptyList(), GenerationTask.CHAT).toList() }.exceptionOrNull()
            assertTrue(failure is ModelAllocationException)
            assertEquals(ModelAllocationFailure.NATIVE_ALLOCATION_FAILED, (failure as ModelAllocationException).reason)
            assertEquals(2048, failure.requiredContext)
            assertEquals(ModelState.Unloaded, engine.state.value)
            assertEquals(1, runtime.generations)
        }
    }

    private suspend fun fixture(block: suspend (LlamatikEngine, Runtime, Guard) -> Unit) {
        val file = GgufTestFile().architecture().context(8192).memory().write(folder.newFile())
        val provider = object : ModelProvider { override suspend fun getModelPath() = Result.success(file.path) }
        val executor = Executors.newSingleThreadExecutor()
        val runtime = Runtime()
        val guard = Guard()
        val engine = LlamatikEngine(provider, GenerationParams(), executor, runtime, memoryGuard = guard)
        try { block(engine, runtime, guard) } finally {
            engine.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
    private class Guard : ModelMemoryGuard {
        override val changes = MutableStateFlow(0L)
        var limit = DeviceContextLimit(8192, MemoryLimitStatus.ESTIMATED)
        var limitsByBatch = emptyMap<Int, DeviceContextLimit>()
        override fun assess(file: File, params: GenerationParams) = limitsByBatch[params.batchSize] ?: limit
        override fun beforeLoad(): DeviceMemorySnapshot? = null
        override fun loaded(file: File, params: GenerationParams, before: DeviceMemorySnapshot?) = Unit
        override fun unloaded() = Unit
        override fun refresh() = Unit
    }
    private class Runtime : LlamaRuntime {
        var params = GenerationParams()
        var tokens = 100
        var loads = 0
        var generations = 0
        var preparations = 0
        var tokenizations = 0
        var begins = 0
        var shutdowns = 0
        var limitFirst = false
        var resizeSucceeds = true
        var failFirstLoad = false
        var throwFirstLoad = false
        var failAllLoads = false
        var beforeGeneration: () -> Unit = {}
        var beforeLoad: () -> Unit = {}
        var onCancel: () -> Unit = {}
        var lastPrompt = ""
        val resizes = mutableListOf<Int>()
        override var lastGeneration: GenerationResult? = null
        override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) { this.params = params }
        override fun load(path: String): Boolean {
            loads++
            beforeLoad()
            if (throwFirstLoad && loads == 1) throw IllegalStateException("Context allocation failed")
            return !failAllLoads && (!failFirstLoad || loads > 1)
        }
        override fun beginRequest() { begins++ }
        override fun prepareTokenizer(path: String) { preparations++ }
        override fun countTokens(prompt: String): Int { tokenizations++; return tokens }
        override fun generateStream(prompt: String, stream: GenStream) {
            lastPrompt = prompt
            generations++
            beforeGeneration()
            stream.onDelta(if (limitFirst) "first" else "complete")
            lastGeneration = GenerationResult(if (limitFirst) GenerationStopReason.CONTEXT_LIMIT else GenerationStopReason.EOS,
                tokens, if (limitFirst) params.contextSize - tokens - 16 else 1200, params.contextSize)
            stream.onComplete()
        }
        override fun resizeContext(context: Int, batch: Int, threads: Int): Boolean { resizes += context; return resizeSucceeds }
        override fun continueStream(stream: GenStream) {
            stream.onDelta("continued")
            lastGeneration = requireNotNull(lastGeneration).copy(reason = GenerationStopReason.EOS, generatedTokens = 2300)
            stream.onComplete()
        }
        override fun cancel() { onCancel() }
        override fun shutdown() { shutdowns++ }
    }
}
