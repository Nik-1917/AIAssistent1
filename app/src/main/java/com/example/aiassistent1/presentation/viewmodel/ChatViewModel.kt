package com.example.aiassistent1.presentation.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiassistent1.domain.interfaces.ChatRepository
import com.example.aiassistent1.domain.interfaces.InputProvider
import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.interfaces.SpeechPlayback
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.model.ModelState
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
import java.io.File
import java.io.FileOutputStream

class ChatViewModel(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val sendMessage: SendMessageUseCase,
    private val llmEngine: LLMEngine,
    private val voiceInput: InputProvider? = null,
    private val speechPlayback: SpeechPlayback? = null,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ChatUiState())
    private var generationJob: Job? = null

    val uiState: StateFlow<ChatUiState> = mutableUiState.asStateFlow()

    init {
        observeHistory()
        observeModelState()
        observeVoiceInput()
        checkModelPresence()
    }

    fun refreshModelStatus() {
        checkModelPresence()
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            try {
                mutableUiState.update { it.copy(modelState = ModelState.Importing(0f)) }
                withContext(Dispatchers.IO) {
                    val contentResolver = context.contentResolver
                    val totalSize = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("Не удалось открыть файл")
                    
                    val targetDir = context.getExternalFilesDir("models") ?: throw Exception("Папка приложения недоступна")
                    if (!targetDir.exists()) targetDir.mkdirs()
                    
                    val targetFile = File(targetDir, "qwen2.5-3b-instruct-q4_k_m.gguf")
                    val outputStream = FileOutputStream(targetFile)
                    
                    val buffer = ByteArray(1024 * 1024) // 1MB buffer
                    var bytesCopied = 0L
                    
                    inputStream.use { input ->
                        outputStream.use { output ->
                            while (true) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead == -1) break
                                output.write(buffer, 0, bytesRead)
                                bytesCopied += bytesRead
                                if (totalSize > 0) {
                                    val progress = bytesCopied.toFloat() / totalSize
                                    mutableUiState.update { it.copy(modelState = ModelState.Importing(progress)) }
                                }
                            }
                        }
                    }
                }
                mutableUiState.update { it.copy(modelState = ModelState.Unloaded, isModelMissing = false) }
            } catch (e: Exception) {
                mutableUiState.update { it.copy(modelState = ModelState.Error(e.message ?: "Ошибка импорта")) }
            }
        }
    }

    private fun checkModelPresence() {
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = "qwen2.5-3b-instruct-q4_k_m.gguf"
            val downloadFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            val privateFile = File(context.getExternalFilesDir("models"), fileName)
            
            val downloadExists = downloadFile.exists() && downloadFile.length() > 0
            val privateExists = privateFile.exists() && privateFile.length() > 0
            val hasPermission = Environment.isExternalStorageManager()
            
            // Модель "есть", если она в приватной папке ИЛИ в Download с разрешением
            val exists = privateExists || (downloadExists && hasPermission)
            
            // Разрешение нужно, если файл в Download, а доступа нет (и в приватной тоже нет)
            val needsPermission = downloadExists && !hasPermission
            
            mutableUiState.update { it.copy(
                isModelMissing = !exists,
                needsPermission = needsPermission
            ) }
        }
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

        voiceInput?.stop()

        val userMessage = ChatMessage(role = MessageRole.USER, content = trimmedText)
        mutableUiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isProcessing = true,
                isVoiceMode = false,
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
                if (mutableUiState.value.isVoiceMode) {
                    assistantMessage?.content
                        ?.takeIf(String::isNotBlank)
                        ?.let { content ->
                            speechPlayback?.speak(content)?.onFailure { error ->
                                mutableUiState.update { it.copy(error = error.userMessage()) }
                            }
                        }
                }
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

    fun setVoiceMode(enabled: Boolean) {
        val state = mutableUiState.value
        if (state.isVoiceMode == enabled || (enabled && state.isProcessing)) return
        mutableUiState.update { it.copy(isVoiceMode = enabled, error = null) }
        if (enabled && !mutableUiState.value.isProcessing) startVoiceInput()
        if (!enabled) {
            voiceInput?.stop()
            speechPlayback?.stop()
        }
    }

    fun stopGeneration() {
        llmEngine.cancelGeneration()
        generationJob?.cancel()
    }

    fun clearChat() {
        val activeGeneration = generationJob
        llmEngine.cancelGeneration()
        voiceInput?.stop()
        speechPlayback?.stop()
        activeGeneration?.cancel()

        viewModelScope.launch {
            activeGeneration?.join()
            withContext(Dispatchers.IO) { chatRepository.deleteAllMessages() }
            mutableUiState.update {
                it.copy(
                    messages = emptyList(),
                    isProcessing = false,
                    isVoiceMode = false,
                    error = null,
                )
            }
        }
    }

    fun clearError() {
        mutableUiState.update { it.copy(error = null) }
    }

    fun openPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    override fun onCleared() {
        stopGeneration()
        voiceInput?.stop()
        (voiceInput as? AutoCloseable)?.close()
        speechPlayback?.close()
        llmEngine.close()
    }

    private fun observeVoiceInput() {
        val inputProvider = voiceInput ?: return
        viewModelScope.launch {
            inputProvider.observeInput().collect { transcript ->
                if (mutableUiState.value.isVoiceMode && !mutableUiState.value.isProcessing) {
                    inputProvider.stop()
                    sendMessage(transcript)
                }
            }
        }
    }

    private fun startVoiceInput() {
        runCatching { voiceInput?.start() }
            .onFailure { error -> mutableUiState.update { it.copy(error = error.userMessage(), isVoiceMode = false) } }
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