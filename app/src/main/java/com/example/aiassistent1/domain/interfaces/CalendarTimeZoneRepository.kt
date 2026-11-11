package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.CalendarCreationMode
import com.example.aiassistent1.domain.model.CalendarTimeContext
import com.example.aiassistent1.domain.model.CalendarTimeZoneState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.time.ZoneId

interface CalendarTimeZoneRepository {
    val state: StateFlow<CalendarTimeZoneState>
    suspend fun refreshDeviceZone()
    suspend fun selectZone(zoneId: String?)
    suspend fun selectHomeZone(zoneId: String?)
    suspend fun selectCreationMode(mode: CalendarCreationMode)

    suspend fun snapshot(): CalendarTimeContext {
        refreshDeviceZone()
        val ready = state.first { it.isLoaded }
        check(ready.error == null) { ready.error.orEmpty() }
        return ready.timeContext()
    }
}

/** Default for standalone consumers/tests; production uses the shared persistent repository. */
class SystemCalendarTimeZoneRepository : CalendarTimeZoneRepository {
    private val mutableState = MutableStateFlow(CalendarTimeZoneState())
    override val state = mutableState.asStateFlow()
    override suspend fun refreshDeviceZone() {
        mutableState.update { it.deviceChangedTo(ZoneId.systemDefault()) }
    }
    override suspend fun selectZone(zoneId: String?) {
        zoneId?.let(ZoneId::of)
        mutableState.update { it.copy(selectedZoneId = zoneId, changePending = false) }
    }
    override suspend fun selectHomeZone(zoneId: String?) {
        mutableState.update { it.withHomeZone(zoneId) }
    }
    override suspend fun selectCreationMode(mode: CalendarCreationMode) {
        mutableState.update { it.copy(creationMode = mode) }
    }
}
