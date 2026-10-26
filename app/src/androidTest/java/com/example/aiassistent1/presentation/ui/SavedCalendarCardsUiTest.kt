package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.calendar.storage.android.RoomCalendarEventRepository
import com.example.aiassistent1.calendar.storage.android.local.CalendarDatabase
import com.example.aiassistent1.domain.model.CalendarTimeZoneState
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.presentation.formatting.UserDateTimeFormatter
import com.example.aiassistent1.presentation.viewmodel.CalendarEventDraftUiState
import com.example.aiassistent1.presentation.viewmodel.SavedCalendarCardsController
import com.example.aiassistent1.presentation.viewmodel.SavedCalendarEventCardState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SavedCalendarCardsUiTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("Europe/Moscow")

    @Test fun savedCardFollowsTheActualRoomEventAndKeepsOneMessageAndOneEvent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).build()
        val repository = RoomCalendarEventRepository(database.calendarEventDao(), deviceZone = { zone })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val before = LocalDateTime.parse("2026-10-20T09:00")
        val after = LocalDateTime.parse("2026-10-22T14:30")
        val event = runBlocking {
            repository.create(CalendarEventDraft("Встреча", before.atZone(zone).toInstant().toEpochMilli(),
                before.plusHours(1).atZone(zone).toInstant().toEpochMilli(), notes = "Старое примечание")).getOrThrow()
        }
        val draft = CalendarEventDraftUiState(requestId = "reply", savedEventId = event.id,
            title = "Встреча", date = "2026-10-20", time = "09:00", notes = "Старое примечание")
        val message = ChatMessage("reply", MessageRole.ASSISTANT, "Новое событие: Встреча.", chatId = "calendar")
        val cards = SavedCalendarCardsController(scope, ObserveCalendarEventsByIdUseCase(repository)::invoke)
        val formatter = UserDateTimeFormatter(hideCurrentYear = false, zoneId = zone)
        var opened: String? = null
        try {
            compose.setContent {
                val state by cards.state.collectAsState()
                LaunchedEffect(Unit) { cards.watch(listOf(draft), setOf(message.id), zone) }
                MaterialTheme {
                    CompositionLocalProvider(LocalCalendarTimeZone provides CalendarTimeZoneState(deviceZoneId = zone.id),
                        LocalUserDateTimeFormatter provides formatter) {
                        ChatMessageItem(message, calendarDraft = draft, savedCalendarEvent = state[event.id] ?: SavedCalendarEventCardState.Loading,
                            onOpenCalendarDraft = { error("A saved card must open its event") }, onOpenSavedCalendarEvent = { opened = it },
                            smoothResponseEnabled = false, onCopy = {}, onSpeak = {})
                    }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Создано").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(formatter.dateTime(before)).assertIsDisplayed()
            runBlocking {
                UpdateCalendarEventUseCase(repository)(CalendarEventUpdate(event.id, "Перенесённая встреча",
                    after.atZone(zone).toInstant().toEpochMilli(), after.plusHours(2).atZone(zone).toInstant().toEpochMilli(),
                    expectedRevision = event.revision, notes = "Новое примечание", valueChange = CalendarValueChange.Set(0))).getOrThrow()
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Перенесённая встреча").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(formatter.dateTime(after)).assertIsDisplayed()
            compose.onNodeWithText(formatter.dateTime(before)).assertDoesNotExist()
            compose.onNodeWithText("Примечание: Новое примечание").assertIsDisplayed()
            compose.onNodeWithText("Длительность: 120 мин · Ценность: 0").assertIsDisplayed()
            compose.onAllNodesWithText("Создано").assertCountEquals(1)
            compose.onNodeWithText(message.content).assertDoesNotExist()
            compose.onNodeWithText("Перейти").performClick()
            compose.runOnIdle {
                assertEquals(event.id, opened)
                assertEquals("2026-10-20", draft.date)
                assertEquals("09:00", draft.time)
                assertEquals("Новое событие: Встреча.", message.content)
            }
            assertEquals(1, runBlocking { repository.search("", Long.MIN_VALUE + 1, Long.MAX_VALUE).getOrThrow() }.size)
            runBlocking { repository.delete(event.id).getOrThrow() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Событие не найдено в календаре.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Перейти").assertDoesNotExist()
            compose.onNodeWithText("Создать событие").assertDoesNotExist()
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            database.close()
        }
    }

    @Test fun readFailureDoesNotPretendTheEventWasDeletedAndRetryUsesTheSameCard() {
        var state by mutableStateOf<SavedCalendarEventCardState>(SavedCalendarEventCardState.Failed)
        var retries = 0
        val draft = CalendarEventDraftUiState(requestId = "reply", savedEventId = "saved", title = "Встреча")
        compose.setContent { MaterialTheme {
            CalendarDraftCard(draft, onOpen = { error("Not a draft") }, onOpenSavedEvent = {},
                savedEventState = state, onRetrySavedEvent = { retries++ })
        } }
        compose.onNodeWithText("Не удалось загрузить актуальные данные события.").assertIsDisplayed()
        compose.onNodeWithText("Событие не найдено в календаре.").assertDoesNotExist()
        compose.onNodeWithText("Повторить").performClick()
        compose.runOnIdle { assertEquals(1, retries); state = SavedCalendarEventCardState.Missing }
        compose.onNodeWithText("Событие не найдено в календаре.").assertIsDisplayed()
        compose.onNodeWithText("Перейти").assertDoesNotExist()
    }
}
