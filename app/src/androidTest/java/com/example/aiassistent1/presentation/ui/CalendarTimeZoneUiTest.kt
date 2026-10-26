package com.example.aiassistent1.presentation.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.repository.DataStoreCalendarTimeZoneRepository
import com.example.aiassistent1.domain.model.CalendarTimeZoneState
import com.example.aiassistent1.presentation.viewmodel.CalendarEventDraftUiState
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class CalendarTimeZoneUiTest {
    @get:Rule val compose = createComposeRule()
    private val scopes = mutableListOf<CoroutineScope>()
    private val samara = ZoneId.of("Europe/Samara")
    private val moscow = ZoneId.of("Europe/Moscow")

    @After fun closeStores() { scopes.forEach { it.cancel() } }

    @Test fun homePickerIsSharedByEditorsAndNeverChangesTheCalendarZone() {
        val repository = repository { samara }
        runBlocking { repository.selectZone(moscow.id) }
        var manual by mutableStateOf(true)
        var draft by mutableStateOf(CalendarEventDraftUiState())
        var result: CalendarEventEditorResult? = null
        compose.setContent { MaterialTheme {
            CalendarTimeZoneProvider(repository) {
                if (manual) CalendarEventEditorDialog(java.time.LocalDate.of(2026, 1, 20), null,
                    onDismiss = {}, onSave = { _, _, _, _, _ -> }, onSaveWithSchedule = {
                        result = it
                        draft = CalendarEventDraftUiState(requestId = "home", title = it.title, date = "2026-01-20", time = "09:00",
                            timeZoneId = moscow.id, inputTimeZoneId = repository.state.value.inputZoneId.id)
                        manual = false
                    })
                else CalendarDraftEditorSheet(draft, onValueChange = { _, _ -> }, onNotesChange = {}, onFormat = {},
                    onVoiceInput = {}, onCreate = {}, onDelete = {}, onDismiss = {},
                    onInputZoneChange = { draft = draft.copy(inputTimeZoneId = it.id) })
            }
        } }
        compose.onNodeWithText("Привычное время").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("Название")).performScrollTo().performTextReplacement("Встреча")
        compose.onNodeWithTag("calendar_time_mode_toggle").performScrollTo().performClick()
        compose.onNodeWithText("По времени телефона").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Домашний пояс: выбрать").performScrollTo().performClick()
        compose.onNodeWithTag("calendar_home_zone_search").performTextInput("Europe/Berlin")
        compose.onNodeWithTag("calendar_home_zone_option_Europe/Berlin").performClick()
        compose.waitUntil(5_000) { repository.state.value.homeZoneId == "Europe/Berlin" }
        compose.onNodeWithText("Сохранить").performClick()
        compose.runOnIdle {
            assertEquals("Europe/Berlin", result!!.inputTimeZoneId)
            assertEquals(moscow, repository.state.value.zoneId)
        }
        compose.onNodeWithText("Привычное время").assertDoesNotExist()
        compose.onNodeWithTag("calendar_time_mode_toggle").performClick()
        compose.onNodeWithText("Домашний пояс: Берлин").performScrollTo().assertIsSelected()
        // Another editor's preference change cannot reinterpret the already open draft.
        runBlocking { repository.selectHomeZone("Asia/Tokyo") }
        compose.runOnIdle { assertEquals("Europe/Berlin", draft.inputTimeZoneId) }
        compose.onNodeWithText("Применить текущую настройку").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Asia/Tokyo", draft.inputTimeZoneId) }
        compose.onNodeWithText("По времени телефона").performScrollTo().performClick()
        compose.waitUntil(5_000) { repository.state.value.homeZoneId == null && draft.inputTimeZoneId == samara.id }
        compose.runOnIdle { assertEquals(moscow, repository.state.value.zoneId) }
    }

    @Test fun selectorAppearsAfterChangeAndSearchSelectsZoneAndAutomaticMode() {
        var device = samara
        val repository = repository { device }
        show(repository)
        compose.onNodeWithTag("calendar_time_zone_button").assertDoesNotExist()
        compose.onNodeWithTag("selected_time").assertTextEquals("Время: 15:00")
        device = moscow
        runBlocking { repository.refreshDeviceZone() }
        compose.onNodeWithTag("calendar_time_zone_button").assertIsDisplayed().performClick()
        compose.onNodeWithText("Часовой пояс календаря").assertIsDisplayed()
        saveScreen("calendar-time-zone-picker.png")
        compose.onNodeWithTag("calendar_time_zone_search").performTextInput("Самара")
        compose.onNodeWithTag("calendar_time_zone_option_Europe/Samara").performClick()
        compose.waitUntil(5_000) { repository.state.value.selectedZoneId == samara.id }
        compose.onNodeWithTag("selected_time").assertTextEquals("Время: 15:00")
        assertFalse(repository.state.value.changePending)
        compose.onNodeWithTag("calendar_time_zone_button").performClick()
        compose.onNodeWithTag("calendar_time_zone_device").performClick()
        compose.waitUntil(5_000) { repository.state.value.selectedZoneId == null }
        compose.onNodeWithTag("selected_time").assertTextEquals("Время: 14:00")
    }

    @Test fun legacyDraftRequiresExplicitTimezoneBeforeCreation() {
        var draft by mutableStateOf(CalendarEventDraftUiState(title = "Встреча", date = "2026-10-20", time = "15:00"))
        var creates = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCalendarTimeZone provides CalendarTimeZoneState(deviceZoneId = moscow.id)) {
                    CalendarDraftEditorSheet(draft, onValueChange = { _, _ -> }, onNotesChange = {}, onFormat = {},
                        onVoiceInput = {}, onCreate = { creates++ }, onDelete = {}, onDismiss = {},
                        onTimeZoneConfirm = { keep -> draft = draft.confirmTimeZone(moscow, keep) })
                }
            }
        }
        compose.onNodeWithText("Создать событие").assertIsNotEnabled()
        compose.onNodeWithTag("calendar_time_mode_toggle").performClick()
        compose.onNodeWithText("Считать введённое время по поясу: Москва").performScrollTo().performClick()
        compose.onNodeWithText("Создать событие").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, creates)
            assertEquals(moscow.id, draft.timeZoneId)
            assertEquals("15:00", draft.time)
        }
    }

    @Test fun changedDraftAllowsKeepingItsOriginalMoment() {
        var draft by mutableStateOf(CalendarEventDraftUiState(title = "Встреча", date = "2026-10-20", time = "15:00", timeZoneId = samara.id))
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCalendarTimeZone provides CalendarTimeZoneState(deviceZoneId = moscow.id)) {
                    CalendarDraftEditorSheet(draft, onValueChange = { _, _ -> }, onNotesChange = {}, onFormat = {},
                        onVoiceInput = {}, onCreate = {}, onDelete = {}, onDismiss = {},
                        onTimeZoneConfirm = { keep -> draft = draft.confirmTimeZone(moscow, keep) })
                }
            }
        }
        compose.onNodeWithText("Создать событие").assertIsNotEnabled()
        compose.onNodeWithTag("calendar_time_mode_toggle").performClick()
        compose.onNodeWithText("Сохранить прежний момент").performScrollTo().performClick()
        compose.onNodeWithText("Создать событие").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(samara.id, draft.timeZoneId)
            assertEquals("15:00", draft.time)
        }
    }

    @SuppressLint("MissingPermission")
    @Test fun emulatorTimezoneBroadcastUpdatesCalendarWithoutRestart() {
        // This system-setting test must never change the timezone of a physical phone.
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val alarm = instrumentation.targetContext.getSystemService(AlarmManager::class.java)
        val previous = ZoneId.systemDefault().id
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.SET_TIME_ZONE)
        try {
            alarm.setTimeZone(samara.id)
            compose.waitUntil(10_000) { ZoneId.systemDefault() == samara }
            val repository = repository(ZoneId::systemDefault)
            show(repository)
            compose.onNodeWithTag("selected_time").assertTextEquals("Время: 15:00")
            alarm.setTimeZone(moscow.id)
            compose.waitUntil(10_000) { repository.state.value.zoneId == moscow && repository.state.value.changePending }
            compose.onNodeWithTag("selected_time").assertTextEquals("Время: 14:00")
            compose.onNodeWithTag("calendar_time_zone_button").assertIsDisplayed()
        } finally {
            alarm.setTimeZone(previous)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun repository(device: () -> ZoneId): DataStoreCalendarTimeZoneRepository {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also(scopes::add)
        return DataStoreCalendarTimeZoneRepository(PreferenceDataStoreFactory.create(scope = scope) {
            File(context.cacheDir, "time-zone-${UUID.randomUUID()}.preferences_pb")
        }, scope, device).also { runBlocking { it.refreshDeviceZone() } }
    }

    private fun show(repository: DataStoreCalendarTimeZoneRepository) {
        compose.setContent {
            MaterialTheme {
                CalendarTimeZoneProvider(repository) {
                    UserDateTimeProvider(false, false) {
                        Column {
                            CalendarTimeZoneAction()
                            Text("Время: " + LocalUserDateTimeFormatter.current.localDateTime(
                                Instant.parse("2026-10-20T11:00:00Z").toEpochMilli()).toLocalTime(), Modifier.testTag("selected_time"))
                        }
                    }
                }
            }
        }
    }

    private fun saveScreen(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
