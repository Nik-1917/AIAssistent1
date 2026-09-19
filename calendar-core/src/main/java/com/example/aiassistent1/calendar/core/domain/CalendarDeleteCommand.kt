package com.example.aiassistent1.calendar.core.domain

/** Delete targets may be incomplete: the app then offers matching events for selection. */
data class CalendarDeleteTarget(
    val query: String? = null,
    val range: CalendarRange? = null,
    val useLastCreated: Boolean = false,
    val useLastInRange: Boolean = false,
) {
    init {
        require(query == null || query.isNotBlank()) { "Название события не может быть пустым" }
        require(listOf(query != null, useLastCreated, useLastInRange).count { it } <= 1) {
            "Конфликт способов выбора события"
        }
        require(!useLastCreated || range == null) { "Последнее созданное событие не допускает период" }
        require(!useLastInRange || range != null) { "Укажите период" }
    }
}

data class CalendarDeleteCommand(
    val target: CalendarDeleteTarget,
)
