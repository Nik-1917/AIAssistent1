package com.example.aiassistent1.data.engine

import com.example.aiassistent1.data.model.GgufTestFile
import com.example.aiassistent1.data.model.ModelMemoryGuard
import com.example.aiassistent1.data.model.DeviceModelMemoryGuard
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.*
import com.llamatik.library.platform.GenStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LlamatikMemoryLimitTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `real guard accepts qwen35 and limits future architecture before native loading`() = runBlocking {
        for (architecture in listOf("qwen35", "future-text")) {
            val data = GgufTestFile().architecture(architecture).context(262144, architecture)
            if (architecture == "qwen35") data.qwen35Memory() else data.memory(architecture)
            val file = data.write(directory.newFile())
            val provider = object : ModelProvider { override suspend fun getModelPath() = Result.success(file.path) }
            var memory = DeviceMemorySnapshot(8L shl 30, 6L shl 30, 256L shl 20, false)
            val guard = DeviceModelMemoryGuard { memory }
            val runtime = RecordingRuntime()
            val executor = Executors.newSingleThreadExecutor()
            val engine = LlamatikEngine(provider, GenerationParams(contextSize = 32768, maxTokens = 16384),
                executor, runtime, memoryGuard = guard)
            try {
                engine.ensureLoaded().getOrThrow()
                assertEquals(ModelState.Ready, engine.state.value)
                val loaded = runtime.loads.single()
                assertTrue(requireNotNull(loaded.deviceContextLimit).canLoad)
                assertEquals(if (architecture == "qwen35") MemoryEstimateProfile.QWEN35 else MemoryEstimateProfile.CONSERVATIVE_FALLBACK,
                    loaded.deviceContextLimit?.estimateProfile)
                if (architecture == "future-text") assertEquals(2048, loaded.contextSize)
                memory = memory.copy(lowMemory = true)
                assertTrue(engine.ensureLoaded().isSuccess)
                assertEquals(1, runtime.loads.size)
            } finally {
                engine.close()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `fresh device limit overrides caller metadata and intersects GGUF with quarter answer`() = runBlocking {
        val fixture = fixture(context = 8192, requested = 32768)
        try {
            fixture.engine.ensureLoaded().getOrThrow()
            val loaded = fixture.runtime.loads.single()
            assertEquals(4096, loaded.contextSize)
            assertEquals(1024, loaded.maxTokens)
            assertEquals(8192, loaded.trainedContextLength)
            assertEquals(fixture.guard.limit, loaded.deviceContextLimit)
            fixture.guard.limit = DeviceContextLimit(32768, MemoryLimitStatus.ESTIMATED)
            fixture.engine.ensureLoaded().getOrThrow()
            assertEquals(4096, fixture.runtime.loads.last().contextSize)
            assertEquals(1024, fixture.runtime.loads.last().maxTokens)
            assertEquals(1, fixture.runtime.loads.size)
        } finally { fixture.close() }
    }

    @Test fun `loaded model retains its working allocation when free RAM falls`() = runBlocking {
        val fixture = fixture()
        try {
            fixture.engine.ensureLoaded().getOrThrow()
            fixture.engine.ensureLoaded().getOrThrow()
            assertEquals(1, fixture.runtime.loads.size)
            fixture.guard.limit = DeviceContextLimit(2048, MemoryLimitStatus.ESTIMATED)
            fixture.engine.ensureLoaded().getOrThrow()
            assertEquals(4096, fixture.runtime.loads.last().contextSize)
            assertEquals(1024, fixture.runtime.loads.last().maxTokens)
            fixture.guard.limit = DeviceContextLimit(0, MemoryLimitStatus.LOW_MEMORY)
            assertTrue(fixture.engine.ensureLoaded().isSuccess)
            assertEquals(ModelState.Ready, fixture.engine.state.value)
            assertTrue(fixture.guard.isLoaded)
            assertEquals(1, fixture.runtime.loads.size)
            fixture.guard.limit = DeviceContextLimit(4096, MemoryLimitStatus.ESTIMATED)
            fixture.engine.ensureLoaded().getOrThrow()
            assertEquals(4096, fixture.runtime.loads.last().contextSize)
        } finally { fixture.close() }
    }

    @Test fun `blocked first load and failed allocation leave no memory credit and can retry`() = runBlocking {
        val fixture = fixture()
        try {
            fixture.guard.limit = DeviceContextLimit(0, MemoryLimitStatus.INSUFFICIENT_MEMORY)
            assertTrue(fixture.engine.ensureLoaded().isFailure)
            assertTrue(fixture.runtime.loads.isEmpty())
            assertFalse(fixture.guard.isLoaded)
            fixture.guard.limit = DeviceContextLimit(4096, MemoryLimitStatus.ESTIMATED)
            fixture.runtime.succeeds = false
            assertTrue(fixture.engine.ensureLoaded().isFailure)
            assertFalse(fixture.guard.isLoaded)
            fixture.runtime.succeeds = true
            fixture.engine.ensureLoaded().getOrThrow()
            assertTrue(fixture.guard.isLoaded)
        } finally { fixture.close() }
        assertFalse(fixture.guard.isLoaded)
    }

    @Test fun `ratio change waits until streaming has finished and automatic batch is reloaded`() = runBlocking {
        val fixture = fixture(requested = 512)
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        fixture.runtime.duringGeneration = { started.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
        try {
            fixture.engine.updateParams(GenerationParams())
            fixture.engine.ensureLoaded().getOrThrow()
            assertEquals(256, fixture.runtime.batches.last())
            val response = async(Dispatchers.Default) { fixture.engine.generate(emptyList()).toList() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            fixture.engine.updateParams(GenerationParams().withContextResponseRatio(ContextResponseRatio.FOUR_TO_ONE))
            assertEquals(256, fixture.runtime.updates.last().maxTokens)
            assertEquals(1, fixture.runtime.loads.size)
            finish.countDown()
            assertEquals(listOf("response"), response.await())
            fixture.engine.ensureLoaded().getOrThrow()
            assertEquals(2, fixture.runtime.loads.size)
            assertEquals(512, fixture.runtime.loads.last().contextSize)
            assertEquals(128, fixture.runtime.loads.last().maxTokens)
            assertEquals(384, fixture.runtime.batches.last())
        } finally { finish.countDown(); fixture.close() }
    }

    private fun fixture(context: Long = 32768, requested: Int = 8192): Fixture {
        val file = GgufTestFile().architecture().context(context).memory().write(directory.newFile())
        val provider = object : ModelProvider { override suspend fun getModelPath() = Result.success(file.path) }
        val guard = Guard()
        val runtime = RecordingRuntime()
        val executor = Executors.newSingleThreadExecutor()
        val params = GenerationParams(contextSize = requested, maxTokens = requested / 4,
            contextResponseRatio = ContextResponseRatio.FOUR_TO_ONE, trainedContextLength = 131072,
            deviceContextLimit = DeviceContextLimit(131072, MemoryLimitStatus.ESTIMATED))
        val engine = LlamatikEngine(provider, params, executor, runtime, memoryGuard = guard)
        return Fixture(engine, runtime, guard) {
            engine.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private class Fixture(val engine: LlamatikEngine, val runtime: RecordingRuntime, val guard: Guard, val close: () -> Unit)

    private class Guard : ModelMemoryGuard {
        override val changes = MutableStateFlow(0L)
        var limit = DeviceContextLimit(4096, MemoryLimitStatus.ESTIMATED)
        var isLoaded = false
        override fun assess(file: File, params: GenerationParams) = limit
        override fun beforeLoad() = DeviceMemorySnapshot(8L shl 30, 6L shl 30, 256L shl 20, false)
        override fun loaded(file: File, params: GenerationParams, before: DeviceMemorySnapshot?) { isLoaded = true }
        override fun unloaded() { isLoaded = false }
        override fun refresh() = Unit
    }

    private class RecordingRuntime : LlamaRuntime {
        val loads = CopyOnWriteArrayList<GenerationParams>()
        val updates = CopyOnWriteArrayList<GenerationParams>()
        val batches = CopyOnWriteArrayList<Int>()
        var succeeds = true
        var duringGeneration: () -> Unit = {}
        override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) { updates += params; batches += batchSize }
        override fun load(path: String): Boolean { loads += updates.last(); return succeeds }
        override fun generateStream(prompt: String, stream: GenStream) {
            duringGeneration()
            stream.onDelta("response")
            stream.onComplete()
        }
        override fun cancel() = Unit
        override fun shutdown() = Unit
    }
}
