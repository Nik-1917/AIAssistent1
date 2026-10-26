package com.example.aiassistent1.domain.provider

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class SystemPromptProvider {
    fun getSystemPrompt(isCalendarMode: Boolean, localDateTime: LocalDateTime = LocalDateTime.now(),
        creationLocalDateTime: LocalDateTime? = null): String {
        val locale = Locale.forLanguageTag("ru-RU")
        // Only local date/time reaches the model, never a zone ID or UTC offset.
        val currentDateTime = localDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", locale))
        val dayOfWeek = localDateTime.format(DateTimeFormatter.ofPattern("EEEE", locale))
        
        return if (isCalendarMode) {
            val creationContext = creationLocalDateTime?.takeIf { it != localDateTime }?.let {
                "Для calendar_add сейчас ${it.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", locale))} день недели ${it.format(DateTimeFormatter.ofPattern("EEEE", locale))}. " +
                    "Для calendar_search и calendar_sum используй дату и время первой строки.\n"
            }.orEmpty()
            "cегодня $currentDateTime день недели $dayOfWeek ответ JSON\n$creationContext$CALENDAR_CONTRACT"
        } else {
            "cегодня $currentDateTime день недели $dayOfWeek"
        }
    }

    companion object {
        // V12.67: one shared contract for the four supported assistant actions.
        internal val CALENDAR_CONTRACT = """
            Верни один JSON: {"intent":"команда","reply":"ответ пользователю","params":{}}. Без Markdown.
            Допустимые поля params по intent; неизвестные значения пропускай, не пиши null:
            chat: {}
            calendar_add: {title,starts_at,ends_at,date,time,duration_min,value,notes}
            calendar_search: {query,range_start,range_end}
            calendar_sum: {query,range_start,range_end}
            Это списки разрешённых ключей, не готовый JSON. Другие ключи не добавляй.
            title,notes,query,reply — строки. date: YYYY-MM-DD; time: HH:mm; starts_at,ends_at,range_start,range_end: YYYY-MM-DDTHH:mm, строки местного времени.
            duration_min — целое положительное число минут, value — целое число. notes — текст заметки.
            Для остальных intent reply — непустой краткий ответ, согласованный с params. Не утверждай, что событие уже сохранено.
        """.trimIndent()
    }
}
