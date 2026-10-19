package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.interfaces.ChatInputDraftRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatInputDraftState(
    val text: String = "",
    val isLoaded: Boolean = false,
    val storageError: String? = null,
)

/**
 * Main-dispatcher state shared across screens and Activity/ViewModel recreation.
 * Its application scope lets queued writes finish after the screen closes.
 */
class ChatInputDraftController(
    private val repository: ChatInputDraftRepository,
    private val scope: CoroutineScope,
) {
    private data class Write(val text: String, val revision: Long)

    private val mutableState = MutableStateFlow(ChatInputDraftState())
    val state = mutableState.asStateFlow()
    private val writes = Channel<Write>(Channel.CONFLATED)
    private var revision = 0L
    private var loading = false

    init {
        scope.launch { restore() }
        scope.launch {
            // One writer preserves ordering; a slow disk retains only the newest pending edit.
            for (write in writes) {
                val error = try {
                    repository.save(write.text)
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    "Не удалось сохранить текст. Повторите попытку."
                }
                if (write.revision == revision) {
                    mutableState.value = state.value.copy(storageError = error)
                }
            }
        }
    }

    fun updateText(text: String) {
        val current = state.value
        if (!current.isLoaded || current.text == text) return
        revision++
        mutableState.value = current.copy(text = text)
        writes.trySend(Write(text, revision))
    }

    fun send(sendMessage: (String) -> Boolean) {
        val current = state.value
        if (!current.isLoaded) return
        val sentRevision = revision
        if (sendMessage(current.text) && revision == sentRevision) updateText("")
    }

    fun retryStorage() {
        if (state.value.isLoaded) {
            writes.trySend(Write(state.value.text, revision))
        } else {
            scope.launch { restore() }
        }
    }

    private suspend fun restore() {
        if (loading || state.value.isLoaded) return
        loading = true
        try {
            val text = repository.load()
            mutableState.value = ChatInputDraftState(text = text, isLoaded = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = state.value.copy(
                storageError = "Не удалось восстановить текст. Повторите попытку.",
            )
        } finally {
            loading = false
        }
    }
}
