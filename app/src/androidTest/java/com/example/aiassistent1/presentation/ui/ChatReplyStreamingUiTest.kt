package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.parser.AssistantResponseParser
import com.example.aiassistent1.presentation.viewmodel.ChatReplyTextStream
import com.example.aiassistent1.presentation.viewmodel.ChatResponseWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatReplyStreamingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realMessageBubbleShowsBrokenJsonTextAndFinishesOnFifthHiddenRepeat() = checkBubble(false)

    @Test fun smoothMessageBubbleAlsoShowsLastWordBeforeGenerationEnds() = checkBubble(true)

    private fun checkBubble(smooth: Boolean) {
        val visible = mutableStateOf(ChatMessage(role = MessageRole.ASSISTANT, content = "", chatId = "chat-test"))
        val streaming = mutableStateOf(true)
        val saved = mutableListOf<ChatMessage>()
        var stops = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val chunks = Channel<String>(Channel.UNLIMITED)
        compose.setContent {
            MaterialTheme {
                ChatMessageItem(message = visible.value, isStreaming = streaming.value, smoothResponseEnabled = smooth,
                    onOpenCalendarDraft = {}, onOpenSavedCalendarEvent = {}, onCopy = {}, onSpeak = {})
            }
        }
        val job = scope.launch {
            val writer = ChatResponseWriter(visible.value, { saved += it }, { message, active ->
                visible.value = message; streaming.value = active
            })
            try {
                writer.collectSnapshots(ChatReplyTextStream(AssistantResponseParser()).snapshots(
                    chunks.receiveAsFlow(), onRepetition = { stops++ }))
                writer.complete(visible.value)
            } finally { writer.interrupt() }
        }
        try {
            compose.onNodeWithText("Думаю...").assertIsDisplayed()
            chunks.trySend("{\"произвольное_поле\":\"Король ходит на одну клетку.")
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Король ходит на одну клетку.").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Король ходит на одну клетку.").assertIsDisplayed()
            assertTrue(job.isActive)
            chunks.trySend("\" \"emote\":\"✿✿✿✿")
            compose.waitForIdle()
            assertTrue(job.isActive)
            chunks.trySend("✿Шестой повтор запрещён")
            compose.waitUntil(5_000) { job.isCompleted }
            compose.onNodeWithText("Король ходит на одну клетку.").assertIsDisplayed()
            compose.onNodeWithText("Думаю...").assertDoesNotExist()
            assertEquals(1, stops)
            assertEquals("Король ходит на одну клетку.", saved.last().content)
            assertFalse(saved.last().isInterrupted)
        } finally { scope.cancel(); chunks.close() }
    }
}
