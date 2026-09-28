package com.example.aiassistent1.presentation.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.repository.DataStoreCalendarDraftRepository
import com.example.aiassistent1.domain.interfaces.CalendarDraftRepository
import com.example.aiassistent1.domain.model.CalendarDraftRecord
import com.example.aiassistent1.presentation.viewmodel.CalendarDraftController
import com.example.aiassistent1.presentation.viewmodel.CalendarEventDraftUiState
import com.example.aiassistent1.presentation.viewmodel.CalendarEventField
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class CalendarDraftCardsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstEditorSurvivesANewEventAndBothCardsRemainIndependent() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = object : CalendarDraftRepository {
            var stored = emptyList<CalendarDraftRecord>()
            override suspend fun load() = stored
            override suspend fun save(drafts: List<CalendarDraftRecord>) { stored = drafts }
        }
        val controller = CalendarDraftController(repository, scope)
        try {
            controller.add(CalendarEventDraftUiState(
                requestId = "a", title = "Встреча с Иваном", date = "2026-10-01", time = "15:00", durationMinutes = 60,
            ))
            compose.setContent {
                val state by controller.state.collectAsState()
                MaterialTheme {
                    Column {
                        TextButton(onClick = controller::showList) { Text("Черновики · ${state.drafts.count { it.savedEventId == null }}") }
                        state.drafts.forEach { draft -> CalendarDraftCard(draft, onOpen = { controller.open(draft.requestId) }) }
                    }
                    state.selected?.let { draft ->
                        key(draft.requestId) {
                            CalendarDraftEditorSheet(
                                draft = draft, onValueChange = { field, value -> controller.edit(draft.requestId, field, value) },
                                onNotesChange = { controller.editNotes(draft.requestId, it) }, onFormat = {}, onVoiceInput = {},
                                onCreate = { controller.save(draft.requestId) { "event-${it.requestId}" } },
                                onDelete = { controller.discard(draft.requestId) }, onDismiss = controller::close,
                            )
                        }
                    }
                    if (state.showList) CalendarDraftListSheet(state.drafts.filter { it.savedEventId == null }, controller::open, controller::close)
                }
            }
            compose.onNodeWithText("Дополнить").performClick()
            compose.onNodeWithText("Создать событие").assertIsNotEnabled()
            compose.onNode(hasSetTextAction() and hasText("Время (ЧЧ:ММ)")).performTextReplacement("17:45")
            compose.runOnIdle {
                controller.add(CalendarEventDraftUiState(requestId = "b", title = "Купить лекарства"))
            }
            compose.onNode(hasSetTextAction() and hasText("17:45")).assertExists()
            compose.onNodeWithContentDescription("Закрыть редактор").performClick()
            compose.onNodeWithText("Черновики · 2").assertExists()
            compose.onNodeWithText("Купить лекарства").assertExists()
            compose.onAllNodesWithText("Дополнить")[0].performClick()
            compose.onNode(hasSetTextAction() and hasText("17:45")).assertExists()
            compose.onNode(hasSetTextAction() and hasText("Ценность события")).performScrollTo().performTextReplacement("0")
            compose.onNodeWithText("Создать событие").assertIsEnabled()
            compose.onNodeWithContentDescription("Закрыть редактор").performClick()
            compose.onNodeWithText("Проверить и создать").performClick()
            compose.onNodeWithText("Черновик события").assertExists()
            val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "calendar-draft-editor.png")
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                screenshot.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithText("Создать событие").performClick()
            compose.onNodeWithText("Создано").assertExists()
            compose.onNodeWithText("Черновики · 1").assertExists()
            compose.runOnIdle {
                assertEquals("event-a", controller.find("a")!!.savedEventId)
                assertEquals("17:45", controller.find("a")!!.time)
                assertNull(controller.find("b")!!.savedEventId)
            }
        } finally { scope.cancel() }
    }

    @Test fun repeatedDiskWritesAndReopeningRestoreDraftsAndSavedStatus() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "draft-test-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = CalendarDraftRecord("a", "calendar", 1, title = "Встреча", fieldInputs = mapOf("Time" to "в пол"))
        val second = CalendarDraftRecord("b", "calendar", 2, title = "Лекарства", value = 0)
        try {
            val repository = DataStoreCalendarDraftRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
            repository.save(listOf(first))
            repository.save(listOf(first, second))
            repository.save(listOf(first.copy(savedEventId = "event-a"), second))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = DataStoreCalendarDraftRepository(PreferenceDataStoreFactory.create(scope = reopenedScope) { file })
            assertEquals(listOf(first.copy(savedEventId = "event-a"), second), reopened.load())
            reopened.save(listOf(second))
            assertEquals(listOf(second), reopened.load())
        } finally { reopenedScope.coroutineContext[Job]!!.cancelAndJoin(); file.delete() }
    }
}
