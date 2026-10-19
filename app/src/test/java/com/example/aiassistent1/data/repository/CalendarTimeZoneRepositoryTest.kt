package com.example.aiassistent1.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class CalendarTimeZoneRepositoryTest {
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
