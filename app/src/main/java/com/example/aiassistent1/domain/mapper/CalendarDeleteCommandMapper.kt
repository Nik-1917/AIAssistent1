package com.example.aiassistent1.domain.mapper

import com.example.aiassistent1.calendar.core.domain.CalendarCommand
import com.example.aiassistent1.calendar.core.domain.CalendarDeleteCommand
import com.example.aiassistent1.domain.model.CalendarDeleteParams
import java.time.ZoneId

class CalendarDeleteCommandMapper(zoneId: ZoneId = ZoneId.systemDefault()) {
    private val mapper = CalendarCommandMapper(zoneId)
    fun map(params: CalendarDeleteParams): Result<CalendarDeleteCommand> = mapper.map(params).mapCatching {
        CalendarDeleteCommand(requireNotNull((it as CalendarCommand.Delete).target) { "Укажите событие для удаления" })
    }
}
