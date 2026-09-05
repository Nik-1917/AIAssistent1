package com.example.aiassistent1.domain.provider

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SystemPromptProvider {
    fun getSystemPrompt(): String {
        val locale = Locale.forLanguageTag("ru-RU")
        val currentDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", locale).format(Date())
        val dayOfWeek = SimpleDateFormat("EEEE", locale).format(Date())
        val timeZone = TimeZone.getDefault().id
        return "Сегодня дата и время: $currentDateTime. День недели сегодня: $dayOfWeek. Часовой пояс: $timeZone. При вопросе о том, какой сегодня день недели, используй это значение. ответ JSON"
    }
}
