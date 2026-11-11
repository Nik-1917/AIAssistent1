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
import com.example.aiassistent1.domain.model.CalendarCreationMode
import com.example.aiassistent1.domain.model.ModelParameterProfiles
import com.example.aiassistent1.domain.model.ModelProfile
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

    @Test fun settingsSelectOneModeShowOnlyItsExampleAndRememberTheHomeZone() {
        val repository = repository { moscow }
        var open by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            CalendarTimeZoneProvider(repository) {
                if (open) ModelSettingsDialog(
                    profiles = ModelParameterProfiles(), initialProfile = ModelProfile.CALENDAR, paramsLoaded = true,
                    hideCurrentMonth = false, onHideCurrentMonthChange = {}, hideCurrentYear = false, onHideCurrentYearChange = {},
                    smoothResponseEnabled = false, dialogueModeEnabled = false, autoPlaybackEnabled = false, speechRate = 1f,
                    onDismiss = { open = false }, onParamsChange = { _, _ -> }, onSmoothResponseChange = {},
                    onDialogueModeChange = {}, onAutoPlaybackChange = {}, onSpeechRateChange = {},
                )
            }
        } }
        fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
        fun example(text: String) = compose.onNodeWithText(text, substring = true)

        click("Часовые пояса")
        val phone = compose.onNodeWithText("По времени телефона")
        phone.assertIsSelected()
        example("Вы уже в Москве").assertDoesNotExist()
        click("По времени телефона")
        example("15:00 по Москве").performScrollTo().assertIsDisplayed()
        click("По времени телефона")
        example("Вы уже в Москве").assertDoesNotExist()
        phone.assertIsSelected()

        click("Фиксированный момент")
        compose.waitUntil(5_000) { repository.state.value.creationMode == CalendarCreationMode.FIXED_MOMENT }
        example("поездка не переносит договорённость").performScrollTo().assertIsDisplayed()
        click("Всегда по местному времени")
        compose.waitUntil(5_000) { repository.state.value.creationMode == CalendarCreationMode.LOCAL_TIME }
        example("09:00 местного времени").performScrollTo().assertIsDisplayed()
        example("поездка не переносит договорённость").assertDoesNotExist()

        click("Домашний пояс")
        compose.onNodeWithTag("calendar_home_zone_search").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Всегда по местному времени").assertIsSelected()
        click("Домашний пояс")
        compose.onNodeWithTag("calendar_home_zone_search").performTextInput("Самара")
        compose.onNodeWithTag("calendar_home_zone_option_Europe/Samara").performClick()
        compose.waitUntil(5_000) { repository.state.value.homeZoneId == samara.id }
        example("Московский календарь показывает 08:00").performScrollTo().assertIsDisplayed()
        example("09:00 местного времени").assertDoesNotExist()
        compose.onNodeWithText("Домашний пояс").assertIsSelected()
        phone.assertIsNotSelected()
        click("По времени телефона")
        compose.waitUntil(5_000) { repository.state.value.creationMode == CalendarCreationMode.PHONE_TIME }
        click("Домашний пояс")
        compose.waitUntil(5_000) { repository.state.value.creationMode == CalendarCreationMode.HOME_TIME }
        compose.onNodeWithTag("calendar_home_zone_search").assertDoesNotExist()
        compose.runOnIdle { open = false }
        compose.waitForIdle()
        compose.runOnIdle { open = true }
        click("Часовые пояса")
        compose.onNodeWithText("Домашний пояс").assertIsSelected()
        example("Московский календарь показывает 08:00").assertDoesNotExist()
        assertEquals(moscow, repository.state.value.zoneId)
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
        compose.onNodeWithTag("calendar_draft_editor_fields").performScrollToNode(hasText("Считать введённое время по поясу: Москва"))
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
        compose.onNodeWithTag("calendar_draft_editor_fields").performScrollToNode(hasText("Сохранить прежний момент"))
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
