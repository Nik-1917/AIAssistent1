package com.example.aiassistent1.presentation.viewmodel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiassistent1.domain.interfaces.ChatRepository
import com.example.aiassistent1.domain.interfaces.InputProvider
import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import com.example.aiassistent1.domain.interfaces.SpeechPlayback
import com.example.aiassistent1.domain.interfaces.VoiceDraftRepository
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.domain.usecase.AddCalendarEventUseCase
import com.example.aiassistent1.domain.usecase.SendMessageUseCase
import com.example.aiassistent1.service.GenerationForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class ChatViewModel(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val sendMessage: SendMessageUseCase,
    private val addCalendarEvent: AddCalendarEventUseCase,
    private val llmEngine: LLMEngine,
    private val voiceInput: InputProvider? = null,
    private val voiceDraftRepository: VoiceDraftRepository,
    private val speechPlayback: SpeechPlayback? = null,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ChatUiState())
    private var generationJob: Job? = null
    private var voiceDraftRestored = false
    private var openVoiceDraftAfterRestore = false
    private var activeVoiceInputSessionId: Long? = null
    private var pendingCalendarEvent: Triple<String, String, Int>? = null
    private var paramsJob: Job? = null

    val uiState: StateFlow<ChatUiState> = mutableUiState.asStateFlow()

    init {
        checkFirstRun()
        observeHistory()
        observeModelState()
        observeSettings()
        observeVoiceInput()
        observeVoiceInputErrors()
        restoreVoiceDraft()
        checkModelPresence(settingsRepository.selectedModel.value)
        updateAvailableModels()
    }

    private fun checkFirstRun() {
        viewModelScope.launch {
            if (settingsRepository.isFirstRun.first()) {
                withContext(Dispatchers.IO) {
                    chatRepository.deleteAllMessages()
                }
                // Сбрасываем модель на дефолтную (русскую) при первом запуске
                settingsRepository.setSelectedModel("ruadapt_qwen2.5_3B_ext_u48_instruct_v4_Q4_K_M.gguf")
                settingsRepository.setFirstRunCompleted()
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.selectedModel.collect { model ->
                mutableUiState.update { it.copy(selectedModel = model) }
                checkModelPresence(model)
                observeParams(model)
            }
        }
        viewModelScope.launch {
            settingsRepository.showDeleteMessageConfirmation.collect { show ->
                mutableUiState.update { it.copy(showDeleteMessageConfirmation = show) }
            }
        }
        viewModelScope.launch {
            settingsRepository.showClearChatConfirmation.collect { show ->
                mutableUiState.update { it.copy(showClearChatConfirmation = show) }
            }
        }
    }

    private fun observeParams(modelName: String) {
        paramsJob?.cancel()
        paramsJob = viewModelScope.launch {
            settingsRepository.getParamsForModel(modelName).collect { params ->
                mutableUiState.update { it.copy(modelParams = params) }
                llmEngine.updateParams(params)
            }
        }
    }

    fun updateModelParams(params: GenerationParams) {
        viewModelScope.launch {
            settingsRepository.updateParamsForModel(uiState.value.selectedModel, params)
        }
    }

    fun refreshModelStatus() {
        checkModelPresence(settingsRepository.selectedModel.value)
        updateAvailableModels()
    }

    private fun updateAvailableModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val modelsDir = context.getExternalFilesDir("models")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            
            val modelFiles = mutableSetOf<String>()
            
            // Добавляем модели из приватной папки
            modelsDir?.listFiles { file -> file.extension == "gguf" }?.forEach { 
                if (it.length() > 0) modelFiles.add(it.name) 
            }
            
            // Добавляем модели из папки загрузок, если есть разрешение
            if (Environment.isExternalStorageManager()) {
                downloadsDir?.listFiles { file -> file.extension == "gguf" }?.forEach { 
                    if (it.length() > 0) modelFiles.add(it.name) 
                }
            }
            
            val sortedModels = modelFiles.toList().sorted()
            mutableUiState.update { it.copy(availableModels = sortedModels) }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            try {
                mutableUiState.update { it.copy(modelState = ModelState.Importing(0f)) }
                val importedFileName = withContext(Dispatchers.IO) {
                    val contentResolver = context.contentResolver
                    
                    // Получаем реальное имя файла
                    val fileName = getFileName(uri) ?: "model_${System.currentTimeMillis()}.gguf"
                    
                    val totalSize = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("Не удалось открыть файл")
                    
                    val targetDir = context.getExternalFilesDir("models") ?: throw Exception("Папка приложения недоступна")
                    if (!targetDir.exists()) targetDir.mkdirs()
                    
                    val targetFile = File(targetDir, fileName)
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
                    fileName
                }
                
                // После успешного импорта выбираем эту модель
                selectModel(importedFileName)
                updateAvailableModels()
                mutableUiState.update { it.copy(modelState = ModelState.Unloaded, isModelMissing = false) }
            } catch (e: Exception) {
                mutableUiState.update { it.copy(modelState = ModelState.Error(e.message ?: "Ошибка импорта")) }
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun checkModelPresence(fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
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
        sendMessageInternal(text)
    }

    fun sendVoiceDraft() {
        val state = mutableUiState.value
        if (!state.voiceDraft.isVisible || !sendMessageInternal(state.voiceDraft.text)) return

        mutableUiState.update { it.copy(voiceDraft = VoiceDraftState()) }
        clearPersistedVoiceDraft()
    }

    private fun sendMessageInternal(text: String): Boolean {
        val trimmedText = text.trim()
        when {
            trimmedText.isEmpty() -> return false
            trimmedText.length > MAX_MESSAGE_LENGTH -> {
                mutableUiState.update { it.copy(error = "Сообщение не должно превышать 500 символов") }
                return false
            }
            mutableUiState.value.isProcessing -> return false
        }

        stopVoiceInput()

        val userMessage = ChatMessage(role = MessageRole.USER, content = trimmedText)
        mutableUiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isProcessing = true,
                isStopping = false, // Сбрасываем флаг при новом сообщении
                isVoiceMode = false,
                voiceDraft = state.voiceDraft.copy(isVisible = false, isRecording = false),
                error = null,
            )
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) { chatRepository.saveMessage(userMessage) }
            startGenerationFlow()
        }
        return true
    }

    private fun startGenerationFlow() {
        GenerationForegroundService.start(context)
        generationJob = viewModelScope.launch {
            var assistantMessage: ChatMessage? = null
            try {
                val responseFlowResult = sendMessage(mutableUiState.value.messages)
                val response = responseFlowResult.getOrElse { error ->
                    mutableUiState.update { it.copy(error = error.userMessage()) }
                    return@launch
                }

                assistantMessage = ChatMessage(role = MessageRole.ASSISTANT, content = "")
                mutableUiState.update { state ->
                    state.copy(messages = state.messages + assistantMessage!!)
                }

                response.collect { delta ->
                    val currentAssistantMessage = assistantMessage ?: return@collect
                    val updatedAssistantMessage = currentAssistantMessage.copy(
                        content = currentAssistantMessage.content + delta,
                    )
                    assistantMessage = updatedAssistantMessage
                    updateMessage(updatedAssistantMessage)
                }

                // Сохраняем в БД только если есть контент
                val finalMessage = assistantMessage
                if (finalMessage != null) {
                    if (finalMessage.content.isNotBlank()) {
                        withContext(Dispatchers.IO) { chatRepository.saveMessage(finalMessage) }
                        finalMessage.content.let { handleIntentIfPresent(it) }
                    } else {
                        // Если сообщение пустое после завершения (например, сброс), удаляем из UI
                        mutableUiState.update { state ->
                            state.copy(messages = state.messages.filterNot { it.id == finalMessage.id })
                        }
                    }
                }

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
                    if (partialMessage.content.isBlank()) {
                        mutableUiState.update { state ->
                            state.copy(messages = state.messages.filterNot { it.id == partialMessage.id })
                        }
                    } else {
                        val interruptedMessage = partialMessage.copy(isInterrupted = true)
                        updateMessage(interruptedMessage)
                        withContext(NonCancellable + Dispatchers.IO) {
                            chatRepository.saveMessage(interruptedMessage)
                        }
                    }
                }
                throw error
            } catch (error: Exception) {
                mutableUiState.update { it.copy(error = error.userMessage()) }
            } finally {
                // Финальная проверка: если в списке осталось пустое сообщение, удаляем его
                assistantMessage?.let { final ->
                    if (final.content.isBlank()) {
                        mutableUiState.update { state ->
                            state.copy(messages = state.messages.filterNot { it.id == final.id })
                        }
                    }
                }
                mutableUiState.update { it.copy(isProcessing = false, isStopping = false) }
                GenerationForegroundService.stop(context)
            }
        }
    }

    fun selectModel(modelName: String) {
        viewModelScope.launch {
            if (uiState.value.selectedModel == modelName) return@launch
            
            stopGeneration()
            llmEngine.close()
            // Принудительно сбрасываем флаги при смене модели
            mutableUiState.update { it.copy(isStopping = false, isProcessing = false) }
            settingsRepository.setSelectedModel(modelName)
        }
    }

    fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("AI Assistant Message", text)
        clipboard.setPrimaryClip(clip)
        mutableUiState.update { it.copy(snackbarMessage = "Текст скопирован") }
    }

    fun setShowDeleteMessageConfirmation(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowDeleteMessageConfirmation(show)
        }
    }

    fun setShowClearChatConfirmation(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowClearChatConfirmation(show)
        }
    }

    fun deleteMessage(id: String) {
        viewModelScope.launch {
            // Сначала немедленно удаляем из UI
            mutableUiState.update { it.copy(messages = it.messages.filterNot { it.id == id }) }
            // Затем удаляем из базы данных
            withContext(Dispatchers.IO) {
                chatRepository.deleteMessage(id)
            }
        }
    }

    fun retry() {
        val state = uiState.value
        if (state.isProcessing) return
        
        val lastUserMessage = state.messages.findLast { it.role == MessageRole.USER } ?: return
        val lastUserMessageIndex = state.messages.indexOf(lastUserMessage)

        // Берем историю до последнего сообщения пользователя включительно
        val historyToRetry = state.messages.take(lastUserMessageIndex + 1)
        
        viewModelScope.launch {
            // Удаляем из БД сообщения после последнего пользовательского
            val messagesToDelete = state.messages.drop(lastUserMessageIndex + 1)
            withContext(Dispatchers.IO) {
                messagesToDelete.forEach { chatRepository.deleteMessage(it.id) }
            }
            
            // Сначала обновляем UI (удаляем лишнее), затем запускаем генерацию
            mutableUiState.update { it.copy(
                messages = historyToRetry,
                isProcessing = true,
                isStopping = false, // Сбрасываем флаг остановки при повторе
                isVoiceMode = false,
                error = null,
            ) }
            
            startGenerationFlow()
        }
    }

    fun onMicrophoneTap() {
        val state = mutableUiState.value
        if (state.isProcessing) return

        if (state.voiceDraft.isVisible) {
            hideVoiceDraftAndEnterVoiceMode()
        } else {
            setVoiceMode(!state.isVoiceMode)
        }
    }

    fun startVoiceDraft() {
        val state = mutableUiState.value
        if (state.isProcessing) return
        if (!voiceDraftRestored) {
            openVoiceDraftAfterRestore = true
            return
        }
        if (state.voiceDraft.isVisible && state.voiceDraft.isRecording) return

        if (state.isVoiceMode || state.voiceDraft.isRecording) stopVoiceInput()
        mutableUiState.update { current ->
            current.copy(
                isVoiceMode = false,
                voiceDraft = current.voiceDraft.copy(isVisible = true, isRecording = true),
                error = null,
            )
        }
        speechPlayback?.stop()
        startVoiceInput(continuous = true)
    }

    fun deleteVoiceDraft() {
        stopVoiceInput()
        mutableUiState.update {
            it.copy(
                isVoiceMode = false,
                voiceDraft = VoiceDraftState(),
            )
        }
        clearPersistedVoiceDraft()
    }

    fun stopVoiceCaptureForBackground() {
        val state = mutableUiState.value
        if (!state.isVoiceMode && !state.voiceDraft.isRecording) return

        stopVoiceInput()
        speechPlayback?.stop()
        mutableUiState.update {
            it.copy(
                isVoiceMode = false,
                voiceDraft = state.voiceDraft.copy(isVisible = false, isRecording = false),
            )
        }
        persistVoiceDraft(state.voiceDraft.text)
    }

    fun setVoiceMode(enabled: Boolean) {
        val state = mutableUiState.value
        if (state.isVoiceMode == enabled || (enabled && state.isProcessing)) return
        mutableUiState.update { it.copy(isVoiceMode = enabled, error = null) }
        if (enabled && !mutableUiState.value.isProcessing) startVoiceInput()
        if (!enabled) {
            stopVoiceInput()
            speechPlayback?.stop()
        }
    }

    fun stopGeneration() {
        if (!uiState.value.isProcessing) return

        // Устанавливаем флаг остановки для изменения текста в UI
        mutableUiState.update { it.copy(isStopping = true) }
        
        // Прерываем native процесс
        llmEngine.cancelGeneration()
        // Отменяем корутину генерации
        generationJob?.cancel()
        // Возвращаем немедленный сброс флага обработки, как было раньше
        mutableUiState.update { it.copy(isProcessing = false) }
        // Останавливаем сервис переднего плана
        GenerationForegroundService.stop(context)
    }

    fun clearChat() {
        val activeGeneration = generationJob
        val voiceDraft = mutableUiState.value.voiceDraft
        llmEngine.cancelGeneration()
        stopVoiceInput()
        speechPlayback?.stop()
        activeGeneration?.cancel()
        GenerationForegroundService.stop(context)
        persistVoiceDraft(voiceDraft.text)

        viewModelScope.launch {
            activeGeneration?.join()
            withContext(Dispatchers.IO) { chatRepository.deleteAllMessages() }
            mutableUiState.update {
                it.copy(
                    messages = emptyList(),
                    isProcessing = false,
                    isVoiceMode = false,
                    voiceDraft = it.voiceDraft.copy(isVisible = false, isRecording = false),
                    error = null,
                )
            }
        }
    }

    fun clearError() {
        mutableUiState.update { it.copy(error = null) }
    }

    fun clearSnackbar() {
        mutableUiState.update { it.copy(snackbarMessage = null) }
    }

    private fun handleIntentIfPresent(text: String) {
        try {
            val jsonStart = text.indexOf('{')
            val jsonEnd = text.lastIndexOf('}')
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                val jsonStr = text.substring(jsonStart, jsonEnd + 1)
                val json = JSONObject(jsonStr)
                val intent = json.optString("intent")
                if (intent == "calendar_add") {
                    val params = json.optJSONObject("params") ?: return
                    val title = params.optString("title")
                    val date = params.optString("date")
                    val duration = params.optInt("duration_min", 60)
                    if (title.isNotEmpty() && date.isNotEmpty()) {
                        executeCalendarAdd(title, date, duration)
                    }
                }
            }
        } catch (e: Exception) {
            // Игнорируем ошибки парсинга
        }
    }

    private fun executeCalendarAdd(title: String, date: String, duration: Int) {
        val hasWritePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasReadPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasWritePermission || !hasReadPermission) {
            pendingCalendarEvent = Triple(title, date, duration)
            mutableUiState.update { it.copy(needsCalendarPermission = true) }
            return
        }

        viewModelScope.launch {
            addCalendarEvent(title, date, duration)
                .onSuccess {
                    mutableUiState.update { it.copy(snackbarMessage = "Событие добавлено в календарь") }
                }
                .onFailure { error ->
                    mutableUiState.update { it.copy(error = error.userMessage()) }
                }
        }
    }

    fun onCalendarPermissionResult(granted: Boolean) {
        mutableUiState.update { it.copy(needsCalendarPermission = false) }
        if (granted) {
            pendingCalendarEvent?.let { (title, date, duration) ->
                executeCalendarAdd(title, date, duration)
            }
        }
        pendingCalendarEvent = null
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
        stopVoiceInput()
        (voiceInput as? AutoCloseable)?.close()
        speechPlayback?.close()
        llmEngine.close()
    }

    private fun observeVoiceInput() {
        val inputProvider = voiceInput ?: return
        viewModelScope.launch {
            inputProvider.observeInput().collect { event ->
                if (event.sessionId != activeVoiceInputSessionId) return@collect
                val state = mutableUiState.value
                when {
                    state.voiceDraft.isRecording && !state.isProcessing -> {
                        appendVoiceDraftTranscript(event.transcript)
                    }
                    state.isVoiceMode && !state.isProcessing -> {
                        sendMessage(event.transcript)
                    }
                }
            }
        }
    }

    private fun startVoiceInput(continuous: Boolean = false) {
        val session = runCatching {
            if (continuous) voiceInput?.startContinuous()
            else voiceInput?.start()
        }
        session.onSuccess { sessionId -> activeVoiceInputSessionId = sessionId }
            .onFailure { error ->
                activeVoiceInputSessionId = null
                mutableUiState.update {
                    it.copy(
                        error = error.userMessage(),
                        isVoiceMode = false,
                        voiceDraft = it.voiceDraft.copy(isRecording = false),
                    )
                }
            }
    }

    private fun observeVoiceInputErrors() {
        val inputProvider = voiceInput ?: return
        viewModelScope.launch {
            inputProvider.observeErrors().collect { event ->
                if (event.sessionId != activeVoiceInputSessionId) return@collect
                val state = mutableUiState.value
                if (!state.isVoiceMode && !state.voiceDraft.isRecording) return@collect

                mutableUiState.update {
                    it.copy(
                        error = event.cause.message ?: "Не удалось обработать голосовой ввод",
                        isVoiceMode = false,
                        voiceDraft = it.voiceDraft.copy(isRecording = false),
                    )
                }
                activeVoiceInputSessionId = null
            }
        }
    }

    private fun hideVoiceDraftAndEnterVoiceMode() {
        val state = mutableUiState.value
        if (!state.voiceDraft.isVisible) return

        stopVoiceInput()
        mutableUiState.update {
            it.copy(
                isVoiceMode = true,
                voiceDraft = state.voiceDraft.copy(isVisible = false, isRecording = false),
                error = null,
            )
        }
        persistVoiceDraft(state.voiceDraft.text)
        startVoiceInput()
    }

    private fun stopVoiceInput() {
        activeVoiceInputSessionId = null
        voiceInput?.stop()
    }

    private fun restoreVoiceDraft() {
        viewModelScope.launch {
            val draft = runCatching { voiceDraftRepository.loadDraft() }
                .onFailure {
                    mutableUiState.update { state ->
                        state.copy(error = "Не удалось восстановить голосовой черновик")
                    }
                }
                .getOrDefault("")

            voiceDraftRestored = true
            mutableUiState.update { state ->
                state.copy(voiceDraft = state.voiceDraft.copy(text = draft.take(MAX_MESSAGE_LENGTH)))
            }
            if (openVoiceDraftAfterRestore) {
                openVoiceDraftAfterRestore = false
                startVoiceDraft()
            }
        }
    }

    private suspend fun appendVoiceDraftTranscript(transcript: String) {
        val recognizedText = transcript.trim()
        if (recognizedText.isEmpty()) return

        val state = mutableUiState.value
        if (!state.voiceDraft.isRecording) return

        val currentText = state.voiceDraft.text.trim()
        val separator = if (currentText.isEmpty()) "" else " "
        val availableLength = MAX_MESSAGE_LENGTH - currentText.length - separator.length
        if (availableLength <= 0) {
            mutableUiState.update { it.copy(error = "Голосовой черновик ограничен 500 символами") }
            return
        }

        val appendedText = recognizedText.take(availableLength)
        val updatedText = currentText + separator + appendedText
        mutableUiState.update {
            it.copy(voiceDraft = it.voiceDraft.copy(text = updatedText))
        }
        runCatching { voiceDraftRepository.saveDraft(updatedText) }
            .onFailure {
                mutableUiState.update { state ->
                    state.copy(error = "Не удалось сохранить голосовой черновик")
                }
            }
        if (appendedText.length < recognizedText.length) {
            mutableUiState.update { it.copy(error = "Голосовой черновик ограничен 500 символами") }
        }
    }

    private fun persistVoiceDraft(text: String) {
        viewModelScope.launch {
            runCatching { voiceDraftRepository.saveDraft(text) }
                .onFailure {
                    mutableUiState.update { state ->
                        state.copy(error = "Не удалось сохранить голосовой черновик")
                    }
                }
        }
    }

    private fun clearPersistedVoiceDraft() {
        viewModelScope.launch {
            runCatching { voiceDraftRepository.clearDraft() }
                .onFailure {
                    mutableUiState.update { state ->
                        state.copy(error = "Не удалось удалить голосовой черновик")
                    }
                }
        }
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
        const val MAX_MESSAGE_LENGTH = 3000
    }
}
