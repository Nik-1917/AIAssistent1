package com.example.aiassistent1.data.engine

import android.app.ActivityManager
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.model.DeviceModelMemoryGuard
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
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
import java.util.concurrent.Executors

/** Opt-in device verification through the same remote runtime used by the application. */
@RunWith(AndroidJUnit4::class)
class GpuAutomaticInstrumentedTest {
    @Test fun autoAndCpuGenerateWithTheInstalledGguf() = runBlocking<Unit> {
        val name = InstrumentationRegistry.getArguments().getString("gpuModel")
        assumeTrue("Pass gpuModel filename", !name.isNullOrBlank())
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(requireNotNull(app.getExternalFilesDir("models")), requireNotNull(name))
        assertTrue(file.isFile)
        val report = JSONArray()
        for (mode in listOf(ComputeMode.AUTO, ComputeMode.CPU)) {
            val runtime = RemoteLlamaRuntime(app)
            val guard = DeviceModelMemoryGuard.forAndroid(app) { RemoteLlamaRuntime.residentPrivateBytes.get() }
            val engine = LlamatikEngine(object : ModelProvider {
                override suspend fun getModelPath() = Result.success(file.path)
            }, GenerationParams(computeMode = mode, temperature = 0f, topK = 1),
                Executors.newSingleThreadExecutor(), runtime, memoryGuard = guard)
            try {
                suspend fun generate(task: GenerationTask) {
                    val events = withTimeout(300_000) {
                        engine.generateEventsForTask(listOf(
                            ChatMessage(role = MessageRole.SYSTEM, content = "Ответь кратко. Сегодня 2026-11-12."),
                            ChatMessage(role = MessageRole.USER, content = "Привет"),
                        ), task).toList()
                    }
                    assertTrue(events.filterIsInstance<GenerationEvent.Delta>().any { it.text.isNotEmpty() })
                    val actual = engine.executionState.value
                    assertNotNull(actual.backend)
                    if (mode == ComputeMode.CPU) {
                        assertEquals(InferenceBackend.CPU, actual.backend)
                        assertEquals(0, actual.offloadedLayers)
                    } else if (actual.backend == InferenceBackend.VULKAN) assertTrue(actual.offloadedLayers > 0)
                    else assertNotNull(actual.fallbackReason)
                    assertNotNull(actual.timeToFirstTokenMillis)
                    assertTrue(requireNotNull(actual.decodeTokensPerSecond) > 0)
                    report.put(JSONObject().put("requested", mode).put("task", task).put("backend", actual.backend)
                        .put("device", actual.deviceName).put("layers", actual.offloadedLayers)
                        .put("fallback", actual.fallbackReason).put("initializationMillis", actual.initializationMillis)
                        .put("ttftMillis", actual.timeToFirstTokenMillis).put("prefillTokensPerSecond", actual.prefillTokensPerSecond)
                        .put("decodeTokensPerSecond", actual.decodeTokensPerSecond).put("generatedTokens", actual.generatedTokens))
                    withContext(Dispatchers.IO) { File(app.cacheDir, "gpu-auto-device.json").writeText(report.toString(2)) }
                }
                for (task in GenerationTask.entries) generate(task)
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
    }
}
