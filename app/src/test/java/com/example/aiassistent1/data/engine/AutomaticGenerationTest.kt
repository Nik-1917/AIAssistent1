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
    @Test fun `unavailable estimate attempts minimum and oversized input never reaches decoder`() = runBlocking {
        fixture { engine, runtime, guard ->
            guard.limit = DeviceContextLimit(0, MemoryLimitStatus.MEMORY_UNAVAILABLE)
            engine.generateForTask(emptyList(), GenerationTask.CALENDAR).toList()
            assertEquals(512, runtime.params.contextSize)
            assertEquals(64, runtime.params.batchSize)
            runtime.tokens = 2000
            val failure = runCatching { engine.generateForTask(emptyList(), GenerationTask.CHAT).toList() }.exceptionOrNull()
            assertTrue(failure is PromptCapacityException)
            assertEquals(1, runtime.generations)
        }
    }
    @Test fun `failed context allocation retries smallest fitting context once`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.failFirstLoad = true
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(2, runtime.loads)
            assertEquals(512, runtime.params.contextSize)
            assertEquals(64, runtime.params.batchSize)
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
        override fun assess(file: File, params: GenerationParams) = limit
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
        var shutdowns = 0
        var limitFirst = false
        var resizeSucceeds = true
        var failFirstLoad = false
        var beforeGeneration: () -> Unit = {}
        var beforeLoad: () -> Unit = {}
        var onCancel: () -> Unit = {}
        val resizes = mutableListOf<Int>()
        override var lastGeneration: GenerationResult? = null
        override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) { this.params = params }
        override fun load(path: String): Boolean { loads++; beforeLoad(); return !failFirstLoad || loads > 1 }
        override fun countTokens(prompt: String) = tokens
        override fun generateStream(prompt: String, stream: GenStream) {
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
