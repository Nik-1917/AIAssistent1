package com.example.aiassistent1.domain.provider

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SystemPromptProvider {
    fun getSystemPrompt(): String {
        val currentDateTime = SimpleDateFormat("yyyy-MM-dd (EEEE) HH:mm", Locale.getDefault()).format(Date())
        val timeZone = TimeZone.getDefault().id
        return """Сегодня дата и время:$currentDateTime""".trimIndent()
    }
}
