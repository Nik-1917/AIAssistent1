package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.aiassistent1.calendar.core.domain.CalendarTimeMode
import com.example.aiassistent1.domain.model.CalendarCreationMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class CalendarTimeZoneRepositoryTest {
    @Test fun legacyHomeBecomesActiveAndIsRememberedAcrossOtherModes() = runTest {
        val store = TestPreferencesDataStore(preferencesOf(stringPreferencesKey("home_input_zone_id") to "Europe/Samara"))
        val moscow = ZoneId.of("Europe/Moscow")
        val first = DataStoreCalendarTimeZoneRepository(store, backgroundScope) { moscow }
        assertEquals("Europe/Samara", first.snapshot().inputZoneId.id)
        assertEquals(CalendarCreationMode.HOME_TIME, first.state.value.creationMode)

        first.selectCreationMode(CalendarCreationMode.PHONE_TIME)
        val reopened = DataStoreCalendarTimeZoneRepository(store, backgroundScope) { moscow }
        assertEquals(moscow, reopened.snapshot().inputZoneId)
        assertEquals(CalendarCreationMode.PHONE_TIME, reopened.state.value.creationMode)
        assertEquals("Europe/Samara", reopened.state.value.homeZoneId)
        reopened.selectCreationMode(CalendarCreationMode.HOME_TIME)
        assertEquals("Europe/Samara", reopened.snapshot().inputZoneId.id)
    }

    @Test fun creationModesCaptureOnlyTheirOwnZoneAndTimeRule() = runTest {
        val moscow = ZoneId.of("Europe/Moscow")
        val samara = ZoneId.of("Europe/Samara")
        val berlin = ZoneId.of("Europe/Berlin")
        var phone = moscow
        val store = TestPreferencesDataStore()
        val zones = DataStoreCalendarTimeZoneRepository(store, backgroundScope) { phone }
        zones.selectZone(samara.id)
        assertEquals(moscow, zones.snapshot().inputZoneId)
        assertEquals(CalendarCreationMode.PHONE_TIME, zones.state.value.creationMode)
        zones.selectHomeZone(berlin.id)
        val expected = mapOf(
            CalendarCreationMode.FIXED_MOMENT to (samara to CalendarTimeMode.FIXED),
            CalendarCreationMode.LOCAL_TIME to (samara to CalendarTimeMode.FLOATING),
            CalendarCreationMode.PHONE_TIME to (moscow to CalendarTimeMode.FIXED),
            CalendarCreationMode.HOME_TIME to (berlin to CalendarTimeMode.FIXED),
        )
        for ((mode, rule) in expected) {
            zones.selectCreationMode(mode)
            val restored = DataStoreCalendarTimeZoneRepository(store, backgroundScope) { phone }
            val context = restored.snapshot()
            assertEquals(mode, restored.state.value.creationMode)
            assertEquals(rule, context.inputZoneId to context.timeMode)
            assertEquals(samara, context.zoneId)
        }
        val openHome = zones.snapshot()
        phone = ZoneId.of("Asia/Tokyo")
        zones.selectCreationMode(CalendarCreationMode.PHONE_TIME)
        assertEquals(phone, zones.snapshot().inputZoneId)
        assertEquals(berlin, openHome.inputZoneId)
    }

    @Test fun `input defaults to phone independently of a fixed calendar zone`() = runTest {
        var device = ZoneId.of("Europe/Samara")
        val repository = DataStoreCalendarTimeZoneRepository(TestPreferencesDataStore(), backgroundScope) { device }
        repository.selectZone("Europe/Moscow")
        val captured = repository.snapshot()
        assertEquals(device, captured.inputZoneId)
        assertEquals(ZoneId.of("Europe/Moscow"), captured.zoneId)
        device = ZoneId.of("Asia/Tokyo")
        assertEquals(device, repository.snapshot().inputZoneId)
        assertEquals(ZoneId.of("Europe/Samara"), captured.inputZoneId)
        assertEquals(ZoneId.of("Europe/Moscow"), repository.state.value.zoneId)
    }

    @Test fun `home selection survives restart and travel and can return to phone time`() = runTest {
        val store = TestPreferencesDataStore()
        var device = ZoneId.of("Europe/Samara")
        val first = DataStoreCalendarTimeZoneRepository(store, backgroundScope) { device }
        first.selectZone("Europe/Moscow")
        first.selectHomeZone("Europe/Berlin")
        device = ZoneId.of("Asia/Tokyo")
        val reopened = DataStoreCalendarTimeZoneRepository(store, backgroundScope) { device }
        assertEquals(ZoneId.of("Europe/Berlin"), reopened.snapshot().inputZoneId)
        assertEquals(ZoneId.of("Europe/Moscow"), reopened.state.value.zoneId)
        assertTrue(reopened.state.value.changePending)
        reopened.selectHomeZone(null)
        val again = DataStoreCalendarTimeZoneRepository(store, backgroundScope) { device }
        assertEquals(device, again.snapshot().inputZoneId)
        assertNull(again.state.value.homeZoneId)
        assertEquals(ZoneId.of("Europe/Moscow"), again.state.value.zoneId)
    }

    @Test fun `a failed home write leaves both persisted zones unchanged`() = runTest {
        val delegate = TestPreferencesDataStore()
        var fail = false
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> by delegate {
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                if (fail) throw java.io.IOException("test write failure")
                return delegate.updateData(transform)
            }
        }
        val repository = DataStoreCalendarTimeZoneRepository(store, backgroundScope) { ZoneId.of("Europe/Samara") }
        repository.selectZone("Europe/Moscow")
        fail = true
        repository.selectHomeZone("Europe/Berlin")
        assertNotNull(repository.state.value.error)
        assertNull(repository.state.value.homeZoneId)
        assertEquals(ZoneId.of("Europe/Samara"), repository.state.value.inputZoneId)
        val reopened = DataStoreCalendarTimeZoneRepository(delegate, backgroundScope) { ZoneId.of("Europe/Samara") }
        assertEquals(ZoneId.of("Europe/Samara"), reopened.snapshot().inputZoneId)
        assertEquals(ZoneId.of("Europe/Moscow"), reopened.state.value.zoneId)
    }

    @Test fun `a failed settings write never publishes an unpersisted selection and retry recovers`() = runTest {
        val delegate = TestPreferencesDataStore()
        var fail = false
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> by delegate {
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                if (fail) throw java.io.IOException("test write failure")
                return delegate.updateData(transform)
            }
        }
        val repository = DataStoreCalendarTimeZoneRepository(store, backgroundScope) { ZoneId.of("Europe/Samara") }
        repository.refreshDeviceZone()
        fail = true
        repository.selectZone("Europe/Moscow")
        assertNull(repository.state.value.selectedZoneId)
        assertEquals(ZoneId.of("Europe/Samara"), repository.state.value.zoneId)
        assertNotNull(repository.state.value.error)
        fail = false
        repository.selectZone("Europe/Moscow")
        assertEquals(ZoneId.of("Europe/Moscow"), repository.state.value.zoneId)
        assertNull(repository.state.value.error)
    }

    @Test fun `first launch follows phone and change exposes selector without selecting a fixed zone`() = runTest {
        var device = ZoneId.of("Europe/Samara")
        val repository = DataStoreCalendarTimeZoneRepository(TestPreferencesDataStore(), backgroundScope) { device }
        repository.refreshDeviceZone()
        assertEquals(device, repository.snapshot().zoneId)
        assertFalse(repository.state.value.showControl)

        device = ZoneId.of("Europe/Moscow")
        repository.refreshDeviceZone()
        assertEquals(device, repository.state.value.zoneId)
        assertEquals("Europe/Samara", repository.state.value.previousDeviceZoneId)
        assertTrue(repository.state.value.showControl)
        assertTrue(repository.state.value.changePending)
        assertNull(repository.state.value.selectedZoneId)
    }

    @Test fun `manual choice and unread device change survive restart and returning to automatic works`() = runTest {
        val store = TestPreferencesDataStore()
        var device = ZoneId.of("Europe/Samara")
        val first = DataStoreCalendarTimeZoneRepository(store, backgroundScope) { device }
        first.refreshDeviceZone()
        first.selectZone("Asia/Yekaterinburg")
        device = ZoneId.of("Europe/Moscow") // App was closed; no broadcast delivered to it.
        val reopened = DataStoreCalendarTimeZoneRepository(store, backgroundScope) { device }
        reopened.refreshDeviceZone()
        assertEquals(ZoneId.of("Asia/Yekaterinburg"), reopened.state.value.zoneId)
        assertTrue(reopened.state.value.changePending)
        assertEquals("Europe/Samara", reopened.state.value.previousDeviceZoneId)
        reopened.selectZone(null)
        assertEquals(device, reopened.state.value.zoneId)
        assertFalse(reopened.state.value.changePending)
        assertTrue(reopened.state.value.showControl)

        val again = DataStoreCalendarTimeZoneRepository(store, backgroundScope) { device }
        again.refreshDeviceZone()
        assertNull(again.state.value.selectedZoneId)
        assertFalse(again.state.value.changePending)
    }

    @Test fun `repeated observation does not recreate dismissed change and snapshots are immutable`() = runTest {
        var device = ZoneId.of("Europe/Samara")
        val repository = DataStoreCalendarTimeZoneRepository(TestPreferencesDataStore(), backgroundScope) { device }
        val request = repository.snapshot()
        device = ZoneId.of("Europe/Moscow")
        repository.refreshDeviceZone()
        repository.selectZone(null)
        repeat(3) { repository.refreshDeviceZone() }
        assertFalse(repository.state.value.changePending)
        assertEquals(ZoneId.of("Europe/Samara"), request.zoneId)
        assertEquals(ZoneId.of("Europe/Moscow"), repository.snapshot().zoneId)
    }
}
