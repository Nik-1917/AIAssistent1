package com.example.aiassistent1.data.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.domain.parser.AssistantResponseParser
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in comparison with the previously used JNI bridge; no saved calendar or settings writes. */
@RunWith(AndroidJUnit4::class)
class CalendarBridgeComparisonTest {
    @Test fun compareCalendarWithPackagedBridge() = runBlocking<Unit> {
        val name = InstrumentationRegistry.getArguments().getString("automaticModel")
        assumeTrue("Pass automaticModel filename", !name.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(requireNotNull(context.getExternalFilesDir("models")), requireNotNull(name))
        assertTrue(file.isFile)
        val params = ModelProfile.CALENDAR.defaults.copy(temperature = 0f, topK = 1,
            cpuThreadsAuto = false, cpuThreads = 4, batchSizeAuto = false, batchSize = 64)
        val messages = listOf(
            ChatMessage(role = MessageRole.SYSTEM,
                content = "cегодня 2026-10-04 03:52 день недели воскресенье ответ JSON"),
            ChatMessage(role = MessageRole.USER,
                content = "Создай встречу Проверка памяти завтра в двенадцать пятнадцать на тридцать минут."),
        )
        val report = JSONObject().put("model", name).put("system", messages[0].content)
            .put("user", messages[1].content).put("context", 512).put("batch", 64)
            .put("temperature", 0).put("topK", 1).put("repeatPenalty", params.repeatPenalty)
        val provider = object : ModelProvider {
            override suspend fun getModelPath() = Result.success(file.path)
        }
        try {
            val automatic = LlamatikEngine(provider, params, Executors.newSingleThreadExecutor(), NativeLlamaRuntime)
            val output = try {
                withTimeout(300_000) { automatic.generateForTask(messages, GenerationTask.CALENDAR).toList().joinToString("") }
            } finally { automatic.close() }
            report.put("automatic", output)
            File(context.cacheDir, "calendar-bridge-comparison.json").writeText(report.toString(2))

            // Deliberately retain all former wrapper behavior, including sampling and output filtering.
            val legacyRuntime = object : LlamaRuntime {
                override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) {
                    LlamaBridge.updateGenerateParams(temperature = params.temperature, maxTokens = params.maxTokens,
                        topP = params.topP, topK = params.topK, repeatPenalty = params.repeatPenalty,
                        contextLength = params.contextSize, numThreads = threads, useMmap = true,
                        flashAttention = true, batchSize = batchSize, gpuLayers = params.gpuLayers)
                }
                override fun load(path: String) = LlamaBridge.initGenerateModel(path)
                override fun generateStream(prompt: String, stream: GenStream) = LlamaBridge.generateStream(prompt, stream)
                override fun cancel() = LlamaBridge.nativeCancelGenerate()
                override fun shutdown() = LlamaBridge.shutdown()
            }
            val legacy = LlamatikEngine(provider, params, Executors.newSingleThreadExecutor(), legacyRuntime)
            val previous = try {
                legacy.ensureLoaded().getOrThrow()
                withTimeout(300_000) { legacy.generate(messages).toList().joinToString("") }
            } finally { legacy.close() }
            report.put("legacy", previous).put("identicalText", previous.trim() == output.trim())
            for (json in listOf(output, previous)) {
                val parsed = AssistantResponseParser().parseResult(json).getOrThrow()
                assertEquals("calendar_add", parsed.intent)
                val actual = parsed.params as CalendarAddParams
                assertEquals("2026-10-05T12:15", actual.startsAt)
                assertEquals(30, actual.durationMin)
            }
            report.put("status", "PASSED")
        } catch (error: Throwable) {
            report.put("status", "FAILED").put("error", error.toString())
            throw error
        } finally {
            File(context.cacheDir, "calendar-bridge-comparison.json").writeText(report.toString(2))
        }
    }
}
