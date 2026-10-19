package com.example.aiassistent1

import android.graphics.Bitmap
import androidx.room.Room
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.aiassistent1.ui.theme.AIAssistent1Theme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.calendar.storage.android.RoomCalendarEventRepository
import com.example.aiassistent1.calendar.storage.android.local.CalendarDatabase
import com.example.aiassistent1.data.repository.DataStoreCalendarTimeZoneRepository
import com.example.aiassistent1.data.repository.DataStoreSettingsRepository
import com.example.aiassistent1.presentation.ui.CalendarScreen
import com.example.aiassistent1.presentation.ui.CalendarTimeZoneProvider
import com.example.aiassistent1.presentation.ui.UserDateTimeProvider
import com.example.aiassistent1.presentation.viewmodel.CalendarViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class CalendarTimeZoneAppTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingZoneUpdatesActualCalendarAndKeepsEveryStoredEventField() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val samara = ZoneId.of("Europe/Samara")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        fun store() = PreferenceDataStoreFactory.create(scope = scope) {
            File(context.cacheDir, "calendar-zone-${UUID.randomUUID()}.preferences_pb")
        }
        val zones = DataStoreCalendarTimeZoneRepository(store(), scope) { samara }
        val settings = DataStoreSettingsRepository(store(), scope)
        // Actual Room persistence in an isolated database; no production settings, events or models.
        val database = Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).build()
        val events = RoomCalendarEventRepository(database.calendarEventDao())
        val viewModel = CalendarViewModel(ObserveCalendarEventsUseCase(events), CreateCalendarEventUseCase(events),
            UpdateCalendarEventUseCase(events), DeleteCalendarEventUseCase(events), GetCalendarEventUseCase(events), settings, zones)
        val start = LocalDate.now(samara).atTime(15, 0).atZone(samara).toInstant().toEpochMilli()
        val created = runBlocking {
            zones.selectZone(samara.id)
            withContext(Dispatchers.IO) {
                events.create(CalendarEventDraft("Проверка часовых поясов", start, start + 3_600_000L,
                    value = 0, notes = "  Документы\n12500  ")).getOrThrow()
            }
        }
        try {
            runBlocking { viewModel.focusSavedEvent(created.id).getOrThrow() }
            compose.setContent {
                AIAssistent1Theme {
                    CalendarTimeZoneProvider(zones) {
                        UserDateTimeProvider(false, false) {
                            CalendarScreen(viewModel, onNavigateBack = {}, onOpenChat = {})
                        }
                    }
                }
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithText(created.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("15:00–16:00").assertIsDisplayed()
            screenshot("calendar-time-zone-calendar.png")
            compose.onNodeWithTag("calendar_time_zone_button").performClick()
            screenshot("calendar-time-zone-picker-app.png")
            compose.onNodeWithTag("calendar_time_zone_search").performTextInput("Москва")
            compose.onNodeWithTag("calendar_time_zone_option_Europe/Moscow").performClick()
            compose.waitUntil(10_000) { zones.state.value.selectedZoneId == "Europe/Moscow" }
            compose.onNodeWithText("14:00–15:00").assertIsDisplayed()
            val after = runBlocking { withContext(Dispatchers.IO) { events.getById(created.id).getOrThrow() } }
            assertEquals(created, after)
        } finally {
            viewModel.viewModelScope.cancel()
            scope.cancel()
            database.close()
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
