package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.context.ModelContextBuilder
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class LlamatikEnginePromptTest {
    @Test
    fun `chat prompt retains last assistant reply between two recent user messages`() {
        val engine = LlamatikEngine(
            modelProvider = object : ModelProvider {
                override suspend fun getModelPath(): Result<String> = Result.failure(IllegalStateException("unused"))
            },
        )
        val history = listOf(
            ChatMessage(role = MessageRole.USER, content = "Старый вопрос"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Старый ответ"),
            ChatMessage(role = MessageRole.USER, content = "Предыдущий вопрос"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Последний ответ модели"),
            ChatMessage(role = MessageRole.USER, content = "Текущий вопрос"),
        )

        val prompt = engine.buildPrompt(ModelContextBuilder().build(history))

        assertEquals(
            "<|im_start|>user\nПредыдущий вопрос\n<|im_end|>\n" +
                "<|im_start|>assistant\nПоследний ответ модели\n<|im_end|>\n" +
                "<|im_start|>user\nТекущий вопрос\n<|im_end|>\n" +
                "<|im_start|>assistant\n",
            prompt,
        )
    }

    @Test
    fun `renders only supplied system message before the user`() {
        val engine = LlamatikEngine(
            modelProvider = object : ModelProvider {
                override suspend fun getModelPath(): Result<String> = Result.failure(IllegalStateException("unused"))
            },
        )
        val temporalPrompt = "Сегодня дата и время:2027-02-03 (среда) 14:30 Europe/Samara ответ JSON"

        val prompt = engine.buildPrompt(
            listOf(
                ChatMessage(role = MessageRole.SYSTEM, content = temporalPrompt),
                ChatMessage(role = MessageRole.USER, content = "Запиши тренировку на завтра."),
            ),
        )

        assertEquals(
            "<|im_start|>system\n$temporalPrompt\n<|im_end|>\n" +
                "<|im_start|>user\nЗапиши тренировку на завтра.\n<|im_end|>\n" +
                "<|im_start|>assistant\n",
            prompt,
        )
    }
}
