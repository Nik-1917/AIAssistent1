package com.example.aiassistent1.domain.provider

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SystemPromptProvider {
    fun getSystemPrompt(): String {
        val currentDateTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault()).format(Date())
        return """
            ### СТРУКТУРА JSON:
            {
              "intent": "calendar_search" | "calendar_add",
              "reply": "Текст события",
              "params": {}
            }
            ### ИНТЕНТЫ И ПАРАМЕТРЫ:
            1. calendar_search (поиск):
               - "query": (string) ключевое слово или имя.
               - "days": (number) глубина поиска в днях (по умолчанию 7, если не указано иное).
            2. calendar_add (создание):
               - "title": (string) название события.
               - "date": (string) формат YYYY-MM-DDTHH:MM.
               - "duration_min": (number) длительность в минутах. ВСЕГДА 60, если пользователь не указал конкретную продолжительность.
               
            возвращай строго отформатированный JSON
            ### ПРАВИЛА ОТВЕТА:
            1. Используй только указанный JSON-формат. Никакого лишнего текста до или после JSON.
            2. Сегодня дата и время: $currentDateTime.
        """.trimIndent()
    }
}
