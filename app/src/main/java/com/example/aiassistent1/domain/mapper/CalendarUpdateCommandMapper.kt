package com.example.aiassistent1.domain.mapper

import com.example.aiassistent1.calendar.core.domain.CalendarCommand
import com.example.aiassistent1.calendar.core.domain.CalendarUpdateCommand
import com.example.aiassistent1.domain.model.CalendarUpdateParams
import java.time.ZoneId

/** Compatibility facade; all transport mapping uses the same V12.4 mapper. */
class CalendarUpdateCommandMapper(zoneId: ZoneId = ZoneId.systemDefault()) {
    private val mapper = CalendarCommandMapper(zoneId)
    fun map(params: CalendarUpdateParams): Result<CalendarUpdateCommand> = runCatching {
        val target = params.target.copy(
            useLastCreated = if (params.target.query != null) false else params.target.useLastCreated ||
                (params.target.rangeStart == null && params.target.rangeEnd == null && !params.target.useLastReferenced),
        )
        val command = mapper.map(params.copy(target = target)).getOrThrow() as CalendarCommand.Update
        CalendarUpdateCommand(requireNotNull(command.target) { "Укажите событие для изменения" }, command.changes)
    }
}
