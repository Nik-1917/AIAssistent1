package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.parser.AssistantResponseParser
import com.example.aiassistent1.presentation.viewmodel.CalendarEventDraftUiState
import com.example.aiassistent1.presentation.viewmodel.CalendarDraftsState
import com.example.aiassistent1.presentation.viewmodel.ChatUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class CalendarReplyCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun createdDraftShowsOnlyItsCardAndStillOpens() {
        val reply = "Новое событие: Встреча."
        val message = calendarReply(reply)
        var draft by mutableStateOf<CalendarEventDraftUiState?>(null)
        var openedId: String? = null
        var openedEventId: String? = null
        compose.setContent {
            MaterialTheme {
                ChatMessageItem(
                    message = message, calendarDraft = draft,
                    onOpenCalendarDraft = { openedId = it },
                    onOpenSavedCalendarEvent = { openedEventId = it }, smoothResponseEnabled = false,
                    onRetry = {}, onDelete = {}, onCopy = {}, onSpeak = {},
                )
            }
        }
        compose.onNodeWithText(reply).assertIsDisplayed()
        compose.runOnIdle {
            draft = CalendarEventDraftUiState(requestId = message.id, chatId = "calendar", title = "Встреча")
        }
        compose.onNodeWithText("Встреча").assertIsDisplayed()
        compose.onNodeWithText("Черновик").assertIsDisplayed()
        compose.onNodeWithText(reply).assertDoesNotExist()
        compose.onNodeWithText("Ассистент").assertDoesNotExist()
        compose.onNodeWithContentDescription("Повторить генерацию").assertDoesNotExist()
        compose.onNodeWithContentDescription("Удалить сообщение").assertDoesNotExist()
        compose.onNodeWithText("Дополнить").performClick()
        compose.runOnIdle {
            assertEquals(message.id, openedId)
            assertEquals(reply, message.content)
            draft = draft!!.copy(savedEventId = "saved-event")
        }
        compose.onNodeWithText("Создано").assertIsDisplayed()
        compose.onNodeWithText("Перейти").performClick()
        compose.runOnIdle { assertEquals("saved-event", openedEventId) }
        compose.onNodeWithText(reply).assertDoesNotExist()
    }

    @Test fun rawCalendarStreamChangesToOneCardWithSmoothTextEnabled() = assertStreamThenSingleCard(true)

    @Test fun rawCalendarStreamChangesToOneCardWithSmoothTextDisabled() = assertStreamThenSingleCard(false)

    private fun assertStreamThenSingleCard(smooth: Boolean) {
        val user = ChatMessage("user", MessageRole.USER, "Создай встречу", chatId = "calendar")
        val reply = calendarReply("Новое событие: Встреча.")
        val chunks = listOf(
            """{"intent":"calendar_add", """,
            """{"intent":"calendar_add", "reply":"Новое событие: """,
        )
        var state by mutableStateOf(ChatUiState(
            activeChatId = "calendar", messages = listOf(user, reply.copy(content = chunks.first())),
            isProcessing = true,
            calendarDrafts = CalendarDraftsState(isLoaded = true),
        ))
        compose.setContent {
            MaterialTheme {
                LazyColumn {
                    items(state.messages, key = { it.id }) { message ->
                        ChatMessageItem(
                            message = message,
                            calendarDraft = state.calendarDrafts.drafts.firstOrNull { it.requestId == message.id },
                            onOpenCalendarDraft = {}, onOpenSavedCalendarEvent = {},
                            isStreaming = state.isGenerating && message.role == MessageRole.ASSISTANT,
                            smoothResponseEnabled = smooth, onCopy = {}, onSpeak = {},
                            onRetry = if (!state.isProcessing && message.role == MessageRole.ASSISTANT) ({}) else null,
                            onDelete = if (!state.isProcessing && message.role == MessageRole.ASSISTANT) ({}) else null,
                            modifier = Modifier.testTag(message.id),
                        )
                    }
                }
            }
        }
        chunks.forEach { content ->
            compose.runOnIdle { state = state.copy(messages = listOf(user, reply.copy(content = content))) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText(content).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(user.content).assertIsDisplayed()
            compose.onNodeWithText(content).assertIsDisplayed()
            compose.onNodeWithText("Ассистент").assertIsDisplayed()
            compose.onNodeWithText("Черновик").assertDoesNotExist()
        }
        val readyDrafts = CalendarDraftsState(
            drafts = listOf(CalendarEventDraftUiState(requestId = reply.id, title = "Встреча")), isLoaded = true,
        )
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            state = state.copy(messages = listOf(user, reply), calendarDrafts = readyDrafts, isProcessing = false)
        }
        repeat(4) {
            compose.mainClock.advanceTimeBy(80)
            compose.onAllNodesWithTag(reply.id).assertCountEquals(1)
            compose.onAllNodesWithText("Черновик").assertCountEquals(1)
            compose.onNodeWithText(user.content).assertIsDisplayed()
            compose.onNodeWithContentDescription("Повторить генерацию").assertDoesNotExist()
            compose.onNodeWithContentDescription("Удалить сообщение").assertDoesNotExist()
        }
        compose.onNodeWithText(reply.content).assertDoesNotExist()
        compose.onNodeWithText("Ассистент").assertDoesNotExist()
        compose.onNodeWithText("Встреча").assertIsDisplayed()
        compose.onNodeWithText(user.content).assertIsDisplayed()
        compose.runOnIdle { assertEquals(reply.content, state.messages.last().content) }

        // Editing the existing draft must update the same card, without another entrance.
        val cardNodeId = compose.onNodeWithText("Черновик").fetchSemanticsNode().id
        compose.runOnIdle {
            state = state.copy(calendarDrafts = readyDrafts.copy(
                drafts = listOf(readyDrafts.drafts.single().copy(title = "Встреча после правки")),
            ))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Встреча после правки").assertIsDisplayed()
        compose.onAllNodesWithText("Черновик").assertCountEquals(1)
        assertEquals(cardNodeId, compose.onNodeWithText("Черновик").fetchSemanticsNode().id)
    }

    @Test fun replyWithOmittedParamsKeepsTextActionsAndPlaybackText() {
        val parsed = AssistantResponseParser().parseResult(
            """{"intent":"chat","reply":"Уточните, какое событие нужно создать."}""",
        ).getOrThrow()
        assertNull(parsed.params)
        var spokenText: String? = null
        var retries = 0
        var deletions = 0
        compose.setContent {
            MaterialTheme {
                ChatMessageItem(
                    message = calendarReply(parsed.reply), onOpenCalendarDraft = {}, onOpenSavedCalendarEvent = {},
                    smoothResponseEnabled = false, onRetry = { retries++ }, onDelete = { deletions++ },
                    onCopy = {}, onSpeak = { spokenText = it },
                )
            }
        }
        compose.onNodeWithText(parsed.reply).assertIsDisplayed().performTouchInput { doubleClick() }
        compose.onNodeWithText("Ассистент").assertIsDisplayed()
        compose.onNodeWithText("Черновик").assertDoesNotExist()
        compose.onNodeWithContentDescription("Повторить генерацию").performClick()
        compose.onNodeWithContentDescription("Удалить сообщение").performClick()
        compose.runOnIdle {
            assertEquals(parsed.reply, spokenText)
            assertEquals(1, retries)
            assertEquals(1, deletions)
        }
    }

    @Test fun missingDraftAndParseFailureKeepTheirReplyCards() {
        var message by mutableStateOf(calendarReply("Новое событие: Встреча."))
        compose.setContent {
            MaterialTheme {
                ChatMessageItem(
                    message = message, onOpenCalendarDraft = {}, onOpenSavedCalendarEvent = {}, smoothResponseEnabled = false,
                    onCopy = {}, onSpeak = {},
                )
            }
        }
        compose.onNodeWithText(message.content).assertIsDisplayed()
        compose.onNodeWithText("Черновик").assertDoesNotExist()
        val error = "Не удалось разобрать ответ модели. Попробуйте изменить запрос."
        compose.runOnIdle { message = message.copy(content = error) }
        compose.onNodeWithText(error).assertIsDisplayed()
        compose.onNodeWithText("Ассистент").assertIsDisplayed()
    }

    @Test fun draftCannotHideAnotherMessageOrAnOrdinaryChatReply() {
        val original = calendarReply("Текст ответа")
        var message by mutableStateOf(original)
        var draft by mutableStateOf(CalendarEventDraftUiState(
            requestId = "another-message", chatId = "calendar", title = "Другой черновик",
        ))
        compose.setContent {
            MaterialTheme {
                ChatMessageItem(
                    message = message, calendarDraft = draft, onOpenCalendarDraft = {}, onOpenSavedCalendarEvent = {},
                    smoothResponseEnabled = false, onCopy = {}, onSpeak = {},
                )
            }
        }
        compose.onNodeWithText(original.content).assertIsDisplayed()
        compose.onNodeWithText("Другой черновик").assertDoesNotExist()
        compose.runOnIdle {
            message = original.copy(chatId = "general")
            draft = draft.copy(requestId = original.id, chatId = "general")
        }
        compose.onNodeWithText(original.content).assertIsDisplayed()
        compose.onNodeWithText("Другой черновик").assertDoesNotExist()
        compose.runOnIdle {
            message = original.copy(role = MessageRole.USER)
            draft = draft.copy(chatId = "calendar")
        }
        compose.onNodeWithText(original.content).assertIsDisplayed()
        compose.onNodeWithText("Вы").assertIsDisplayed()
        compose.onNodeWithText("Другой черновик").assertDoesNotExist()
    }

    private fun calendarReply(text: String) = ChatMessage(
        id = "calendar-reply", role = MessageRole.ASSISTANT, content = text, chatId = "calendar",
    )
}
