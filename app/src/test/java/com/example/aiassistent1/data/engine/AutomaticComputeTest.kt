package com.example.aiassistent1.data.engine

import com.example.aiassistent1.data.model.GgufTestFile
import com.example.aiassistent1.data.model.ModelMemoryGuard
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.domain.usecase.textSnapshots
import com.llamatik.library.platform.GenStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutomaticComputeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `auto uses compatible GPU and explicit CPU skips device probing`() = runBlocking {
        fixture { engine, runtime, _ ->
            engine.generateEventsForTask(emptyList(), GenerationTask.CHAT).toList()
            assertTrue(runtime.loadedLayers.single() > 0)
            engine.updateParams(GenerationParams(computeMode = ComputeMode.CPU))
            val probes = runtime.probes
            engine.generateEventsForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(0, runtime.loadedLayers.last())
            assertEquals(probes, runtime.probes)
        }
    }

    @Test fun `both GPU allocation failures try OpenCL then CPU without restarting visible text`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.failLoad = true
            val events = engine.generateEventsForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(listOf(InferenceBackend.VULKAN, InferenceBackend.OPENCL, InferenceBackend.CPU), runtime.loadedBackends)
            assertTrue(runtime.loadedLayers.first() > 0)
            assertEquals(0, runtime.loadedLayers.last())
            assertTrue(events.none { it is GenerationEvent.Restart })
            assertEquals("cpu complete", (events.last() as GenerationEvent.Delta).text)
        }
    }

    @Test fun `Vulkan failure before output reaches OpenCL with identical layer count and a single ready callback`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.failBeforeOutput = InferenceBackend.VULKAN
            var ready = 0
            val events = engine.generateEventsForTask(emptyList(), GenerationTask.CHAT) { ready++ }.toList()
            assertEquals(listOf(InferenceBackend.VULKAN, InferenceBackend.OPENCL), runtime.loadedBackends)
            assertEquals(runtime.loadedLayers.first(), runtime.loadedLayers.last())
            assertEquals(listOf(GenerationEvent.Delta("gpu complete")), events)
            assertEquals(1, ready)
            assertTrue(requireNotNull(runtime.fallback).contains("Vulkan"))
        }
    }

    @Test fun `OpenCL partial output is replaced by CPU after Vulkan is unavailable`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.unavailable = InferenceBackend.VULKAN
            runtime.failGeneration = true
            val events = engine.generateEventsForTask(emptyList(), GenerationTask.CALENDAR).toList()
            assertEquals(listOf(InferenceBackend.OPENCL, InferenceBackend.CPU), runtime.loadedBackends)
            assertEquals(1, events.filterIsInstance<GenerationEvent.Restart>().size)
            assertTrue(requireNotNull(runtime.fallback).contains("OpenCL"))
        }
    }

    @Test fun `zero OpenCL budget and a too-small single buffer each select CPU`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.unavailable = InferenceBackend.VULKAN
            for (device in listOf(GpuDevice("Adreno", "id", 0, 1024), GpuDevice("Adreno", "id", null, 1024, 1))) {
                runtime.openClDevice = device
                engine.generateEventsForTask(emptyList(), GenerationTask.CHAT).toList()
                assertEquals(InferenceBackend.CPU, runtime.params.inferenceBackend)
            }
        }
    }

    @Test fun `explicit loading also recovers from a GPU allocation failure`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.failLoad = true
            assertTrue(engine.ensureLoaded().isSuccess)
            assertTrue(runtime.loadedLayers.first() > 0)
            assertEquals(0, runtime.loadedLayers.last())
            assertNotNull(runtime.fallback)
        }
    }

    @Test fun `manual memory settings retain GPU to CPU recovery`() = runBlocking {
        fixture { engine, runtime, _ ->
            engine.updateParams(GenerationParams(automaticMemory = false, contextSize = 1024, maxTokens = 256))
            runtime.failLoad = true
            val events = engine.generateEventsForTask(emptyList(), GenerationTask.CHAT).toList()
            assertEquals(listOf(InferenceBackend.VULKAN, InferenceBackend.OPENCL, InferenceBackend.CPU), runtime.loadedBackends)
            assertTrue(runtime.loadedLayers.first() > 0)
            assertEquals(0, runtime.loadedLayers.last())
            assertEquals("cpu complete", (events.last() as GenerationEvent.Delta).text)
        }
    }

    @Test fun `CPU attempt replaces partial GPU response and announces readiness once`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.failGeneration = true
            var starts = 0
            val snapshots = engine.generateEventsForTask(emptyList(), GenerationTask.CHAT) { starts++ }
                .textSnapshots().toList()
            assertEquals(listOf("gpu partial", "cpu complete"), snapshots)
            assertEquals(1, starts)
            assertEquals(2, runtime.loadedLayers.size)
        }
    }

    @Test fun `cancelled GPU request never starts a CPU retry`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.cancelGeneration = true
            val failure = runCatching { engine.generateEventsForTask(emptyList(), GenerationTask.CHAT).toList() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(1, runtime.loadedLayers.size)
        }
    }

    @Test fun `cancellation during first GPU probe prevents both the next probe and CPU retry`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.unavailable = InferenceBackend.VULKAN
            runtime.onProbe = { engine.cancelGeneration() }
            val failure = runCatching { engine.generateEventsForTask(emptyList(), GenerationTask.CHAT).toList() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(1, runtime.probes)
            assertTrue(runtime.loadedBackends.isEmpty())
        }
    }

    @Test fun `ensureLoaded verifies a changed GGUF at the same path again`() = runBlocking {
        fixture { engine, runtime, _ ->
            engine.ensureLoaded().getOrThrow()
            engine.ensureLoaded().getOrThrow()
            assertEquals(1, runtime.loadedBackends.size)
            val file = requireNotNull(folder.root.listFiles()).single()
            assertTrue(file.setLastModified(file.lastModified() + 2000L))
            engine.ensureLoaded().getOrThrow()
            assertEquals(listOf(InferenceBackend.VULKAN, InferenceBackend.VULKAN), runtime.loadedBackends)
        }
    }

    @Test fun `GPU memory rejection can still load the full request on CPU`() = runBlocking {
        fixture { engine, runtime, guard ->
            guard.rejectGpu = true
            val output = engine.generateEventsForTask(emptyList(), GenerationTask.SUMMARY).toList()
            assertEquals(listOf(0), runtime.loadedLayers)
            assertEquals("cpu complete", (output.last() as GenerationEvent.Delta).text)
            assertNotNull(runtime.fallback)
        }
    }

    @Test fun `stream larger than channel capacity drains while the runtime is blocking`() = runBlocking {
        fixture { engine, runtime, _ ->
            runtime.outputChunks = List(200) { "token" }
            val events = withTimeout(5_000) { engine.generateEventsForTask(emptyList(), GenerationTask.CHAT).toList() }
            assertEquals(200, events.filterIsInstance<GenerationEvent.Delta>().size)
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
        var rejectGpu = false
        override fun assess(file: File, params: GenerationParams) =
            if (rejectGpu && params.gpuLayers != 0) DeviceContextLimit(0, MemoryLimitStatus.INSUFFICIENT_MEMORY)
            else DeviceContextLimit(8192, MemoryLimitStatus.ESTIMATED)
        override fun beforeLoad(): DeviceMemorySnapshot? = null
        override fun loaded(file: File, params: GenerationParams, before: DeviceMemorySnapshot?) = Unit
        override fun unloaded() = Unit
        override fun refresh() = Unit
    }

    private class Runtime : LlamaRuntime {
        var params = GenerationParams()
        val loadedLayers = mutableListOf<Int>()
        val loadedBackends = mutableListOf<InferenceBackend>()
        var unavailable: InferenceBackend? = null
        var failBeforeOutput: InferenceBackend? = null
        var openClDevice: GpuDevice? = null
        var probes = 0
        var onProbe: () -> Unit = {}
        var failLoad = false
        var failGeneration = false
        var cancelGeneration = false
        var fallback: String? = null
        var outputChunks: List<String>? = null
        override var lastGeneration: GenerationResult? = null
        override fun gpuDevice(path: String, backend: InferenceBackend): GpuDevice? {
            probes++
            onProbe()
            if (backend == unavailable) return null
            if (backend == InferenceBackend.OPENCL) openClDevice?.let { return it }
            return GpuDevice("Test GPU", "driver-1", 64L * 1024 * 1024 * 1024, 64L * 1024 * 1024 * 1024)
        }
        override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) { this.params = params }
        override fun prepareTokenizer(path: String) = Unit
        override fun countTokens(prompt: String) = 32
        override fun load(path: String): Boolean {
            loadedLayers += params.gpuLayers
            loadedBackends += params.inferenceBackend
            if (params.gpuLayers > 0 && failLoad) throw GpuExecutionException("GPU allocation failed")
            return true
        }
        override fun generateStream(prompt: String, stream: GenStream) {
            if (params.gpuLayers > 0) {
                if (params.inferenceBackend == failBeforeOutput) throw GpuExecutionException("Kernel failed")
                if (cancelGeneration) throw CancellationException("User cancelled")
                if (failGeneration) { stream.onDelta("gpu partial"); throw GpuExecutionException("Device lost") }
            }
            val chunks = outputChunks ?: listOf(if (params.gpuLayers > 0) "gpu complete" else "cpu complete")
            chunks.forEach(stream::onDelta)
            lastGeneration = GenerationResult(GenerationStopReason.EOS, 32, 2, params.contextSize)
            stream.onComplete()
        }
        override fun recordFallback(reason: String?) { fallback = reason }
        override fun cancel() = Unit
        override fun shutdown() = Unit
    }
}
