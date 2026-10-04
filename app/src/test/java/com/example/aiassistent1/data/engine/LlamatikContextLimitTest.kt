package com.example.aiassistent1.data.engine

import com.example.aiassistent1.data.model.GgufTestFile
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelProfile
import com.llamatik.library.platform.GenStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LlamatikContextLimitTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `native context is bounded from GGUF before load even when caller limit is stale`() = runBlocking {
        val file = GgufTestFile().architecture().context(4096).write(temporaryFolder.newFile())
        val provider = object : ModelProvider { override suspend fun getModelPath() = Result.success(file.path) }
        val requested = ModelProfile.CHAT.defaults.withContextResponseRatio(com.example.aiassistent1.domain.model.ContextResponseRatio.TWO_TO_ONE).copy(contextSize = 32768, maxTokens = 16384, trainedContextLength = 131072)
        val executor = Executors.newSingleThreadExecutor()
        val runtime = RecordingRuntime()
        val engine = LlamatikEngine(provider, requested, executor, runtime)
        try {
            engine.ensureLoaded().getOrThrow()
            val native = runtime.loads.single()
            assertEquals(4096, native.contextSize)
            assertEquals(2048, native.maxTokens)
            assertEquals(4096, native.trainedContextLength)
            engine.ensureLoaded().getOrThrow()
            assertEquals(1, runtime.loads.size)
            engine.updateParams(requested.copy(temperature = 0.8f))
            assertEquals(4096, runtime.updates.last().contextSize)
            assertEquals(0.8f, runtime.updates.last().temperature)
            assertEquals(1, runtime.loads.size)
        } finally {
            engine.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `switching files refreshes ceiling and bounds summary independently of user profile`() = runBlocking {
        val large = GgufTestFile().architecture().context(32768).write(temporaryFolder.newFile())
        val small = GgufTestFile().architecture().context(1024).write(temporaryFolder.newFile())
        var selected = large
        val provider = object : ModelProvider { override suspend fun getModelPath() = Result.success(selected.path) }
        val requested = ModelProfile.CHAT.defaults.withContextResponseRatio(com.example.aiassistent1.domain.model.ContextResponseRatio.TWO_TO_ONE).copy(contextSize = 16384, maxTokens = 8192)
        val executor = Executors.newSingleThreadExecutor()
        val runtime = RecordingRuntime()
        val engine = LlamatikEngine(provider, requested, executor, runtime)
        try {
            engine.ensureLoaded().getOrThrow()
            assertEquals(16384, runtime.loads.last().contextSize)
            engine.unload()
            selected = small
            engine.updateParams(requested.copy(contextSize = 4096, maxTokens = 512))
            engine.ensureLoaded().getOrThrow()
            assertEquals(1024, runtime.loads.last().contextSize)
            assertEquals(512, runtime.loads.last().maxTokens)
            engine.unload()
            selected = large
            engine.updateParams(requested)
            engine.ensureLoaded().getOrThrow()
            assertEquals(16384, runtime.loads.last().contextSize)
        } finally {
            engine.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `unknown file metadata uses fallback instead of a caller supplied ceiling`() = runBlocking {
        val file = GgufTestFile().architecture().write(temporaryFolder.newFile())
        val provider = object : ModelProvider { override suspend fun getModelPath() = Result.success(file.path) }
        val executor = Executors.newSingleThreadExecutor()
        val runtime = RecordingRuntime()
        val engine = LlamatikEngine(provider, GenerationParams(contextSize = 32768, maxTokens = 16384,
            trainedContextLength = 32768), executor, runtime)
        try {
            engine.ensureLoaded().getOrThrow()
            assertEquals(8192, runtime.loads.single().contextSize)
            assertNull(runtime.loads.single().trainedContextLength)
        } finally {
            engine.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private class RecordingRuntime : LlamaRuntime {
        val updates = mutableListOf<GenerationParams>()
        val loads = mutableListOf<GenerationParams>()
        override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) { updates += params }
        override fun load(path: String): Boolean { loads += updates.last(); return true }
        override fun generateStream(prompt: String, stream: GenStream) = error("No inference in this JVM test")
        override fun cancel() = Unit
        override fun shutdown() = Unit
    }
}
