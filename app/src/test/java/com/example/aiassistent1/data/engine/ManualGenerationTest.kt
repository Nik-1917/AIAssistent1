package com.example.aiassistent1.data.engine

import com.example.aiassistent1.data.model.GgufTestFile
import com.example.aiassistent1.data.model.ModelMemoryGuard
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.*
import com.llamatik.library.platform.GenStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManualGenerationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `manual values remain fixed and statistics refresh for each request`() = runBlocking {
        fixture { engine, runtime, _ ->
            assertEquals(752, engine.promptTokenBudget(GenerationTask.SUMMARY))
            assertEquals(listOf("answer"), engine.generateForTask(emptyList(), GenerationTask.CHAT).toList())
            assertEquals(1024, runtime.loads.single())
            assertEquals(256, runtime.params.maxTokens)
            assertEquals(64, runtime.batch)
            assertEquals(100, engine.automaticState.value?.promptTokens)
            assertEquals(40, engine.automaticState.value?.generatedTokens)
            assertEquals(false, engine.automaticState.value?.automaticMemory)
            runtime.tokens = 200
            engine.generateForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(listOf(1024), runtime.loads)
            assertEquals(200, engine.automaticState.value?.promptTokens)
            assertEquals(1024, engine.automaticState.value?.contextSize)
            assertEquals(256, runtime.params.maxTokens)
        }
    }

    @Test fun `manual prompt budget rejects overflow without loading or notifying ready`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.tokens = 753
            var ready = false
            val failure = runCatching {
                engine.generateForTask(emptyList(), GenerationTask.CHAT) { ready = true }.toList()
            }.exceptionOrNull()
            assertTrue(failure is PromptCapacityException)
            assertEquals(ContextCapacityReason.MANUAL_LIMIT, (failure as PromptCapacityException).reason)
            assertEquals(752, failure.promptBudget)
            assertFalse(ready)
            assertTrue(runtime.loads.isEmpty())
            assertEquals(753, engine.automaticState.value?.promptTokens)
            assertEquals(true, engine.automaticState.value?.requestRejected)
        }
    }

    @Test fun `manual output limit reports incomplete answer and never expands context`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.reason = GenerationStopReason.OUTPUT_LIMIT
            val received = mutableListOf<String>()
            val failure = runCatching {
                engine.generateForTask(emptyList(), GenerationTask.CALENDAR).collect { received += it }
            }.exceptionOrNull()
            assertEquals(listOf("answer"), received)
            assertTrue(failure?.message?.contains("лимита 256 токенов") == true)
            assertEquals(GenerationStopReason.OUTPUT_LIMIT, engine.automaticState.value?.stopReason)
            assertEquals(256, engine.automaticState.value?.generatedTokens)
            assertEquals(ModelState.Ready, engine.state.value)
            assertEquals(0, runtime.resizes)
        }
    }

    @Test fun `manual RAM refusal does not silently shrink the chosen context`() = runBlocking {
        fixture { engine, runtime, guard ->
            guard.limit = DeviceContextLimit(512, MemoryLimitStatus.ESTIMATED)
            val failure = runCatching { engine.checkModelAvailability(GenerationTask.CHAT) }.exceptionOrNull()
            assertTrue(failure?.message?.contains("вручную выбрано 1024") == true)
            assertTrue(runtime.loads.isEmpty())
        }
    }

    private suspend fun fixture(block: suspend (LlamatikEngine, Runtime, Guard) -> Unit) {
        val file = GgufTestFile().architecture().context(8192).memory().write(folder.newFile())
        val provider = object : ModelProvider { override suspend fun getModelPath() = Result.success(file.path) }
        val executor = Executors.newSingleThreadExecutor()
        val runtime = Runtime()
        val guard = Guard()
        val engine = LlamatikEngine(provider, ModelProfile.CHAT.defaults.withAutomaticMemory(false),
            executor, runtime, memoryGuard = guard)
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
        var batch = 0
        var reason = GenerationStopReason.EOS
        val loads = mutableListOf<Int>()
        var resizes = 0
        override var lastGeneration: GenerationResult? = null
        override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) {
            this.params = params
            batch = batchSize
        }
        override fun load(path: String): Boolean { loads += params.contextSize; return true }
        override fun generateStream(prompt: String, stream: GenStream) {
            stream.onDelta("answer")
            lastGeneration = GenerationResult(reason, tokens,
                if (reason == GenerationStopReason.OUTPUT_LIMIT) params.maxTokens else 40, params.contextSize)
            stream.onComplete()
        }
        override fun countTokens(prompt: String) = tokens
        override fun resizeContext(context: Int, batch: Int, threads: Int): Boolean { resizes++; return true }
        override fun cancel() = Unit
        override fun shutdown() = Unit
    }
}
