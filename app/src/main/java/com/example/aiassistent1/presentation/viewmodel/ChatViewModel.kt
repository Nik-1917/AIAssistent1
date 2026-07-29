package com.example.aiassistent1.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiassistent1.domain.interfaces.ChatRepository
import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val sendMessage: SendMessageUseCase,
    private val llmEngine: LLMEngine,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ChatUiState())
    private var generationJob: Job? = null

    val uiState: StateFlow<ChatUiState> = mutableUiState.asStateFlow()

    init {
        observeHistory()
        observeModelState()
    }

    fun sendMessage(text: String) {
        val trimmedText = text.trim()
        when {
            trimmedText.isEmpty() -> return
            trimmedText.length > MAX_MESSAGE_LENGTH -> {
                mutableUiState.update { it.copy(error = "Сообщение не должно превышать 500 символов") }
                return
            }
            mutableUiState.value.isProcessing -> return
        }

        val userMessage = ChatMessage(role = MessageRole.USER, content = trimmedText)
        mutableUiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isProcessing = true,
                error = null,
            )
        }

        generationJob = viewModelScope.launch {
            var assistantMessage: ChatMessage? = null
            try {
                withContext(Dispatchers.IO) { chatRepository.saveMessage(userMessage) }
                val response = sendMessage(mutableUiState.value.messages).getOrElse { error ->
                    mutableUiState.update { it.copy(error = error.userMessage()) }
                    return@launch
                }

                assistantMessage = ChatMessage(role = MessageRole.ASSISTANT, content = "")
                mutableUiState.update { state ->
                    state.copy(messages = state.messages + assistantMessage)
                }

                response.collect { delta ->
                    val currentAssistantMessage = assistantMessage ?: return@collect
                    val updatedAssistantMessage = currentAssistantMessage.copy(
                        content = currentAssistantMessage.content + delta,
                    )
                    assistantMessage = updatedAssistantMessage
                    updateMessage(updatedAssistantMessage)
                }

                withContext(Dispatchers.IO) { chatRepository.saveMessage(assistantMessage) }
            } catch (error: CancellationException) {
                assistantMessage?.let { partialMessage ->
                    val interruptedMessage = partialMessage.copy(isInterrupted = true)
                    updateMessage(interruptedMessage)
                    if (interruptedMessage.content.isNotBlank()) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            chatRepository.saveMessage(interruptedMessage)
                        }
                    }
                }
                throw error
            } catch (error: Exception) {
                mutableUiState.update { it.copy(error = error.userMessage()) }
            } finally {
                mutableUiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    fun stopGeneration() {
        llmEngine.cancelGeneration()
        generationJob?.cancel()
    }

    fun clearChat() {
        val activeGeneration = generationJob
        llmEngine.cancelGeneration()
        activeGeneration?.cancel()

        viewModelScope.launch {
            activeGeneration?.join()
            withContext(Dispatchers.IO) { chatRepository.deleteAllMessages() }
            mutableUiState.update {
                it.copy(
                    messages = emptyList(),
                    isProcessing = false,
                    error = null,
                )
            }
        }
    }

    fun clearError() {
        mutableUiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        stopGeneration()
        llmEngine.close()
    }

    private fun observeHistory() {
        viewModelScope.launch {
            chatRepository.observeMessages().collect { persistedMessages ->
                mutableUiState.update { state ->
                    val persistedIds = persistedMessages.mapTo(mutableSetOf()) { it.id }
                    val pendingMessages = state.messages.filterNot { it.id in persistedIds }
                    state.copy(messages = persistedMessages + pendingMessages)
                }
            }
        }
    }

    private fun observeModelState() {
        viewModelScope.launch {
            llmEngine.state.collect { modelState ->
                mutableUiState.update { it.copy(modelState = modelState) }
            }
        }
    }

    private fun updateMessage(message: ChatMessage) {
        mutableUiState.update { state ->
            state.copy(messages = state.messages.map { current ->
                if (current.id == message.id) message else current
            })
        }
    }

    private fun Throwable.userMessage(): String = message ?: "Не удалось сгенерировать ответ"

    private companion object {
        const val MAX_MESSAGE_LENGTH = 500
    }
}