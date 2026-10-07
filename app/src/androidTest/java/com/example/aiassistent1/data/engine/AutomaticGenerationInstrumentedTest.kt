package com.example.aiassistent1.data.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.model.DeviceModelMemoryGuard
import com.example.aiassistent1.data.model.ModelMemoryGuard
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.domain.parser.AssistantResponseParser
import com.example.aiassistent1.domain.provider.SystemPromptProvider
import com.example.aiassistent1.domain.usecase.LongTextSummarizer
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in; does not modify saved conversations, calendar or user settings. */
@RunWith(AndroidJUnit4::class)
class AutomaticGenerationInstrumentedTest {
    @Test fun tokenizerNativeGenerationAndReuse() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("automaticModel")
        assumeTrue("Pass automaticModel filename", !name.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(requireNotNull(context.getExternalFilesDir("models")), requireNotNull(name))
        assertTrue(file.isFile)
        val real = DeviceModelMemoryGuard.forAndroid(context)
        var low = false
        var smallFirst = false
        var assessments = 0
        val guard = object : ModelMemoryGuard by real {
            override fun assess(file: File, params: GenerationParams): DeviceContextLimit {
                assessments++
                if (smallFirst) {
                    smallFirst = false
                    return DeviceContextLimit(512, MemoryLimitStatus.ESTIMATED)
                }
                return if (low) DeviceContextLimit(0, MemoryLimitStatus.LOW_MEMORY) else real.assess(file, params)
            }
        }
        val engine = LlamatikEngine(object : ModelProvider {
            override suspend fun getModelPath() = Result.success(file.path)
        }, guard, ModelProfile.CHAT.defaults.copy(temperature = 0f, topK = 1,
            cpuThreadsAuto = false, cpuThreads = 4))
        val report = JSONObject().put("model", name)
        try {
            val messages = listOf(ChatMessage(role = MessageRole.USER,
                content = "Скажи коротко: проверка завершена 🙂. /no_think"))
            val tokens = engine.countTokens(messages)
            assertTrue(tokens > 0)
            assertEquals(tokens, engine.countTokens(messages))
            val first = withTimeout(300_000) { engine.generateForTask(messages, GenerationTask.CHAT).toList().joinToString("") }
            val state = requireNotNull(engine.automaticState.value)
            assertEquals(tokens, state.promptTokens)
            assertEquals(GenerationStopReason.EOS, state.stopReason)
            assertTrue(first.isNotBlank())
            assertFalse(first.contains('\uFFFD'))
            val calls = assessments
            low = true
            val second = withTimeout(300_000) { engine.generateForTask(messages, GenerationTask.CHAT).toList().joinToString("") }
            assertTrue(second.isNotBlank())
            assertEquals("Reused allocation must not request a fresh RAM estimate", calls, assessments)
            assertEquals(state.contextSize, engine.automaticState.value?.contextSize)
            assertEquals(ModelState.Ready, engine.state.value)
            report.put("promptTokens", tokens).put("context", state.contextSize).put("batch", state.batchSize)
                .put("first", first).put("second", second).put("reuseAfterLowEstimate", true)
            File(context.cacheDir, "automatic-generation-smoke.json").writeText(report.toString(2))
            if (args.getString("longAnswer") == "true") {
                low = false
                val end = args.getString("longEnd")?.toIntOrNull()?.coerceIn(200, 400) ?: 400
                val request = listOf(ChatMessage(role = MessageRole.SYSTEM,
                    content = "Ты помощник. Выполняй запрос полностью. /no_think"),
                    ChatMessage(role = MessageRole.USER, content =
                        "Выведи числа от 1 до $end по порядку, каждое число с новой строки. Все $end чисел, без пропусков и без сокращений."))
                val long = withTimeout(900_000) { engine.generateForTask(request, GenerationTask.CHAT).toList().joinToString("") }
                val actual = requireNotNull(engine.automaticState.value)
                report.put("longPromptTokens", actual.promptTokens).put("longGeneratedTokens", actual.generatedTokens)
                    .put("longContext", actual.contextSize).put("longStop", actual.stopReason).put("longResponse", long)
                    .put("longRequestedEnd", end)
                assertEquals(GenerationStopReason.EOS, actual.stopReason)
                assertTrue("A long-answer fixture must exceed 512 generated tokens", actual.generatedTokens > 512)
                assertEquals((1..end).toList(), long.lineSequence().mapNotNull { it.trim().toIntOrNull() }.toList())
            }
            if (args.getString("nativeGrowth") == "true") {
                engine.unload()
                low = false
                var instruction = "Выведи числа от 1 до 200 по порядку, каждое число с новой строки. /no_think"
                while (engine.countTokens(listOf(ChatMessage(role = MessageRole.USER, content = instruction))) < 130) {
                    instruction += " Не пропускай числа."
                }
                smallFirst = true
                val request = listOf(ChatMessage(role = MessageRole.USER, content = instruction))
                val grown = withTimeout(300_000) { engine.generateForTask(request, GenerationTask.CALENDAR).toList().joinToString("") }
                val actual = requireNotNull(engine.automaticState.value)
                report.put("grownResponse", grown).put("grownContext", actual.contextSize)
                    .put("grownTokens", actual.generatedTokens).put("grownPromptTokens", actual.promptTokens)
                assertTrue("Native context was not expanded", actual.contextSize > 512)
                assertEquals(GenerationStopReason.EOS, actual.stopReason)
                val numbers = grown.lineSequence().mapNotNull { it.trim().toIntOrNull() }.toList()
                assertEquals((1..200).toList(), numbers)
            }
            if (args.getString("calendar") == "true") {
                low = false
                engine.updateParams(ModelProfile.CALENDAR.defaults.copy(temperature = 0f, topK = 1,
                    cpuThreadsAuto = false, cpuThreads = 4))
                val request = listOf(ChatMessage(role = MessageRole.SYSTEM, content = SystemPromptProvider().getSystemPrompt(true)),
                    ChatMessage(role = MessageRole.USER,
                        content = "Создай встречу Проверка памяти завтра в двенадцать пятнадцать на тридцать минут."))
                val json = withTimeout(300_000) { engine.generateForTask(request, GenerationTask.CALENDAR).toList().joinToString("") }
                report.put("calendarResponse", json)
                val parsed = AssistantResponseParser().parseResult(json).getOrThrow()
                assertEquals("calendar_add", parsed.intent)
                val params = parsed.params as CalendarAddParams
                assertTrue(params.time == "12:15" || params.startsAt?.endsWith("T12:15") == true)
                assertEquals(30, params.durationMin)
            }
            if (args.getString("summary") == "true") {
                low = false
                val source = "Анна отправит отчёт 5 октября.\n".repeat(16) +
                    "Борис проверит расчёты 6 октября.\n".repeat(16)
                val summary = withTimeout(600_000) {
                    LongTextSummarizer(engine).summarize(flowOf(source), maximumPromptTokens = 512)
                }
                report.put("summary", summary).put("summarySourceCharacters", source.length)
                assertTrue(summary.contains("Анн", ignoreCase = true))
                assertTrue(summary.contains("Борис", ignoreCase = true))
                assertEquals(GenerationTask.SUMMARY, engine.automaticState.value?.task)
            }
            report.put("status", "PASSED")
        } catch (error: Throwable) {
            report.put("status", "FAILED").put("error", error.toString())
            throw error
        } finally {
            engine.close()
            File(context.cacheDir, "automatic-generation-smoke.json").writeText(report.toString(2))
            Log.i("AutomaticGenerationSmoke", report.toString())
        }
    }
}
