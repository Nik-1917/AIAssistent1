package com.example.aiassistent1.domain.model

import com.example.aiassistent1.calendar.core.domain.CalendarTimeMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

enum class CalendarCreationMode(val timeMode: CalendarTimeMode) {
    FIXED_MOMENT(CalendarTimeMode.FIXED),
    LOCAL_TIME(CalendarTimeMode.FLOATING),
    PHONE_TIME(CalendarTimeMode.FIXED),
    HOME_TIME(CalendarTimeMode.FIXED);

    companion object {
        fun fromLegacyHomeZone(homeZoneId: String?) = if (homeZoneId == null) PHONE_TIME else HOME_TIME
    }
}

/** Calendar policy only. No event timestamps or model protocol fields belong here. */
data class CalendarTimeZoneState(
    val deviceZoneId: String = ZoneId.systemDefault().id,
    val selectedZoneId: String? = null,
    val previousDeviceZoneId: String? = null,
    val hasDetectedChange: Boolean = false,
    val changePending: Boolean = false,
    val isLoaded: Boolean = true,
    val error: String? = null,
    /** Retained when another creation mode is active. */
    val homeZoneId: String? = null,
    val creationMode: CalendarCreationMode = CalendarCreationMode.fromLegacyHomeZone(homeZoneId),
) {
    init {
        require(creationMode != CalendarCreationMode.HOME_TIME || homeZoneId != null) { "Выберите домашний часовой пояс." }
    }

    val zoneId: ZoneId get() = ZoneId.of(selectedZoneId ?: deviceZoneId)
    val inputZoneId: ZoneId get() = when (creationMode) {
        CalendarCreationMode.FIXED_MOMENT, CalendarCreationMode.LOCAL_TIME -> zoneId
        CalendarCreationMode.PHONE_TIME -> ZoneId.of(deviceZoneId)
        CalendarCreationMode.HOME_TIME -> ZoneId.of(requireNotNull(homeZoneId))
    }
    val showControl: Boolean get() = hasDetectedChange || selectedZoneId != null || error != null

    fun timeContext() = CalendarTimeContext(zoneId, inputZoneId = inputZoneId, timeMode = creationMode.timeMode)

    fun withHomeZone(zone: String?): CalendarTimeZoneState {
        zone?.let(ZoneId::of)
        return copy(homeZoneId = zone, creationMode = CalendarCreationMode.fromLegacyHomeZone(zone))
    }

    fun deviceChangedTo(zone: ZoneId): CalendarTimeZoneState =
        if (zone.id == deviceZoneId) this else copy(
            deviceZoneId = zone.id, previousDeviceZoneId = deviceZoneId,
            hasDetectedChange = true, changePending = true,
        )
}

/** One immutable temporal context for prompt date, parsing, mapping and execution. */
data class CalendarTimeContext(
    val zoneId: ZoneId,
    val instant: Instant = Instant.now(),
    val inputZoneId: ZoneId = zoneId,
    val timeMode: CalendarTimeMode = CalendarTimeMode.FIXED,
) {
    val localDateTime: LocalDateTime get() = LocalDateTime.ofInstant(instant, zoneId)
    val inputLocalDateTime: LocalDateTime get() = LocalDateTime.ofInstant(instant, inputZoneId)
}
