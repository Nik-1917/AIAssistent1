package com.example.aiassistent1.data.engine

import android.app.ActivityManager
import android.os.Bundle
import android.os.Process
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.model.DeviceModelMemoryGuard
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.context.ModelContextBuilder
import com.example.aiassistent1.domain.parser.AssistantResponseParser
import com.example.aiassistent1.domain.provider.SystemPromptProvider
import com.example.aiassistent1.service.GenerationForegroundService
import com.example.aiassistent1.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executors

/** Opt-in device verification through the same remote runtime used by the application. */
@RunWith(AndroidJUnit4::class)
class GpuAutomaticInstrumentedTest {
    @Test fun autoAndCpuGenerateWithTheInstalledGguf() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("gpuModel")
        assumeTrue("Pass gpuModel filename", !name.isNullOrBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val file = File(requireNotNull(app.getExternalFilesDir("models")), requireNotNull(name))
        assertTrue(file.isFile)
        // Instrumentation stops the original foreground activity. Launch the app before
        // acquiring the same foreground generation session used by ChatViewModel.
        val launcher = requireNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName)?.component)
        val host = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-f 0x10008000 -n ${launcher.flattenToString()}")
        ParcelFileDescriptor.AutoCloseInputStream(host).bufferedReader().use { assertTrue(it.readText().contains("Status: ok")) }
        val report = JSONArray()
        val expected = args.getString("expectedAuto")?.let(InferenceBackend::valueOf)
        val parser = AssistantResponseParser()
        val session = withContext(Dispatchers.Main) {
            GenerationForegroundService.acquire(app, GenerationForegroundService.Kind.Generation)
        }
        try {
            session.awaitReady()
            for (mode in listOf(ComputeMode.AUTO, ComputeMode.CPU)) {
                val runtime = RemoteLlamaRuntime(app)
                val guard = DeviceModelMemoryGuard.forAndroid(app) { RemoteLlamaRuntime.residentPrivateBytes.get() }
                val engine = LlamatikEngine(object : ModelProvider {
                    override suspend fun getModelPath() = Result.success(file.path)
                }, GenerationParams(computeMode = mode, temperature = 0f, topK = 1),
                    Executors.newSingleThreadExecutor(), runtime, memoryGuard = guard)
                try {
                    suspend fun generate(task: GenerationTask, interruptGpu: Boolean = false) {
                        val calendar = task == GenerationTask.CALENDAR
                        val system = if (task == GenerationTask.SUMMARY) "Кратко перескажи текст одним предложением."
                            else SystemPromptProvider().getSystemPrompt(calendar, LocalDateTime.of(2026, 11, 18, 12, 0))
                        val content = when (task) {
                            GenerationTask.CALENDAR -> "Создай событие с названием Встреча"
                            GenerationTask.CHAT -> "Привет"
                            GenerationTask.SUMMARY -> "На совещании решили провести встречу в пятницу."
                        }
                        val messages = listOf(ChatMessage(role = MessageRole.SYSTEM, content = system)) +
                            ModelContextBuilder().build(listOf(ChatMessage(role = MessageRole.USER, content = content)),
                                appendChatStyleInstruction = task == GenerationTask.CHAT, isCalendarMode = calendar)
                        var killed = false
                        var restarts = 0
                        var readyCount = 0
                        val output = StringBuilder()
                        val events = withTimeout(300_000) {
                            engine.generateEventsForTask(messages, task) { readyCount++ }.onEach { event ->
                                when (event) {
                                    is GenerationEvent.Delta -> {
                                        output.append(event.text)
                                        if (interruptGpu && !killed) {
                                            assertNotEquals(InferenceBackend.CPU, engine.executionState.value.backend)
                                            val pid = app.getSystemService(ActivityManager::class.java).runningAppProcesses
                                                .single { it.uid == Process.myUid() && it.processName == "${app.packageName}:llm" }.pid
                                            Process.killProcess(pid)
                                            killed = true
                                        }
                                    }
                                    is GenerationEvent.Restart -> { output.clear(); restarts++ }
                                }
                            }.toList()
                        }
                        assertTrue(events.filterIsInstance<GenerationEvent.Delta>().any { it.text.isNotEmpty() })
                        val actual = engine.executionState.value
                        assertNotNull(actual.backend)
                        assertEquals(1, readyCount)
                        assertEquals(if (interruptGpu) 1 else 0, restarts)
                        if (mode == ComputeMode.CPU || interruptGpu) {
                            assertEquals(InferenceBackend.CPU, actual.backend)
                            assertEquals(0, actual.offloadedLayers)
                        } else {
                            expected?.let { assertEquals(actual.toString(), it, actual.backend) }
                            if (actual.backend != InferenceBackend.CPU) assertTrue(actual.offloadedLayers > 0)
                            else assertNotNull(actual.fallbackReason)
                        }
                        if (calendar) {
                            val command = parser.parseResult(output.toString()).getOrThrow()
                            assertEquals("calendar_add", command.intent)
                            assertEquals("Встреча", (command.params as CalendarAddParams).title)
                        } else assertTrue(parser.parseChatReply(output.toString()).getOrThrow().isNotBlank())
                        assertNotNull(actual.timeToFirstTokenMillis)
                        assertTrue(requireNotNull(actual.decodeTokensPerSecond) > 0)
                        report.put(JSONObject().put("requested", mode).put("task", task).put("backend", actual.backend)
                            .put("device", actual.deviceName).put("layers", actual.offloadedLayers)
                            .put("totalLayers", actual.totalLayers).put("workerKilledAfterDelta", killed)
                            .put("restarts", restarts).put("readyCallbacks", readyCount)
                            .put("output", output.toString()).put("model", file.name)
                            .put("fallback", actual.fallbackReason).put("initializationMillis", actual.initializationMillis)
                            .put("ttftMillis", actual.timeToFirstTokenMillis).put("prefillTokensPerSecond", actual.prefillTokensPerSecond)
                            .put("decodeTokensPerSecond", actual.decodeTokensPerSecond).put("generatedTokens", actual.generatedTokens))
                        withContext(Dispatchers.IO) { File(app.cacheDir, "gpu-auto-device.json").writeText(report.toString(2)) }
                    }
                    for (task in GenerationTask.entries) generate(task)
                    if (mode == ComputeMode.AUTO && args.getString("exerciseGpuFailure") == "true") {
                        generate(GenerationTask.CALENDAR, interruptGpu = true)
                    }
                    if (mode == ComputeMode.CPU) {
                        val worker = app.getSystemService(ActivityManager::class.java).runningAppProcesses
                            .first { it.uid == Process.myUid() && it.processName == "${app.packageName}:llm" }
                        assertNotEquals(Process.myPid(), worker.pid)
                        Process.killProcess(worker.pid)
                        withTimeout(5_000) { while (runtime.allocationValid) delay(20) }
                        generate(GenerationTask.CALENDAR)
                        withContext(Dispatchers.IO) {
                            File(app.cacheDir, "gpu-worker-restart.txt").writeText("CPU generation succeeded after worker process termination")
                        }
                    }
                } finally { engine.close() }
            }
            instrumentation.addResults(Bundle().apply { putString("gpuReport", report.toString()) })
        } finally { withContext(Dispatchers.Main) { session.close() } }
    }
}
