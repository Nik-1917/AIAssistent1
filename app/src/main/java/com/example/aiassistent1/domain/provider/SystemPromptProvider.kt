package com.example.aiassistent1.domain.provider

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SystemPromptProvider {
    fun getSystemPrompt(): String {
        val locale = Locale.forLanguageTag("ru-RU")
        val now = Date()
        val currentDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", locale).format(now)
        val dayOfWeek = SimpleDateFormat("EEEE", locale).format(now)
        val timeZone = TimeZone.getDefault().id
        return "Сегодня: $currentDateTime. День недели, сегодня: $dayOfWeek. ответ JSON"
    }
}
