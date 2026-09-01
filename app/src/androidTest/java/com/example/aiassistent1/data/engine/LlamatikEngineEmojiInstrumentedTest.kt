package com.example.aiassistent1.data.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlamatikEngineEmojiInstrumentedTest {
    @Test
    fun streamsEmojiWithoutSystemPrompt() = runBlocking {
        val modelPath = findImportedModel()?.absolutePath
        assumeTrue("На устройстве нет импортированной .gguf-модели", modelPath != null)
        val selectedModelPath = modelPath ?: return@runBlocking

        val engine = LlamatikEngine(
            modelProvider = object : ModelProvider {
                override suspend fun getModelPath(): Result<String> = Result.success(selectedModelPath)
            },
        )

        try {
            engine.ensureLoaded().getOrThrow()
            val response = withTimeout(GENERATION_TIMEOUT_MILLIS) {
                engine.generate(
                    listOf(
                        ChatMessage(
                            role = MessageRole.USER,
                            content = "Верни ровно этот текст: Привет! 😊",
                        ),
                    ),
                ).toList().joinToString(separator = "")
            }

            assertTrue("Модель не вернула запрошенный emoji: $response", response.contains("😊"))
        } finally {
            engine.close()
        }
    }

    private fun findImportedModel(): File? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getExternalFilesDir("models")
            ?.listFiles()
            ?.firstOrNull { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
    }

    private companion object {
        const val GENERATION_TIMEOUT_MILLIS = 180_000L
    }
}