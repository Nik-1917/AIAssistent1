package com.example.aiassistent1.domain.provider

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SystemPromptProvider {
    fun getSystemPrompt(isCalendarMode: Boolean): String {
        val locale = Locale.forLanguageTag("ru-RU")
        val now = Date()
        val currentDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", locale).format(now)
        val dayOfWeek = SimpleDateFormat("EEEE", locale).format(now)
        
        return if (isCalendarMode) {
            "cегодня $currentDateTime день недели $dayOfWeek ответ JSON"
        } else {
            "cегодня $currentDateTime день недели $dayOfWeek Ты - полезный ИИ ассистент."
        }
    }
}
