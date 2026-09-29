package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelState
import com.llamatik.library.platform.GenStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LlamatikEngineLifecycleTest {
    @Test
    fun `unload preserves executor and supports model and summary reloads`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val runtime = FakeRuntime()
        var path = "first.gguf"
        val engine = LlamatikEngine(provider { path }, GenerationParams(), executor, runtime)
        try {
            engine.ensureLoaded().getOrThrow()
            engine.unload()
            assertEquals(ModelState.Unloaded, engine.state.value)
            assertFalse(executor.isShutdown)

            path = "second.gguf"
            val summaryParams = GenerationParams(contextSize = 4096, fixedResponseTokens = 512)
            engine.updateParams(summaryParams)
            engine.ensureLoaded().getOrThrow()
            assertEquals(summaryParams, runtime.params.last())
            assertEquals(listOf("response"), engine.generate(emptyList()).toList())

            engine.unload()
            engine.updateParams(GenerationParams())
            engine.ensureLoaded().getOrThrow()
            assertEquals(GenerationParams(), runtime.params.last())
            assertEquals(listOf("first.gguf", "second.gguf", "second.gguf"), runtime.paths)
            assertEquals(ModelState.Ready, engine.state.value)
        } finally {
            engine.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `close terminates workers and repeated cleanup cannot touch the runtime`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val runtime = FakeRuntime()
        val engine = LlamatikEngine(provider { "model.gguf" }, GenerationParams(), executor, runtime)
        try {
            engine.ensureLoaded().getOrThrow()
            engine.close()
            assertTrue(executor.isShutdown)
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            engine.close()
            engine.unload()
            engine.cancelGeneration()
            engine.updateParams(GenerationParams())

            assertTrue(engine.ensureLoaded().isFailure)
            assertThrows(IllegalStateException::class.java) { engine.generate(emptyList()) }
            assertEquals(ModelState.Unloaded, engine.state.value)
            assertEquals(1, runtime.cancels)
            assertEquals(1, runtime.shutdowns)
            assertEquals(1, runtime.params.size)
            assertEquals(1, runtime.paths.size)
        } finally { engine.close() }
    }

    @Test
    fun `shutdown failure still terminates executor`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val runtime = FakeRuntime()
        val engine = LlamatikEngine(provider { "model.gguf" }, GenerationParams(), executor, runtime)
        try {
            engine.ensureLoaded().getOrThrow()
            runtime.failShutdown = true
            assertThrows(IllegalStateException::class.java) { engine.close() }
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(ModelState.Unloaded, engine.state.value)
            engine.close()
            assertEquals(1, runtime.shutdowns)
        } finally { engine.close() }
    }

    @Test
    fun `cancel failure still shuts down native runtime and executor`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val runtime = FakeRuntime()
        val engine = LlamatikEngine(provider { "model.gguf" }, GenerationParams(), executor, runtime)
        try {
            engine.ensureLoaded().getOrThrow()
            runtime.failCancel = true
            assertThrows(IllegalStateException::class.java) { engine.close() }
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(1, runtime.shutdowns)
            assertEquals(ModelState.Unloaded, engine.state.value)
        } finally { engine.close() }
    }

    @Test
    fun `in flight model lookup cannot reload native runtime after close`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val runtime = FakeRuntime()
        val lookupStarted = CountDownLatch(1)
        val allowLookup = CountDownLatch(1)
        val engine = LlamatikEngine(provider {
            lookupStarted.countDown()
            check(allowLookup.await(5, TimeUnit.SECONDS))
            "model.gguf"
        }, GenerationParams(), executor, runtime)
        val loading = async(Dispatchers.Default) { engine.ensureLoaded() }
        try {
            assertTrue(lookupStarted.await(5, TimeUnit.SECONDS))
            engine.close()
            allowLookup.countDown()
            assertTrue(loading.await().isFailure)
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(ModelState.Unloaded, engine.state.value)
            assertTrue(runtime.paths.isEmpty())
            assertTrue(runtime.params.isEmpty())
            assertEquals(1, runtime.shutdowns)
        } finally {
            allowLookup.countDown()
            engine.close()
        }
    }

    private fun provider(path: () -> String) = object : ModelProvider {
        override suspend fun getModelPath(): Result<String> = Result.success(path())
    }

    private class FakeRuntime : LlamaRuntime {
        val paths = mutableListOf<String>()
        val params = mutableListOf<GenerationParams>()
        var cancels = 0
        var shutdowns = 0
        var failCancel = false
        var failShutdown = false
        override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) {
            this.params += params
        }
        override fun load(path: String): Boolean { paths += path; return true }
        override fun inspectPrompt(path: String, prompt: String, userMessageForSizing: String?) = PromptInspection(32, 8192)
        override fun generateStream(prompt: String, stream: GenStream) {
            stream.onDelta("response")
            stream.onComplete()
        }
        override fun cancel() { cancels++; check(!failCancel) { "cancel failed" } }
        override fun shutdown() { shutdowns++; check(!failShutdown) { "shutdown failed" } }
    }
}
