package com.example.aiassistent1.data.engine

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.model.DeviceModelMemoryGuard
import com.example.aiassistent1.data.model.GgufMetadataReader
import com.example.aiassistent1.data.model.ModelMemoryGuard
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.domain.provider.SystemPromptProvider
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, isolated engines: no saved messages, calendar commands or preference writes. */
@RunWith(AndroidJUnit4::class)
class ContextAllocationInstrumentedTest {
    @Test fun importedModelWithCompleteCalendarAndChatPrompts() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("contextAllocationModel")
        assumeTrue("Pass contextAllocationModel filename", !name.isNullOrBlank())
        val context = instrumentation.targetContext
        val file = requireNotNull(GgufMetadataReader.resolveInDirectory(
            requireNotNull(context.getExternalFilesDir("models")), requireNotNull(name)))
        assertTrue(file.isFile)
        val metadata = requireNotNull(GgufMetadataReader.readMetadata(file))
        val report = JSONObject().put("model", name).put("fileBytes", file.length())
            .put("trainedContext", metadata.contextLength).put("cases", JSONArray())
        val rows = report.getJSONArray("cases")
        val provider = object : ModelProvider { override suspend fun getModelPath() = Result.success(file.path) }
        val timeoutMillis = (args.getString("contextAllocationTimeoutSeconds")?.toLongOrNull() ?: 180)
            .coerceIn(30, 600) * 1000
        val selectedTask = args.getString("contextAllocationTask")
        val tasks = listOf(GenerationTask.CHAT, GenerationTask.CALENDAR)
            .filter { selectedTask == null || it.name == selectedTask }
        require(tasks.isNotEmpty()) { "contextAllocationTask must be CHAT or CALENDAR" }
        report.put("timeoutSeconds", timeoutMillis / 1000)
        var probeTimeout: TimeoutCancellationException? = null
        try {
            for (task in tasks) {
                val real = DeviceModelMemoryGuard.forAndroid(context)
                val params = (if (task == GenerationTask.CALENDAR) ModelProfile.CALENDAR else ModelProfile.CHAT)
                    .defaults.copy(temperature = 0f, topK = 1, batchSizeAuto = false, batchSize = 64,
                        cpuThreadsAuto = false, cpuThreads = 4, gpuLayers = 0)
                val messages = listOf(
                    ChatMessage(role = MessageRole.SYSTEM,
                        content = SystemPromptProvider().getSystemPrompt(task == GenerationTask.CALENDAR)),
                    ChatMessage(role = MessageRole.USER, content = if (task == GenerationTask.CALENDAR)
                        "Создай встречу Проверка контекста завтра в 12:15 на 30 минут."
                    else "Ответь одним словом: готово.\n\nотвечай очень вежливо используй эмодзи"),
                )
                val row = JSONObject().put("task", task.name)
                rows.put(row)
                var needsProbe = false
                val engine = LlamatikEngine(provider, real, params)
                val minimum: Int
                try {
                    val tokens = engine.countTokens(messages)
                    minimum = AutomaticContextPolicy.minimumContext(tokens, task)
                    val assessment = real.assess(file, params)
                    row.put("promptTokens", tokens).put("minimumContext", minimum)
                        .put("minimumAnswer", AutomaticContextPolicy.minimumAnswer(task))
                        .put("assessmentStatus", assessment.status.name)
                        .put("assessedMaximumContext", assessment.maximumContext)
                        .put("availableBytes", assessment.availableBytes).put("reserveBytes", assessment.reserveBytes)
                    try {
                        measure(engine, real, messages, task, row, "production", timeoutMillis)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        assertTrue(error.toString(), error is ModelAllocationException || error is PromptCapacityException)
                        row.put("productionError", error.toString())
                        if (error is ModelAllocationException) row.put("productionFailure", error.reason.name)
                        assertFalse(error.message.orEmpty().contains("по частям"))
                        needsProbe = true
                    }
                } finally { engine.close() }

                if (needsProbe && args.getString("contextAllocationNativeProbe") == "true") {
                    // Explicit diagnostic override only. Production RAM policy is never changed by this test.
                    val cost = metadata.memory?.let { ModelMemoryCalculator.cost(it, MemoryRuntimeSettings(false, 64), minimum) }
                    val memory = real.beforeLoad()
                    if (cost == null || memory == null || memory.lowMemory ||
                        cost.totalBytes > memory.availableBytes - memory.lowMemoryThresholdBytes) {
                        row.put("nativeProbe", "SKIPPED: insufficient measured headroom or missing metadata")
                    } else {
                        row.put("nativeProbeEstimatedBytes", cost.totalBytes).put("nativeProbePolicyOverride", true)
                        val probeGuard = object : ModelMemoryGuard by real {
                            override fun assess(file: File, params: GenerationParams) =
                                DeviceContextLimit(minimum, MemoryLimitStatus.ESTIMATED)
                        }
                        val probe = LlamatikEngine(provider, probeGuard, params)
                        try { measure(probe, real, messages, task, row, "nativeProbe", timeoutMillis) }
                        catch (error: TimeoutCancellationException) {
                            row.put("nativeProbeError", error.toString())
                            if (probeTimeout == null) probeTimeout = error
                        }
                        finally { probe.close() }
                    }
                }
                File(context.cacheDir, "context-allocation-device.json").writeText(report.toString(2))
            }
            probeTimeout?.let { throw it }
            report.put("status", "PASSED")
        } catch (error: Throwable) {
            report.put("status", "FAILED").put("error", error.toString())
            throw error
        } finally {
            File(context.cacheDir, "context-allocation-device.json").writeText(report.toString(2))
            Log.i("ContextAllocationTest", report.toString())
        }
    }

    private suspend fun measure(engine: LlamatikEngine, guard: DeviceModelMemoryGuard,
        messages: List<ChatMessage>, task: GenerationTask, row: JSONObject, prefix: String,
        timeoutMillis: Long) = coroutineScope {
        val before = requireNotNull(guard.beforeLoad())
        val peakPrivate = AtomicLong(before.processPrivateDirtyBytes)
        val peakPss = AtomicLong(Debug.getPss() * 1024)
        val lowestAvailable = AtomicLong(before.availableBytes)
        val started = System.nanoTime()
        val readyAt = AtomicLong(0)
        val output = StringBuilder()
        val monitor = launch(Dispatchers.Default) {
            while (isActive) {
                if (engine.state.value is ModelState.Ready) readyAt.compareAndSet(0, System.nanoTime())
                guard.beforeLoad()?.let {
                    peakPrivate.updateAndGet { value -> maxOf(value, it.processPrivateDirtyBytes) }
                    lowestAvailable.updateAndGet { value -> minOf(value, it.availableBytes) }
                }
                peakPss.updateAndGet { maxOf(it, Debug.getPss() * 1024) }
                delay(250)
            }
        }
        try {
            withTimeout(timeoutMillis) {
                engine.generateForTask(messages, task).collect { output.append(it) }
            }
            val state = requireNotNull(engine.automaticState.value)
            assertTrue(output.isNotBlank())
            assertEquals(GenerationStopReason.EOS, state.stopReason)
            assertTrue(state.availableAnswerTokens >= AutomaticContextPolicy.minimumAnswer(task))
        } finally {
            monitor.cancelAndJoin()
            val state = engine.automaticState.value
            row.put(prefix + "Seconds", (System.nanoTime() - started) / 1_000_000_000.0)
                .put(prefix + "AllocationReady", readyAt.get() != 0L || state != null)
                .put(prefix + "Output", output.toString())
            if (readyAt.get() != 0L) row.put(prefix + "ReadyAfterSeconds", (readyAt.get() - started) / 1_000_000_000.0)
            state?.let {
                row.put(prefix + "Context", it.contextSize).put(prefix + "Batch", it.batchSize)
                    .put(prefix + "ReportedGeneratedTokens", it.generatedTokens)
                    .put(prefix + "Stop", it.stopReason?.name)
            }
            row.put(prefix + "PrivateDirtyBefore", before.processPrivateDirtyBytes)
                .put(prefix + "PeakPrivateDirty", peakPrivate.get()).put(prefix + "PeakPss", peakPss.get())
                .put(prefix + "LowestAvailableBytes", lowestAvailable.get())
        }
    }
}
