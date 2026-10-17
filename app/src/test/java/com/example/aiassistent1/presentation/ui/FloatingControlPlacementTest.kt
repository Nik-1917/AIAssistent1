package com.example.aiassistent1.presentation.ui

import com.example.aiassistent1.domain.model.FloatingControlPositions
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingControlPlacementTest {
    @Test
    fun `fresh positions match the approved phone screenshot`() {
        val positions = FloatingControlPositions()
        val density = 2.75f
        val headerBottomPx = 266
        assertEquals(395, headerBottomPx + (positions.calendarButtonYdp * density).roundToInt())
        assertEquals(597, headerBottomPx + (positions.speechCardYdp * density).roundToInt())
        assertEquals(883, 1080 - 182 + (positions.calendarButtonXdp * density).roundToInt())
        assertEquals(884, 1080 - 14 - 182 + (positions.speechCardXdp * density).roundToInt())
    }

    @Test
    fun `legacy phone positions migrate without moving either square`() {
        val legacy = FloatingControlPositions(
            speechCardXdp = 0f,
            speechCardYdp = -138.489807f,
            calendarButtonXdp = -5.545410f,
            calendarButtonYdp = -141.035110f,
            isRelativeToHeader = false,
        )
        val migrated = anchorFloatingControlsToHeader(legacy, containerHeightPx = 2134, density = 2.75f)
        assertTrue(migrated.isRelativeToHeader)
        assertEquals(129, (migrated.calendarButtonYdp * 2.75f).roundToInt())
        assertEquals(331, (migrated.speechCardYdp * 2.75f).roundToInt())
        assertEquals(legacy.calendarButtonXdp, migrated.calendarButtonXdp)
        assertEquals(legacy.speechCardXdp, migrated.speechCardXdp)
        assertSame(migrated, anchorFloatingControlsToHeader(migrated, 1200, 2.75f))
    }

    @Test
    fun `anchored positions stay fixed when available height changes`() {
        val positions = FloatingControlPositions(speechCardYdp = 180f, calendarButtonYdp = 90f)
        for (height in listOf(720, 1200, 2134)) {
            assertSame(positions, anchorFloatingControlsToHeader(positions, height, density = 2.75f))
        }
    }
}
