package com.example.aiassistent1.presentation.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Main-dispatcher ownership of history deletion and its optional return to listening. */
internal class ChatClearController(private val conversations: ConversationVoiceSession) {
    class Request internal constructor(val conversation: ConversationVoiceSession.Ticket,
        val modelName: String, internal var resumeAllowed: Boolean) {
        val chatId: String get() = conversation.chatId
    }
    data class Current(val chatId: String, val modelName: String, val voiceMode: Boolean)
    data class Completion(val currentConversation: Boolean, val resumeListening: Boolean, val error: Exception?)
    private val pending = mutableMapOf<String, Request>()

    fun isClearing(chatId: String): Boolean = pending.containsKey(chatId)

    fun begin(chatId: String, modelName: String, voiceMode: Boolean): Request? {
        if (isClearing(chatId)) return null
        val conversation = conversations.restart(chatId) ?: return null
        return Request(conversation, modelName, voiceMode).also { pending[chatId] = it }
    }

    /** Explicit user stop revokes resuming; deletion itself must still finish. */
    fun cancelVoiceResume() { pending.values.forEach { it.resumeAllowed = false } }

    fun accepts(request: Request, chatId: String): Boolean = pending[request.chatId] === request &&
        conversations.accepts(request.conversation, chatId)

    suspend fun execute(request: Request, previousGeneration: Job?, clearHistory: suspend () -> Unit,
        current: () -> Current, completed: (Completion) -> Unit) {
        check(pending[request.chatId] === request)
        var failure: Exception? = null
        var cancelled = false
        try {
            // The old writer can flush an interrupted reply in NonCancellable cleanup.
            // Delete only after that writer is finished, so it cannot recreate cleared history.
            previousGeneration?.join()
            clearHistory()
        } catch (error: CancellationException) {
            cancelled = true
            throw error
        } catch (error: Exception) {
            failure = error
        } finally {
            val state = current()
            val sameConversation = accepts(request, state.chatId)
            val ownsRequest = pending[request.chatId] === request
            if (ownsRequest) pending.remove(request.chatId)
            if (ownsRequest && !cancelled && currentCoroutineContext().isActive) {
                completed(Completion(sameConversation, sameConversation && request.resumeAllowed &&
                    state.voiceMode && state.modelName == request.modelName, failure))
            }
        }
    }
}
