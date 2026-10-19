package com.example.aiassistent1.domain.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Calendar policy only. No event timestamps or model protocol fields belong here. */
data class CalendarTimeZoneState(
    val deviceZoneId: String = ZoneId.systemDefault().id,
    val selectedZoneId: String? = null,
    val previousDeviceZoneId: String? = null,
    val hasDetectedChange: Boolean = false,
    val changePending: Boolean = false,
    val isLoaded: Boolean = true,
    val error: String? = null,
) {
    val zoneId: ZoneId get() = ZoneId.of(selectedZoneId ?: deviceZoneId)
    val showControl: Boolean get() = hasDetectedChange || selectedZoneId != null || error != null

    fun deviceChangedTo(zone: ZoneId): CalendarTimeZoneState =
        if (zone.id == deviceZoneId) this else copy(
            deviceZoneId = zone.id, previousDeviceZoneId = deviceZoneId,
            hasDetectedChange = true, changePending = true,
        )
}

/** One immutable temporal context for prompt date, parsing, mapping and execution. */
data class CalendarTimeContext(val zoneId: ZoneId, val instant: Instant = Instant.now()) {
    val localDateTime: LocalDateTime get() = LocalDateTime.ofInstant(instant, zoneId)
}
