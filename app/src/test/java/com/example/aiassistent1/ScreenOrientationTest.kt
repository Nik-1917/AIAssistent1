package com.example.aiassistent1

import android.content.pm.ActivityInfo
import com.example.aiassistent1.domain.model.ScreenOrientation
import com.example.aiassistent1.domain.model.ScreenPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenOrientationTest {
    @Test
    fun `unlocking returns orientation to the system regardless of the saved choice`() {
        for (orientation in ScreenOrientation.entries) {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                ScreenPreferences(false, orientation).toRequestedOrientation())
        }
    }

    @Test
    fun `locking requests only the selected fixed orientation`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ScreenPreferences(true, ScreenOrientation.PORTRAIT).toRequestedOrientation())
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ScreenPreferences(true, ScreenOrientation.LANDSCAPE).toRequestedOrientation())
    }
}
