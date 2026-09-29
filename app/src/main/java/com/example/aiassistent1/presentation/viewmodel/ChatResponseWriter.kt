package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.model.ChatMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Confined to the generation's dispatcher; only immutable snapshots cross the storage boundary. */
internal class ChatResponseWriter(
    initialMessage: ChatMessage,
    private val saveMessage: suspend (ChatMessage) -> Unit,
    private val onMessageChanged: (ChatMessage, Boolean) -> Unit,
    private val checkpointIntervalMillis: Long = 1_000L,
) {
    private var currentMessage = initialMessage
    private var finished = false
    private var collecting = false

    init {
        require(checkpointIntervalMillis > 0)
    }

    suspend fun collect(response: Flow<String>) = coroutineScope {
        check(!collecting && !finished)
        collecting = true
        val checkpoints = launch {
            var savedContent: String? = null
            while (isActive) {
                delay(checkpointIntervalMillis)
                val snapshot = currentMessage
                if (snapshot.content.isNotBlank() && snapshot.content != savedContent) {
                    // A killed process leaves an explicitly unfinished response in history.
                    // Wait for each write even on cancellation, so it cannot overtake the final write.
                    withContext(NonCancellable) {
                        saveMessage(snapshot.copy(isInterrupted = true))
                    }
                    savedContent = snapshot.content
                }
            }
        }
        try {
            response.collect { delta ->
                if (delta.isNotEmpty()) {
                    currentMessage = currentMessage.copy(content = currentMessage.content + delta)
                    onMessageChanged(currentMessage, true)
                }
            }
        } finally {
            withContext(NonCancellable) {
                checkpoints.cancelAndJoin()
                collecting = false
            }
        }
    }

    suspend fun complete(message: ChatMessage) {
        check(!collecting && !finished)
        persistTerminal(message.copy(isInterrupted = false))
        // A stop during the final disk write must not start calendar actions or speech afterwards.
        currentCoroutineContext().ensureActive()
        finished = true
    }

    /** Also used for errors; a completed calendar reply must never be replaced by raw stream text. */
    suspend fun interrupt() {
        check(!collecting)
        if (!finished) {
            persistTerminal(currentMessage.copy(isInterrupted = true))
            finished = true
        }
    }

    private suspend fun persistTerminal(message: ChatMessage) = withContext(NonCancellable) {
        currentMessage = message
        onMessageChanged(message, false)
        if (message.content.isNotBlank()) saveMessage(message)
    }
}
