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
            "cегодня $currentDateTime день недели $dayOfWeek ответ JSON\n$CALENDAR_CONTRACT"
        } else {
            "cегодня $currentDateTime день недели $dayOfWeek"
        }
    }

    companion object {
        // Canonical output only; parser aliases remain available for older model responses.
        internal val CALENDAR_CONTRACT = """
            Верни один JSON: {"intent":"команда","reply":"ответ пользователю","params":{}}. Без Markdown.
            Допустимые поля params по intent; неизвестные значения пропускай, не пиши null:
            chat: {}
            calendar_add: {title,starts_at,ends_at,date,time,duration_min,value,notes}
            calendar_search: {query,range_start,range_end}
            calendar_sum: {query,range_start,range_end}
            calendar_update: {target:{query,range_start,range_end,use_last_created},changes:{title,date,time,duration_min,value,clear_value,notes}}
            calendar_delete: {target:{query,range_start,range_end,use_last_created,use_last_in_range}}
            calendar_delete_range: {start,end}
            Удалить ВСЕ события периода: calendar_delete_range с start/end. Удалить последнее событие периода: calendar_delete с target.use_last_in_range:true. Это разные действия.
            Это списки разрешённых ключей, не готовый JSON. Другие ключи не добавляй.
            title,notes,query,reply — строки. date: YYYY-MM-DD; time: HH:mm; starts_at,ends_at,range_start,range_end,start,end: YYYY-MM-DDTHH:mm, строки местного времени.
            duration_min — целое положительное число минут, value — целое число. notes — текст заметки. Время начала 12:15 — time или starts_at, не длительность. Часы длительности переводи в минуты.
            При полной дате и времени начала используй starts_at; иначе известные date/time. Если известен конец, используй ends_at; не выдумывай длительность.
            range_start/range_end указывай вместе, начало раньше конца, конец не включается. Для calendar_delete_range start и end обязательны, start раньше end.
            query — название для поиска; для calendar_search всех событий query="". Флаги только true, иначе пропускай.
            В calendar_search, calendar_sum и target сохраняй указанный пользователем период в range_start/range_end. Конкретная дата означает от 00:00 этого дня до 00:00 следующего.
            target: либо query (если задана дата, обязательно добавь период), либо use_last_created:true без query и периода. Только при удалении: use_last_in_range:true с периодом без query.
            changes содержит только запрошенные изменения; clear_value:true удаляет значение и несовместим с value. При удалении changes не нужен.
            reply — непустой краткий ответ, время должно совпадать с params. Не утверждай, что событие уже сохранено.
        """.trimIndent()
    }
}
