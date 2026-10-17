package com.example.aiassistent1

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.di.AppModule
import com.example.aiassistent1.domain.model.AppDestination
import com.example.aiassistent1.domain.model.FloatingControlPositions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses MotionEvents in the application's window, without global input injection. */
@RunWith(AndroidJUnit4::class)
class FloatingControlsGestureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity

    @Test(timeout = 120_000)
    fun quickDragPushesOnFirstContactInBothDirectionsAndKeepsTapAndSavedPositions() {
        val context = instrumentation.targetContext
        val settings = AppModule.provideSettingsRepository(context)
        // An explicit device-QA option, used only when its owner requests this placement.
        val positionsToRestore = if (InstrumentationRegistry.getArguments().getString("restoreFloatingControlDefaults") == "true") {
            FloatingControlPositions()
        } else {
            runBlocking { settings.floatingControlPositions.first() }
        }
        val navigationToRestore = runBlocking { settings.navigationState.first() }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            runBlocking {
                settings.setAppDestination(AppDestination.CHAT)
                settings.setFloatingControlPositions(FloatingControlPositions())
            }
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            prepareWindow(scenario)
            val calendarStart = calendarBounds()
            val density = context.resources.displayMetrics.density
            val defaults = FloatingControlPositions()
            var headerBottom = 0
            instrumentation.runOnMainSync {
                headerBottom = activity.window.decorView.rootWindowInsets
                    .getInsets(android.view.WindowInsets.Type.statusBars()).top + (64f * density).toInt()
            }
            assertEquals(headerBottom + 47f * density, calendarStart.top.toFloat(), 1f)

            drag(calendarStart.exactCenterX(), calendarStart.exactCenterY(), 70f * density)
            waitFor {
                val positions = runBlocking { settings.floatingControlPositions.first() }
                positions.calendarButtonYdp > defaults.calendarButtonYdp + 30f &&
                    positions.speechCardYdp > defaults.speechCardYdp + 30f
            }
            val afterDown = runBlocking { settings.floatingControlPositions.first() }
            assertTrue(afterDown.speechCardYdp - afterDown.calendarButtonYdp >= 71f - 1f / density)

            val calendarAfterDown = calendarBounds()
            val speechCenterY = calendarAfterDown.exactCenterY() +
                (afterDown.speechCardYdp - afterDown.calendarButtonYdp) * density
            drag(calendarAfterDown.exactCenterX(), speechCenterY, -90f * density)
            waitFor { runBlocking { settings.floatingControlPositions.first() }.calendarButtonYdp < 40f }
            val afterUp = runBlocking { settings.floatingControlPositions.first() }
            assertTrue(afterUp.calendarButtonYdp < afterDown.calendarButtonYdp - 30f)
            assertTrue(afterUp.speechCardYdp < afterDown.speechCardYdp - 30f)
            assertTrue(afterUp.speechCardYdp - afterUp.calendarButtonYdp >= 71f - 1f / density)

            val beforeRecreation = calendarBounds()
            scenario.recreate()
            prepareWindow(scenario)
            assertEquals(beforeRecreation, calendarBounds())
            assertEquals(afterUp, runBlocking { settings.floatingControlPositions.first() })

            val calendar = calendarBounds()
            tap(calendar.exactCenterX(), calendar.exactCenterY())
            waitFor { runBlocking { settings.navigationState.first() }.destination == AppDestination.CALENDAR }
            waitFor { findNode(instrumentation.uiAutomation.rootInActiveWindow, "Вернуться в чат") != null }
        } finally {
            runBlocking {
                settings.setFloatingControlPositions(positionsToRestore)
                settings.setSystemPromptEnabled(navigationToRestore.isCalendarMode)
                settings.setAppDestination(navigationToRestore.destination)
            }
            scenario?.close()
        }
    }

    private fun prepareWindow(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            activity = it
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            it.setTurnScreenOn(true)
        }
    }

    private fun calendarBounds(): Rect {
        var bounds: Rect? = null
        waitFor {
            val node = findNode(instrumentation.uiAutomation.rootInActiveWindow, "Открыть календарь")
            if (node != null && node.isVisibleToUser) {
                bounds = Rect().also(node::getBoundsInScreen)
            }
            bounds != null
        }
        return checkNotNull(bounds)
    }

    private fun findNode(node: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == description) return node
        for (index in 0 until node.childCount) {
            findNode(node.getChild(index), description)?.let { return it }
        }
        return null
    }

    private fun drag(x: Float, y: Float, distanceY: Float) {
        val downTime = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, x, y, downTime)
        for (step in 1..10) {
            SystemClock.sleep(16)
            dispatch(MotionEvent.ACTION_MOVE, x, y + distanceY * step / 10f, downTime)
        }
        dispatch(MotionEvent.ACTION_UP, x, y + distanceY, downTime)
    }

    private fun tap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, x, y, downTime)
        SystemClock.sleep(50)
        dispatch(MotionEvent.ACTION_UP, x, y, downTime)
    }

    private fun dispatch(action: Int, x: Float, y: Float, downTime: Long) {
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            val location = IntArray(2)
            decor.getLocationOnScreen(location)
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x - location[0], y - location[1], 0)
            try {
                decor.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Floating control condition was not reached in 15 seconds" }
            SystemClock.sleep(50)
        }
    }
}
