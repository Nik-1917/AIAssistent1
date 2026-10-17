package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.parser.AssistantResponseParser
import com.example.aiassistent1.presentation.viewmodel.CalendarEventDraftUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class CalendarReplyCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun createdDraftReplacesReplyAndItsControlsAndStillOpens() {
        val reply = "Новое событие: Встреча."
        val message = calendarReply(reply)
        var draft by mutableStateOf<CalendarEventDraftUiState?>(null)
        var openedId: String? = null
        compose.setContent {
            MaterialTheme {
                ChatMessageItem(
                    message = message, calendarDraft = draft,
                    onOpenCalendarDraft = { openedId = it }, smoothResponseEnabled = false,
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
        compose.onNodeWithText(reply).assertDoesNotExist()
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
                    message = calendarReply(parsed.reply), onOpenCalendarDraft = {},
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
                    message = message, onOpenCalendarDraft = {}, smoothResponseEnabled = false,
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
                    message = message, calendarDraft = draft, onOpenCalendarDraft = {},
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
