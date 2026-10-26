package com.example.aiassistent1.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aiassistent1.domain.interfaces.CalendarTimeZoneRepository
import com.example.aiassistent1.domain.model.CalendarTimeZoneState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

private val Context.calendarTimeZoneDataStore by preferencesDataStore("calendar_time_zone")
fun Context.calendarTimeZoneStore(): DataStore<Preferences> = calendarTimeZoneDataStore

class DataStoreCalendarTimeZoneRepository(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
    private val deviceZone: () -> ZoneId = ZoneId::systemDefault,
) : CalendarTimeZoneRepository {
    private val mutableState = MutableStateFlow(CalendarTimeZoneState(deviceZoneId = deviceZone().id, isLoaded = false))
    override val state = mutableState.asStateFlow()
    private val writes = Mutex()
    private var restored = false

    init { scope.launch { refreshDeviceZone() } }

    override suspend fun refreshDeviceZone() = update { it.deviceChangedTo(deviceZone()) }

    override suspend fun selectZone(zoneId: String?) {
        zoneId?.let(ZoneId::of)
        update { it.deviceChangedTo(deviceZone()).copy(selectedZoneId = zoneId, changePending = false) }
    }

    override suspend fun selectHomeZone(zoneId: String?) {
        zoneId?.let(ZoneId::of)
        update { it.deviceChangedTo(deviceZone()).copy(homeZoneId = zoneId) }
    }

    private suspend fun update(change: (CalendarTimeZoneState) -> CalendarTimeZoneState) {
        writes.withLock {
            try {
                val previous = if (restored) state.value else read()
                val next = change(previous).copy(isLoaded = true, error = null)
                // Publish a selection only after it is durable. This never touches calendar Room.
                if (!restored || next != previous.copy(error = null)) write(next)
                restored = true
                mutableState.value = next
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = state.value.copy(isLoaded = true,
                    error = "Не удалось сохранить часовой пояс. Повторите выбор.")
            }
        }
    }

    private suspend fun read(): CalendarTimeZoneState {
        val p = store.data.first()
        fun zone(key: Preferences.Key<String>) = p[key]?.also { ZoneId.of(it) }
        return CalendarTimeZoneState(
            deviceZoneId = zone(DEVICE) ?: deviceZone().id,
            selectedZoneId = zone(SELECTED), previousDeviceZoneId = zone(PREVIOUS),
            hasDetectedChange = p[DETECTED] ?: false, changePending = p[PENDING] ?: false,
            homeZoneId = zone(HOME),
        )
    }

    private suspend fun write(value: CalendarTimeZoneState) {
        store.edit { p ->
            p[DEVICE] = value.deviceZoneId
            value.selectedZoneId?.let { p[SELECTED] = it } ?: p.remove(SELECTED)
            value.previousDeviceZoneId?.let { p[PREVIOUS] = it } ?: p.remove(PREVIOUS)
            p[DETECTED] = value.hasDetectedChange
            p[PENDING] = value.changePending
            value.homeZoneId?.let { p[HOME] = it } ?: p.remove(HOME)
        }
    }

    private companion object {
        val DEVICE = stringPreferencesKey("device_zone_id")
        val SELECTED = stringPreferencesKey("selected_zone_id")
        val PREVIOUS = stringPreferencesKey("previous_device_zone_id")
        val DETECTED = booleanPreferencesKey("change_detected")
        val PENDING = booleanPreferencesKey("change_pending")
        val HOME = stringPreferencesKey("home_input_zone_id")
    }
}
