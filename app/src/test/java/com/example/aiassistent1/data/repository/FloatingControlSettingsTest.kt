package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import com.example.aiassistent1.domain.model.FloatingControlPositions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingControlSettingsTest {
    @Test
    fun `empty settings use approved defaults instead of zero offsets`() = runTest {
        val repository = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        assertEquals(FloatingControlPositions(), repository.floatingControlPositions.first())
    }

    @Test
    fun `saved legacy coordinates are marked for migration and retain missing zero defaults`() = runTest {
        val store = TestPreferencesDataStore(preferencesOf(
            floatPreferencesKey("speech_card_y_dp") to -138.489807f,
            floatPreferencesKey("calendar_button_x_dp") to -5.545410f,
            floatPreferencesKey("calendar_button_y_dp") to -141.035110f,
        ))
        val positions = DataStoreSettingsRepository(store, backgroundScope).floatingControlPositions.first()
        assertFalse(positions.isRelativeToHeader)
        assertEquals(0f, positions.speechCardXdp)
        assertEquals(-138.489807f, positions.speechCardYdp)
        assertEquals(-5.545410f, positions.calendarButtonXdp)
        assertEquals(-141.035110f, positions.calendarButtonYdp)
    }

    @Test
    fun `reopened repository restores moved positions with their new anchor`() = runTest {
        val store = TestPreferencesDataStore()
        val moved = FloatingControlPositions(-90f, 200f, -120f, 300f)
        DataStoreSettingsRepository(store, backgroundScope).setFloatingControlPositions(moved)
        val restored = DataStoreSettingsRepository(store, backgroundScope).floatingControlPositions.first()
        assertEquals(moved, restored)
        assertTrue(restored.isRelativeToHeader)
    }
}
