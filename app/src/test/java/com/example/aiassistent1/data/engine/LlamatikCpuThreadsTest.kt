package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.GenerationParams
import com.llamatik.library.platform.GenStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LlamatikCpuThreadsTest {
    @Test
    fun `changed CPU count is bounded and reaches native context creation on the next request`() = runBlocking {
        val runtime = RecordingRuntime()
        val executor = Executors.newSingleThreadExecutor()
        val initial = GenerationParams()
        val engine = LlamatikEngine(provider, initial, executor, runtime, processorCount = 8)
        try {
            engine.ensureLoaded().getOrThrow()
            assertEquals(listOf(4), runtime.loadedThreads)
            val manual = initial.copy(cpuThreadsAuto = false, cpuThreads = 2)
            engine.updateParams(manual)
            assertEquals(listOf(4), runtime.appliedThreads)
            assertFalse(runtime.events.contains("shutdown"))
            engine.ensureLoaded().getOrThrow()
            assertEquals(listOf(4, 2), runtime.loadedThreads)
            assertEquals(1, runtime.events.count { it == "shutdown" })

            engine.updateParams(manual.copy(cpuThreads = Int.MAX_VALUE))
            engine.ensureLoaded().getOrThrow()
            engine.ensureLoaded().getOrThrow()
            assertEquals(listOf(4, 2, 8), runtime.loadedThreads)
            assertEquals(2, runtime.events.count { it == "shutdown" })
        } finally {
            engine.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `mode changes and remembered values with the same effective CPU count avoid reload`() = runBlocking {
        val runtime = RecordingRuntime()
        val executor = Executors.newSingleThreadExecutor()
        val initial = GenerationParams()
        val engine = LlamatikEngine(provider, initial, executor, runtime, processorCount = 8)
        try {
            engine.ensureLoaded().getOrThrow()
            engine.updateParams(initial.copy(cpuThreadsAuto = false, cpuThreads = 4))
            engine.ensureLoaded().getOrThrow()
            val autoWithRememberedManualValue = initial.copy(cpuThreadsAuto = true, cpuThreads = 7)
            engine.updateParams(autoWithRememberedManualValue)
            engine.ensureLoaded().getOrThrow()
            engine.updateParams(autoWithRememberedManualValue)
            engine.ensureLoaded().getOrThrow()
            assertEquals(listOf(4), runtime.loadedThreads)
            assertEquals(listOf(4, 4, 4), runtime.appliedThreads)
            assertFalse(runtime.events.contains("shutdown"))
        } finally {
            engine.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `CPU edit cannot change the active generation or reload before its cleanup`() = runBlocking {
        val runtime = RecordingRuntime()
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        runtime.duringGeneration = {
            started.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
        }
        val engine = LlamatikEngine(provider, GenerationParams(), executor, runtime, processorCount = 8)
        try {
            engine.ensureLoaded().getOrThrow()
            val response = async(Dispatchers.Default) { engine.generate(emptyList()).toList() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            engine.updateParams(GenerationParams(cpuThreadsAuto = false, cpuThreads = 1))
            val reload = async(start = CoroutineStart.UNDISPATCHED) { engine.ensureLoaded() }
            assertFalse(reload.isCompleted)
            assertEquals(listOf(4), runtime.appliedThreads)
            assertFalse(runtime.events.contains("shutdown"))
            finish.countDown()
            assertEquals(listOf("response"), response.await())
            reload.await().getOrThrow()
            assertEquals(listOf(4, 1), runtime.loadedThreads)
            assertTrue(runtime.events.indexOf("generation finished") < runtime.events.indexOf("shutdown"))
            assertTrue(runtime.events.indexOf("cancel") < runtime.events.indexOf("shutdown"))
        } finally {
            finish.countDown()
            engine.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private val provider = object : ModelProvider {
        override suspend fun getModelPath() = Result.success("assistant.gguf")
    }

    private class RecordingRuntime : LlamaRuntime {
        val appliedThreads = CopyOnWriteArrayList<Int>()
        val loadedThreads = CopyOnWriteArrayList<Int>()
        val events = CopyOnWriteArrayList<String>()
        var duringGeneration: () -> Unit = {}
        override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) {
            appliedThreads += threads
            events += "params"
        }
        override fun load(path: String): Boolean {
            loadedThreads += appliedThreads.last()
            events += "load"
            return true
        }
        override fun generateStream(prompt: String, stream: GenStream) {
            duringGeneration()
            stream.onDelta("response")
            stream.onComplete()
            events += "generation finished"
        }
        override fun cancel() { events += "cancel" }
        override fun shutdown() { events += "shutdown" }
    }
}
