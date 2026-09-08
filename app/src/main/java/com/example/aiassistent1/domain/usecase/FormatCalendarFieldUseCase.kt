package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import org.json.JSONObject

/**
 * Use case for formatting a single calendar event field using LLM.
 * It ensures that only the minimal required context is sent to the model,
 * preventing any leakage of chat history into the formatting prompt.
 */
class FormatCalendarFieldUseCase(
    private val llmEngine: LLMEngine,
) {
    suspend operator fun invoke(
        modelName: String,
        expectedFormat: String,
        rawValue: String
    ): Result<String> = runCatching {
        llmEngine.ensureLoaded().getOrThrow()
        
        val request = ChatMessage(
            role = MessageRole.USER,
            content = """
                {"intent":"calendar_field_format","field":"$modelName","value":${JSONObject.quote(rawValue)},"expected_format":"$expectedFormat","instruction":"Format only the requested field. Return JSON with exactly field and value. Do not change other event data."}
            """.trimIndent(),
        )
        
        val messages = listOf(
            ChatMessage(
                role = MessageRole.SYSTEM,
                content = "Return only valid JSON. Never create, search, or modify calendar events.",
            ),
            request,
        )

        var response = ""
        llmEngine.generate(messages).collect { response += it }

        parseResponse(modelName, response)
    }

    private fun parseResponse(modelName: String, response: String): String {
        val jsonStart = response.indexOf('{')
        val jsonEnd = response.lastIndexOf('}')
        require(jsonStart >= 0 && jsonEnd > jsonStart) { "Модель не вернула JSON для форматирования поля." }
        
        val json = JSONObject(response.substring(jsonStart, jsonEnd + 1))
        require(json.optString("field") == modelName) { "Модель вернула другое поле." }
        
        return if (modelName == "duration_min") {
            json.optInt("value", 0).toString()
        } else {
            json.optString("value")
        }
    }
}
