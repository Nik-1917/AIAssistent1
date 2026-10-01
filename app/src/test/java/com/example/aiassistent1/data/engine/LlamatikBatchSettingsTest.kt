package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelProfile
import com.example.aiassistent1.domain.model.ModelState
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

class LlamatikBatchSettingsTest {
    @Test
    fun `profile and batch changes reload only before the next request`() = runBlocking {
        val runtime = RecordingRuntime()
        val executor = Executors.newSingleThreadExecutor()
        val engine = LlamatikEngine(provider, ModelProfile.CALENDAR.defaults, executor, runtime)
        try {
            engine.ensureLoaded().getOrThrow()
            assertEquals(listOf(256), runtime.batches)
            engine.updateParams(ModelProfile.CHAT.defaults)
            assertEquals(1, runtime.loadedParams.size)
            assertFalse(runtime.events.contains("shutdown"))
            engine.ensureLoaded().getOrThrow()
            assertEquals(ModelProfile.CHAT.defaults, runtime.loadedParams.last())
            assertEquals(listOf(256, 512), runtime.batches)
            assertEquals(1, runtime.events.count { it == "shutdown" })

            val manual = ModelProfile.CHAT.defaults.copy(batchSizeAuto = false, batchSize = 128)
            engine.updateParams(manual)
            assertEquals(2, runtime.loadedParams.size)
            engine.ensureLoaded().getOrThrow()
            engine.ensureLoaded().getOrThrow()
            assertEquals(3, runtime.loadedParams.size)
            assertEquals(manual, runtime.loadedParams.last())
            assertEquals(128, runtime.batches.last())
        } finally {
            engine.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `sampling and auto mode changes with the same effective batch avoid reload`() = runBlocking {
        val runtime = RecordingRuntime()
        val executor = Executors.newSingleThreadExecutor()
        val initial = ModelProfile.CALENDAR.defaults
        val engine = LlamatikEngine(provider, initial, executor, runtime)
        try {
            engine.ensureLoaded().getOrThrow()
            val changed = initial.copy(topK = 40, batchSizeAuto = false, batchSize = 256)
            engine.updateParams(changed)
            engine.ensureLoaded().getOrThrow()
            assertEquals(1, runtime.loadedParams.size)
            assertEquals(changed, runtime.updates.last())
            assertFalse(runtime.events.contains("shutdown"))
            engine.updateParams(changed)
            engine.ensureLoaded().getOrThrow()
            assertEquals(2, runtime.updates.size)
        } finally {
            engine.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `native generation and cleanup finish before a pending batch reload`() = runBlocking {
        val runtime = RecordingRuntime()
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        runtime.duringGeneration = {
            started.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
        }
        val engine = LlamatikEngine(provider, ModelProfile.CALENDAR.defaults, executor, runtime)
        try {
            engine.ensureLoaded().getOrThrow()
            val response = async(Dispatchers.Default) { engine.generate(emptyList()).toList() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val changed = ModelProfile.CALENDAR.defaults.copy(batchSizeAuto = false, batchSize = 128)
            engine.updateParams(changed)
            assertEquals(1, runtime.updates.size)
            val reload = async(start = CoroutineStart.UNDISPATCHED) { engine.ensureLoaded() }
            assertFalse(reload.isCompleted)
            assertFalse(runtime.events.contains("shutdown"))
            finish.countDown()
            assertEquals(listOf("response"), response.await())
            reload.await().getOrThrow()
            assertEquals(changed, runtime.loadedParams.last())
            assertTrue(runtime.events.indexOf("generation finished") < runtime.events.indexOf("shutdown"))
            assertTrue(runtime.events.indexOf("cancel") < runtime.events.indexOf("shutdown"))
        } finally {
            finish.countDown()
            engine.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `failed native reload can be retried with the requested batch`() = runBlocking {
        val runtime = RecordingRuntime()
        val executor = Executors.newSingleThreadExecutor()
        val engine = LlamatikEngine(provider, ModelProfile.CALENDAR.defaults, executor, runtime)
        try {
            engine.ensureLoaded().getOrThrow()
            engine.updateParams(ModelProfile.CHAT.defaults)
            runtime.loadSucceeds = false
            assertTrue(engine.ensureLoaded().isFailure)
            assertTrue(engine.state.value is ModelState.Error)
            runtime.loadSucceeds = true
            engine.ensureLoaded().getOrThrow()
            assertEquals(ModelState.Ready, engine.state.value)
            assertEquals(512, runtime.batches.last())
            assertEquals(ModelProfile.CHAT.defaults, runtime.loadedParams.last())
        } finally {
            engine.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private val provider = object : ModelProvider {
        override suspend fun getModelPath() = Result.success("assistant.gguf")
    }

    private class RecordingRuntime : LlamaRuntime {
        val updates = CopyOnWriteArrayList<GenerationParams>()
        val loadedParams = CopyOnWriteArrayList<GenerationParams>()
        val batches = CopyOnWriteArrayList<Int>()
        val events = CopyOnWriteArrayList<String>()
        var duringGeneration: () -> Unit = {}
        var loadSucceeds = true
        override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) {
            updates += params
            batches += batchSize
            events += "params"
        }
        override fun load(path: String): Boolean {
            loadedParams += updates.last()
            events += "load"
            return loadSucceeds
        }
        override fun generateStream(prompt: String, stream: GenStream) {
            events += "generation started"
            duringGeneration()
            stream.onDelta("response")
            stream.onComplete()
            events += "generation finished"
        }
        override fun cancel() { events += "cancel" }
        override fun shutdown() { events += "shutdown" }
    }
}
