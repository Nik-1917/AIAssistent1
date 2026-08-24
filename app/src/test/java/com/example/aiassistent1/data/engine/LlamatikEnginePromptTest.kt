package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class LlamatikEnginePromptTest {
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
