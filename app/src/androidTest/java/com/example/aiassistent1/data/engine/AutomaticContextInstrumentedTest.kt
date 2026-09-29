package com.example.aiassistent1.data.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.domain.context.ContextCapacityException
import com.example.aiassistent1.domain.context.ContextWindowPolicy
import com.example.aiassistent1.domain.context.ModelContextBuilder
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.provider.SystemPromptProvider
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticContextInstrumentedTest {
    @Test fun realTokenizerAndReloadGrowThenReturnToMinimum() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val name = arguments.getString("contextModel")
        assumeTrue("Pass an imported GGUF name in contextModel", !name.isNullOrBlank())
        require(!name!!.contains('/') && !name.contains('\\'))
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("models")!!
        val model = File(directory, name)
        assumeTrue("Imported model is unavailable", model.isFile)
        val settings = GenerationParams(contextSize = 1024, maxContextSize = 8192)
        val engine = LlamatikEngine(object : ModelProvider {
            override suspend fun getModelPath() = Result.success(model.absolutePath)
        }, settings)
        val short = listOf(ChatMessage(role = MessageRole.USER, content = "Ответь одним словом: привет"))
        val long = listOf(ChatMessage(role = MessageRole.USER,
            content = "Встреча с командой завтра в десять утра. ".repeat(180)))
        try {
            withTimeout(180_000) {
                val inspection = NativeLlamaRuntime.inspectPrompt(model.absolutePath, engine.buildPrompt(short), short.single().content)
                assertTrue(inspection.tokenCount > 0)
                assertTrue(inspection.userMessageTokens!! in 1..322)
                assertTrue(inspection.modelContextLimit >= settings.maxContextSize)
                Log.i("AutomaticContextTest", "vocabulary: $inspection")
                recordSmallMessageBoundary(engine, model.absolutePath)
                recordMessageBudgets(engine, model.absolutePath)
                engine.prepareGeneration(short, short.single().content).getOrThrow()
                assertEquals(1024, engine.contextBudget.value!!.contextSize)
                assertEquals(512, engine.contextBudget.value!!.responseTokens)
                assertEquals(32, engine.contextBudget.value!!.reserveTokens)
                Log.i("AutomaticContextTest", "small: ${engine.contextBudget.value}")
                for ((repetitions, expectedSize) in listOf(35 to 2048, 80 to 4096, 180 to 8192)) {
                    val messages = listOf(ChatMessage(role = MessageRole.USER,
                        content = "Встреча с командой завтра в десять утра. ".repeat(repetitions)))
                    engine.prepareGeneration(messages, messages.single().content).getOrThrow()
                    val enlarged = engine.contextBudget.value!!
                    assertEquals(expectedSize, enlarged.contextSize)
                    assertEquals(expectedSize / 2, enlarged.responseTokens)
                    assertEquals(128, enlarged.reserveTokens)
                    assertTrue(enlarged.requiredTokens <= enlarged.contextSize)
                    Log.i("AutomaticContextTest", "step: $enlarged")
                }
                val result = engine.prepareGeneration(short, short.single().content).getOrThrow().toList().joinToString("")
                assertEquals(1024, engine.contextBudget.value!!.contextSize)
                assertEquals(512, engine.contextBudget.value!!.responseTokens)
                assertEquals(32, engine.contextBudget.value!!.reserveTokens)
                assertTrue("Native generation returned no text", result.isNotBlank())
                Log.i("AutomaticContextTest", "returned: ${engine.contextBudget.value}; responseLength=${result.length}")

                engine.updateParams(settings.copy(autoContextEnabled = false))
                assertTrue(engine.prepareGeneration(long, long.single().content).exceptionOrNull() is ContextCapacityException)
                assertEquals(1024, engine.contextBudget.value!!.contextSize)
            }
        } finally { engine.close() }
    }

    private fun recordSmallMessageBoundary(engine: LlamatikEngine, path: String) {
        for (tokens in listOf(322, 323)) {
            val raw = " да".repeat(tokens)
            val messages = listOf(ChatMessage(role = MessageRole.SYSTEM, content = " да".repeat(100)),
                ChatMessage(role = MessageRole.USER, content = raw))
            val inspection = NativeLlamaRuntime.inspectPrompt(path, engine.buildPrompt(messages), raw)
            assertEquals(tokens, inspection.userMessageTokens)
            val budget = ContextWindowPolicy.plan(inspection.tokenCount, GenerationParams(),
                inspection.modelContextLimit, inspection.userMessageTokens)
            assertEquals(if (tokens == 322) 32 else 128, budget.reserveTokens)
            assertEquals(if (tokens == 322) 1024 else 2048, budget.contextSize)
            Log.i("AutomaticContextTest", "small boundary: $budget")
        }
    }

    private fun recordMessageBudgets(engine: LlamatikEngine, path: String) {
        val samples = linkedMapOf(
            "russian" to "Запланируй встречу с командой завтра в 10:00, напомни за 30 минут. ".repeat(100).take(3000),
            "logs" to "ERROR 2026-09-30T12:34:56Z request_id=abc-123: timeout after 5000ms\n".repeat(100).take(3000),
            "emoji" to "😀".repeat(1500),
        )
        val previousReply = " да".repeat(512)
        val replyTokens = NativeLlamaRuntime.inspectPrompt(path, previousReply).tokenCount
        val builder = ModelContextBuilder()
        val provider = SystemPromptProvider()
        Log.i("AutomaticContextTest", "measurement replyTokens=$replyTokens; calendarSystem=${provider.getSystemPrompt(true)}")
        for ((name, text) in samples) {
            assertEquals(3000, text.length)
            val history = listOf(
                ChatMessage(role = MessageRole.USER, content = text),
                ChatMessage(role = MessageRole.ASSISTANT, content = previousReply),
                ChatMessage(role = MessageRole.USER, content = text),
            )
            for (calendar in listOf(true, false)) {
                val system = ChatMessage(role = MessageRole.SYSTEM, content = provider.getSystemPrompt(calendar))
                val messages = listOf(system) + builder.build(history,
                    appendChatStyleInstruction = !calendar, isCalendarMode = calendar)
                val inspection = NativeLlamaRuntime.inspectPrompt(path, engine.buildPrompt(messages), text)
                val plan = runCatching { ContextWindowPolicy.plan(inspection.tokenCount, GenerationParams(),
                    inspection.modelContextLimit, inspection.userMessageTokens) }
                val decision = plan.fold(onSuccess = {
                    "answer=${it.responseTokens} reserve=${it.reserveTokens} required=${it.requiredTokens} context=${it.contextSize}"
                }, onFailure = {
                    assertTrue(it is ContextCapacityException)
                    val failure = it as ContextCapacityException
                    "rejected required=${failure.required} limit=${failure.limit}"
                })
                Log.i("AutomaticContextTest", "measurement sample=$name calendar=$calendar chars=${text.length} " +
                    "message=${inspection.userMessageTokens} prompt=${inspection.tokenCount} $decision")
            }
        }
    }
}
