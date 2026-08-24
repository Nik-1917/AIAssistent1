package com.example.aiassistent1.presentation.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiassistent1.calendar.core.domain.CalendarEventDraft
import com.example.aiassistent1.calendar.core.domain.CreateCalendarEventUseCase
import com.example.aiassistent1.calendar.core.domain.SearchCalendarEventsUseCase
import com.example.aiassistent1.domain.interfaces.ChatRepository
import com.example.aiassistent1.domain.interfaces.InputProvider
import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import com.example.aiassistent1.domain.interfaces.SpeechPlayback
import com.example.aiassistent1.domain.interfaces.VoiceDraftRepository
import com.example.aiassistent1.domain.formatter.CalendarReplyTimeFormatter
import com.example.aiassistent1.domain.context.ModelContextBuilder
import com.example.aiassistent1.domain.model.CalendarAddParams
import com.example.aiassistent1.domain.model.CalendarSearchParams
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.ChatScrollPosition
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.FloatingControlPositions
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.domain.parser.AssistantResponseParser
import com.example.aiassistent1.domain.usecase.SendMessageUseCase
import com.example.aiassistent1.service.GenerationForegroundService
import com.example.aiassistent1.presentation.playback.SpeechPlaybackController
import com.example.aiassistent1.presentation.playback.SpeechPlaybackState
import com.example.aiassistent1.presentation.playback.SpeechStopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ChatViewModel(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val sendMessage: SendMessageUseCase,
    private val createCalendarEvent: CreateCalendarEventUseCase,
    private val llmEngine: LLMEngine,
    private val voiceInput: InputProvider? = null,
    private val voiceDraftRepository: VoiceDraftRepository,
    private val speechPlayback: SpeechPlayback? = null,
    private val settingsRepository: SettingsRepository,
    private val searchCalendarEvents: SearchCalendarEventsUseCase,
    private val assistantResponseParser: AssistantResponseParser,
    private val modelContextBuilder: ModelContextBuilder,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ChatUiState())
    private val speechPlaybackController = speechPlayback?.let {
        SpeechPlaybackController(context, it)
    }
    private var generationJob: Job? = null
    private var voiceDraftRestored = false
    private var openVoiceDraftAfterRestore = false
    private var activeVoiceInputSessionId: Long? = null
    private var paramsJob: Job? = null
    private var voiceModeShutdownJob: Job? = null
    private var previousSpeechPlaybackState: SpeechPlaybackState = SpeechPlaybackState.Idle
    private var isManualMessagePlayback = false

    val uiState: StateFlow<ChatUiState> = mutableUiState.asStateFlow()

    init {
        checkFirstRun()
        observeHistory()
        observeModelState()
        observeSettings()
        observeVoiceInput()
        observeVoiceInputErrors()
        observeSpeechPlayback()
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
        viewModelScope.launch {
            settingsRepository.smoothResponseEnabled.collect { enabled ->
                mutableUiState.update { it.copy(smoothResponseEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.systemPromptEnabled.collect { enabled ->
                mutableUiState.update { it.copy(systemPromptEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.dialogueModeEnabled.collect { enabled ->
                mutableUiState.update { it.copy(dialogueModeEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.autoPlaybackEnabled.collect { enabled ->
                mutableUiState.update { it.copy(autoPlaybackEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.speechRate.collect { rate ->
                mutableUiState.update { it.copy(speechRate = rate) }
            }
        }
        viewModelScope.launch {
            settingsRepository.speechVoice.collect { voice ->
                mutableUiState.update { it.copy(speechVoice = voice) }
            }
        }
        viewModelScope.launch {
            settingsRepository.chatScrollPosition.collect { position ->
                mutableUiState.update {
                    it.copy(
                        chatScrollPosition = position,
                        isChatScrollPositionLoaded = true,
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.floatingControlPositions.collect { positions ->
                mutableUiState.update {
                    it.copy(
                        floatingControlPositions = positions,
                        isFloatingControlPositionsLoaded = true,
                    )
                }
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
            val modelFiles = mutableSetOf<String>()
            modelsDir?.listFiles { file -> file.extension.equals("gguf", ignoreCase = true) }?.forEach {
                if (it.length() > 0) modelFiles.add(it.name) 
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
                    
                    val fileName = requireModelFileName(getFileName(uri))
                    
                    val totalSize = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("Не удалось открыть файл")
                    
                    val targetDir = context.getExternalFilesDir("models") ?: throw Exception("Папка приложения недоступна")
                    check(targetDir.exists() || targetDir.mkdirs()) { "Не удалось создать папку моделей" }
                    if (totalSize >= 0) {
                        require(totalSize <= MAX_MODEL_FILE_BYTES) { "Размер модели превышает 8 ГБ" }
                        require(totalSize <= targetDir.usableSpace) { "Недостаточно места для импорта модели" }
                    }
                    
                    val canonicalTargetDir = targetDir.canonicalFile
                    val targetFile = File(canonicalTargetDir, fileName).canonicalFile
                    require(targetFile.parentFile == canonicalTargetDir) { "Некорректный путь к модели" }
                    require(!targetFile.exists()) { "Модель с таким именем уже импортирована" }
                    val temporaryFile = File.createTempFile("model_", ".part", canonicalTargetDir)
                    val buffer = ByteArray(1024 * 1024) // 1MB buffer
                    var bytesCopied = 0L
                    try {
                        inputStream.use { input ->
                            FileOutputStream(temporaryFile).use { output ->
                                while (true) {
                                    val bytesRead = input.read(buffer)
                                    if (bytesRead == -1) break
                                    bytesCopied += bytesRead
                                    require(bytesCopied <= MAX_MODEL_FILE_BYTES) { "Размер модели превышает 8 ГБ" }
                                    output.write(buffer, 0, bytesRead)
                                    if (totalSize > 0) {
                                        val progress = bytesCopied.toFloat() / totalSize
                                        mutableUiState.update { it.copy(modelState = ModelState.Importing(progress)) }
                                    }
                                }
                            }
                        }
                        require(bytesCopied > 0) { "Файл модели пуст" }
                        check(temporaryFile.renameTo(targetFile)) { "Не удалось завершить импорт модели" }
                    } finally {
                        if (temporaryFile.exists()) temporaryFile.delete()
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

    private fun requireModelFileName(fileName: String?): String {
        val safeFileName = fileName?.trim().orEmpty()
        require(safeFileName.isNotBlank()) { "Не удалось определить имя файла модели" }
        require(safeFileName.length <= MAX_MODEL_FILE_NAME_LENGTH) { "Слишком длинное имя файла модели" }
        require(!safeFileName.contains('/') && !safeFileName.contains('\\')) { "Некорректное имя файла модели" }
        require(safeFileName.endsWith(".gguf", ignoreCase = true)) { "Поддерживаются только модели .gguf" }
        return safeFileName
    }

    private fun checkModelPresence(fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val privateFile = File(context.getExternalFilesDir("models"), fileName)
            val privateExists = privateFile.exists() && privateFile.length() > 0
            mutableUiState.update { it.copy(isModelMissing = !privateExists) }
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

    private fun sendMessageInternal(text: String, preserveVoiceMode: Boolean = false): Boolean {
        val trimmedText = text.trim()
        when {
            trimmedText.isEmpty() -> return false
            trimmedText.length > MAX_MESSAGE_LENGTH -> {
                mutableUiState.update { it.copy(error = "Сообщение не должно превышать 3000 символов") }
                return false
            }
            mutableUiState.value.isProcessing -> return false
        }

        stopVoiceInput()
        speechPlaybackController?.stop(SpeechStopReason.NewMessage)

        val userMessage = ChatMessage(role = MessageRole.USER, content = trimmedText)
        mutableUiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isProcessing = true,
                isStopping = false, // Сбрасываем флаг при новом сообщении
                isVoiceMode = preserveVoiceMode,
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
                val responseFlowResult = sendMessage(
                    modelContextBuilder.build(mutableUiState.value.messages),
                    useSystemPrompt = mutableUiState.value.systemPromptEnabled,
                )
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
                    withContext(Dispatchers.IO) { chatRepository.saveMessage(updatedAssistantMessage) }
                    assistantMessage = updatedAssistantMessage
                    updateMessage(updatedAssistantMessage)
                }

                // Сохраняем в БД только если есть контент
                val finalMessage = assistantMessage
                if (finalMessage != null) {
                    if (finalMessage.content.isNotBlank()) {
                        val parsed = assistantResponseParser.parse(finalMessage.content)
                        val messageToSave = if (parsed != null) {
                            finalMessage.copy(content = parsed.calendarReplyOrNull() ?: parsed.reply)
                        } else {
                            finalMessage
                        }

                        // Update UI with the reply if parsed
                        if (parsed != null) {
                            assistantMessage = messageToSave
                            updateMessage(messageToSave)
                        }

                        withContext(Dispatchers.IO) { chatRepository.saveMessage(messageToSave) }
                        
                        if (parsed != null) {
                            handleParsedResponse(parsed, messageToSave.id)
                        }
                    } else {
                        // Если сообщение пустое после завершения (например, сброс), удаляем из UI
                        mutableUiState.update { state ->
                            state.copy(messages = state.messages.filterNot { it.id == finalMessage.id })
                        }
                    }
                }

                val playbackStarted = if (mutableUiState.value.autoPlaybackEnabled) {
                    assistantMessage?.content
                        ?.takeIf(String::isNotBlank)
                        ?.let { content -> speechPlaybackController?.speak(content) }
                        ?: false
                } else {
                    false
                }
                if (!playbackStarted) {
                    viewModelScope.launch {
                        kotlinx.coroutines.yield()
                        resumeDialogueVoiceInput()
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
            
            stopGeneration(resumeDialogue = false)
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

        setVoiceMode(false)
        
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

        cancelPendingVoiceModeShutdown()
        if (state.isVoiceMode || state.voiceDraft.isRecording) stopVoiceInput()
        mutableUiState.update { current ->
            current.copy(
                isVoiceMode = false,
                voiceDraft = current.voiceDraft.copy(isVisible = true, isRecording = true),
                error = null,
            )
        }
        speechPlaybackController?.stop(SpeechStopReason.User)
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

    fun stopVoiceCaptureForBackground(isChangingConfigurations: Boolean) {
        if (isChangingConfigurations) return
        val state = mutableUiState.value
        speechPlaybackController?.stop(SpeechStopReason.Background)
        if (!state.isVoiceMode && !state.voiceDraft.isRecording) return

        stopVoiceInput()
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
        if (enabled) cancelPendingVoiceModeShutdown()
        mutableUiState.update { it.copy(isVoiceMode = enabled, error = null) }
        if (enabled && !mutableUiState.value.isProcessing) startVoiceInput()
        if (!enabled) {
            stopVoiceInput()
        }
    }

    fun stopGeneration(resumeDialogue: Boolean = true) {
        speechPlaybackController?.stop(SpeechStopReason.User)
        if (!uiState.value.isProcessing) return
        if (resumeDialogue && !uiState.value.dialogueModeEnabled) {
            setVoiceMode(false)
        }

        val activeGeneration = generationJob

        // Устанавливаем флаг остановки для изменения текста в UI
        mutableUiState.update { it.copy(isStopping = true) }
        
        // Прерываем native процесс
        llmEngine.cancelGeneration()
        // Отменяем корутину генерации
        activeGeneration?.cancel()
        // Возвращаем немедленный сброс флага обработки, как было раньше
        mutableUiState.update { it.copy(isProcessing = false) }
        // Останавливаем сервис переднего плана
        GenerationForegroundService.stop(context)
        if (resumeDialogue) {
            viewModelScope.launch {
                activeGeneration?.join()
                resumeDialogueVoiceInput()
            }
        }
    }

    fun clearChat() {
        val activeGeneration = generationJob
        val voiceDraft = mutableUiState.value.voiceDraft
        llmEngine.cancelGeneration()
        stopVoiceInput()
        speechPlaybackController?.stop(SpeechStopReason.User)
        activeGeneration?.cancel()
        GenerationForegroundService.stop(context)
        persistVoiceDraft(voiceDraft.text)

        viewModelScope.launch {
            activeGeneration?.join()
            withContext(Dispatchers.IO) { chatRepository.deleteAllMessages() }
            settingsRepository.setChatScrollPosition(ChatScrollPosition())
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

    fun stopSpeechPlayback() {
        isManualMessagePlayback = false
        speechPlaybackController?.stop(SpeechStopReason.User)
        resumeDialogueVoiceInput()
    }

    fun speakMessage(text: String) {
        if (text.isBlank()) return
        val controller = speechPlaybackController ?: return

        cancelPendingVoiceModeShutdown()
        isManualMessagePlayback = true
        stopVoiceInput()
        if (!controller.speak(text)) {
            isManualMessagePlayback = false
            resumeDialogueVoiceInput()
        }
    }

    private fun handleParsedResponse(response: com.example.aiassistent1.domain.model.AssistantResponse, messageId: String) {
        when (val params = response.params) {
            is com.example.aiassistent1.domain.model.CalendarAddParams -> {
                startCalendarEventDraft(params)
            }
            is com.example.aiassistent1.domain.model.CalendarSearchParams -> {
                viewModelScope.launch {
                    val range = runCatching {
                        val rangeStart = parseCalendarDateTime(params.rangeStart, "Начало диапазона поиска")
                        val rangeEnd = parseCalendarDateTime(params.rangeEnd, "Конец диапазона поиска")
                        require(rangeStart < rangeEnd) { "Начало диапазона поиска должно быть раньше его конца." }
                        rangeStart to rangeEnd
                    }.getOrElse { error ->
                        mutableUiState.update { it.copy(error = error.userMessage()) }
                        return@launch
                    }

                    searchCalendarEvents(params.query, range.first, range.second).onSuccess { events ->
                        val resultsText = if (events.isNotEmpty()) {
                            "\n\nНайдено:\n" + events.joinToString("\n") {
                                "- ${it.title} (${formatCalendarEventStart(it.startsAtEpochMillis)}, ${eventDurationMinutes(it.startsAtEpochMillis, it.endsAtEpochMillis)} мин)"
                            }
                        } else {
                            "\n\nНичего не найдено."
                        }
                        
                        val updatedMessage = ChatMessage(
                            id = messageId,
                            role = MessageRole.ASSISTANT,
                            content = response.reply + resultsText
                        )
                        updateMessage(updatedMessage)
                        withContext(Dispatchers.IO) { chatRepository.saveMessage(updatedMessage) }
                    }.onFailure { error ->
                        mutableUiState.update { it.copy(error = "Ошибка поиска: ${error.message}") }
                    }
                }
            }
            else -> {}
        }
    }

    fun setSmoothResponseEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSmoothResponseEnabled(enabled)
        }
    }

    fun setSystemPromptEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSystemPromptEnabled(enabled)
        }
    }

    fun setDialogueModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDialogueModeEnabled(enabled)
        }
    }

    fun setAutoPlaybackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoPlaybackEnabled(enabled)
        }
    }

    fun setSpeechRate(rate: Float) {
        viewModelScope.launch {
            settingsRepository.setSpeechRate(rate)
        }
    }

    fun saveChatScrollPosition(position: ChatScrollPosition) {
        viewModelScope.launch {
            settingsRepository.setChatScrollPosition(position)
        }
    }

    fun previewSpeechVoice(voice: com.example.aiassistent1.domain.model.SpeechVoice) {
        viewModelScope.launch {
            settingsRepository.setSpeechVoice(voice)
            speakMessage(SPEECH_VOICE_PREVIEW_TEXT)
        }
    }

    fun setSpeechVoice(voice: com.example.aiassistent1.domain.model.SpeechVoice) {
        stopSpeechPlayback()
        viewModelScope.launch { settingsRepository.setSpeechVoice(voice) }
    }

    fun saveFloatingControlPositions(positions: FloatingControlPositions) {
        viewModelScope.launch {
            settingsRepository.setFloatingControlPositions(positions)
        }
    }

    private fun com.example.aiassistent1.domain.model.AssistantResponse.calendarReplyOrNull(): String? {
        val params = params as? CalendarAddParams ?: return null
        val title = params.title ?: return null
        val startsAt = params.startsAt ?: return null
        val duration = params.durationMin ?: return null
        return runCatching {
            CalendarReplyTimeFormatter.formatCreationReply(title, startsAt, duration)
        }.getOrNull()
    }

    private fun startCalendarEventDraft(params: CalendarAddParams) {
        val draft = CalendarEventDraftUiState(
            title = params.title,
            startsAt = params.startsAt?.takeIf { isCalendarDateTime(it) },
            durationMinutes = params.durationMin,
        )
        mutableUiState.update { it.copy(calendarEventDraft = draft.withNextField()) }
    }

    fun updateCalendarDraftInput(value: String) {
        mutableUiState.update { state ->
            state.copy(calendarEventDraft = state.calendarEventDraft?.copy(input = value, error = null))
        }
    }

    fun submitCalendarDraftField() {
        val draft = uiState.value.calendarEventDraft ?: return
        val field = draft.activeField
        if (draft.isComplete) {
            confirmCalendarEventDraft(draft)
            return
        }
        if (field == null || draft.isFormatting) return

        validateCalendarField(field, draft.input)
            .onSuccess { value -> applyCalendarDraftField(field, value) }
            .onFailure {
                if (field == CalendarEventField.Title) {
                    setCalendarDraftError(it.message ?: "Введите название события.")
                } else {
                    formatCalendarDraftField(field, draft.input)
                }
            }
    }

    fun cancelCalendarEventDraft() {
        stopCalendarDraftVoiceInput()
        mutableUiState.update { it.copy(calendarEventDraft = null) }
    }

    fun startCalendarDraftVoiceInput() {
        val draft = uiState.value.calendarEventDraft ?: return
        if (draft.activeField == null || draft.isFormatting) return
        stopVoiceInput()
        val result = runCatching { voiceInput?.start() ?: error("Голосовой ввод недоступен.") }
        result.onSuccess { sessionId ->
            activeVoiceInputSessionId = sessionId
            mutableUiState.update { state ->
                state.copy(calendarEventDraft = state.calendarEventDraft?.copy(isVoiceInputActive = true, error = null))
            }
        }.onFailure { error -> setCalendarDraftError(error.userMessage()) }
    }

    private fun stopCalendarDraftVoiceInput() {
        if (uiState.value.calendarEventDraft?.isVoiceInputActive == true) stopVoiceInput()
        mutableUiState.update { state ->
            state.copy(calendarEventDraft = state.calendarEventDraft?.copy(isVoiceInputActive = false))
        }
    }

    private fun confirmCalendarEventDraft(draft: CalendarEventDraftUiState) {
        val title = draft.title ?: return
        val startsAt = draft.startsAt ?: return
        val duration = draft.durationMinutes ?: return
        mutableUiState.update { it.copy(calendarEventDraft = null) }
        executeCalendarAdd(title, startsAt, duration)
    }

    private fun applyCalendarDraftField(field: CalendarEventField, value: String) {
        mutableUiState.update { state ->
            val current = state.calendarEventDraft ?: return@update state
            val changed = when (field) {
                CalendarEventField.Title -> current.copy(title = value)
                CalendarEventField.StartsAt -> current.copy(startsAt = value)
                CalendarEventField.DurationMinutes -> current.copy(durationMinutes = value.toInt())
            }
            state.copy(calendarEventDraft = changed.withNextField())
        }
    }

    private fun setCalendarDraftError(message: String) {
        mutableUiState.update { state ->
            state.copy(calendarEventDraft = state.calendarEventDraft?.copy(error = message, isFormatting = false))
        }
    }

    private fun formatCalendarDraftField(field: CalendarEventField, rawValue: String) {
        if (rawValue.isBlank()) {
            setCalendarDraftError("Заполните поле «${field.label}».")
            return
        }
        mutableUiState.update { state ->
            state.copy(calendarEventDraft = state.calendarEventDraft?.copy(isFormatting = true, error = null))
        }
        viewModelScope.launch {
            val result = runCatching {
                llmEngine.ensureLoaded().getOrThrow()
                val request = ChatMessage(
                    role = MessageRole.USER,
                    content = """
                        {"intent":"calendar_field_format","field":"${field.modelName}","value":${JSONObject.quote(rawValue)},"expected_format":"${field.expectedFormat}","instruction":"Format only the requested field. Return JSON with exactly field and value. Do not change other event data."}
                    """.trimIndent(),
                )
                var response = ""
                llmEngine.generate(
                    listOf(
                        ChatMessage(
                            role = MessageRole.SYSTEM,
                            content = "Return only valid JSON. Never create, search, or modify calendar events.",
                        ),
                        request,
                    ),
                ).collect { response += it }
                parseFormattedCalendarField(field, response)
            }
            result.onSuccess { value -> applyCalendarDraftField(field, value) }
                .onFailure { error -> setCalendarDraftError(error.userMessage()) }
        }
    }

    private fun parseFormattedCalendarField(field: CalendarEventField, response: String): String {
        val jsonStart = response.indexOf('{')
        val jsonEnd = response.lastIndexOf('}')
        require(jsonStart >= 0 && jsonEnd > jsonStart) { "Модель не вернула JSON для форматирования поля." }
        val json = JSONObject(response.substring(jsonStart, jsonEnd + 1))
        require(json.optString("field") == field.modelName) { "Модель вернула другое поле." }
        val value = when (field) {
            CalendarEventField.DurationMinutes -> json.optInt("value", 0).toString()
            else -> json.optString("value")
        }
        return validateCalendarField(field, value).getOrThrow()
    }

    private fun validateCalendarField(field: CalendarEventField, rawValue: String): Result<String> = runCatching {
        when (field) {
            CalendarEventField.Title -> rawValue.trim().also { require(it.isNotEmpty()) { "Название события не может быть пустым." } }
            CalendarEventField.StartsAt -> LocalDateTime.parse(rawValue.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            CalendarEventField.DurationMinutes -> rawValue.trim().toInt().also {
                require(it > 0) { "Длительность должна быть больше нуля." }
            }.toString()
        }
    }

    private fun parseCalendarDateTime(value: String?, label: String): Long {
        require(!value.isNullOrBlank()) { "$label не указано." }
        return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    private fun isCalendarDateTime(value: String): Boolean =
        runCatching { LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }.isSuccess

    private fun executeCalendarAdd(title: String, date: String, duration: Int) {
        viewModelScope.launch {
            val draft = toCalendarEventDraft(title, date, duration).getOrElse { error ->
                mutableUiState.update { it.copy(error = error.userMessage()) }
                return@launch
            }

            createCalendarEvent(draft)
                .onSuccess {
                    mutableUiState.update { it.copy(snackbarMessage = "Событие сохранено в локальном календаре") }
                }
                .onFailure { error ->
                    mutableUiState.update { it.copy(error = error.userMessage()) }
                }
        }
    }

    private fun toCalendarEventDraft(
        title: String,
        date: String,
        durationMinutes: Int,
    ): Result<CalendarEventDraft> = runCatching {
        require(durationMinutes > 0) { "Длительность события должна быть больше нуля." }
        val start = LocalDateTime.parse(date, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val durationMillis = Math.multiplyExact(durationMinutes.toLong(), MILLIS_PER_MINUTE)
        CalendarEventDraft(
            title = title,
            startsAtEpochMillis = start,
            endsAtEpochMillis = Math.addExact(start, durationMillis),
        )
    }

    private fun formatCalendarEventStart(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    private fun eventDurationMinutes(startEpochMillis: Long, endEpochMillis: Long): Long =
        (endEpochMillis - startEpochMillis) / MILLIS_PER_MINUTE

    override fun onCleared() {
        cancelPendingVoiceModeShutdown()
        stopGeneration(resumeDialogue = false)
        stopVoiceInput()
        (voiceInput as? AutoCloseable)?.close()
        speechPlaybackController?.close()
        llmEngine.close()
    }

    private fun observeSpeechPlayback() {
        val controller = speechPlaybackController ?: return
        viewModelScope.launch {
            controller.state.collect { playbackState ->
                mutableUiState.update { it.copy(speechPlaybackState = playbackState) }
                if (isManualMessagePlayback &&
                    (playbackState is SpeechPlaybackState.Idle || playbackState is SpeechPlaybackState.Error)
                ) {
                    isManualMessagePlayback = false
                    if (uiState.value.dialogueModeEnabled && uiState.value.isVoiceMode &&
                        !uiState.value.isProcessing
                    ) {
                        startVoiceInput()
                    } else {
                        scheduleVoiceModeShutdown()
                    }
                } else if (previousSpeechPlaybackState is SpeechPlaybackState.Playing &&
                    playbackState is SpeechPlaybackState.Idle
                ) {
                    if (uiState.value.dialogueModeEnabled && uiState.value.isVoiceMode &&
                        !uiState.value.isProcessing
                    ) {
                        startVoiceInput()
                    } else {
                        scheduleVoiceModeShutdown()
                    }
                } else if (playbackState !is SpeechPlaybackState.Idle) {
                    cancelPendingVoiceModeShutdown()
                }
                previousSpeechPlaybackState = playbackState
            }
        }
    }

    private fun scheduleVoiceModeShutdown() {
        cancelPendingVoiceModeShutdown()
        voiceModeShutdownJob = viewModelScope.launch {
            delay(VOICE_MODE_SHUTDOWN_DELAY_MILLIS)
            if (uiState.value.speechPlaybackState is SpeechPlaybackState.Idle) {
                setVoiceMode(false)
            }
        }
    }

    private fun cancelPendingVoiceModeShutdown() {
        voiceModeShutdownJob?.cancel()
        voiceModeShutdownJob = null
    }

    private fun resumeDialogueVoiceInput() {
        val state = uiState.value
        if (!state.dialogueModeEnabled || !state.isVoiceMode || state.isProcessing) return

        cancelPendingVoiceModeShutdown()
        startVoiceInput()
    }

    private fun observeVoiceInput() {
        val inputProvider = voiceInput ?: return
        viewModelScope.launch {
            inputProvider.observeInput().collect { event ->
                if (event.sessionId != activeVoiceInputSessionId) return@collect
                val state = mutableUiState.value
                when {
                    state.calendarEventDraft?.isVoiceInputActive == true -> {
                        updateCalendarDraftInput(event.transcript)
                        stopCalendarDraftVoiceInput()
                    }
                    state.voiceDraft.isRecording && !state.isProcessing -> {
                        appendVoiceDraftTranscript(event.transcript)
                    }
                    state.isVoiceMode && !state.isProcessing -> {
                        sendMessageInternal(event.transcript, preserveVoiceMode = state.dialogueModeEnabled)
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
                if (!state.isVoiceMode && !state.voiceDraft.isRecording &&
                    state.calendarEventDraft?.isVoiceInputActive != true
                ) return@collect

                mutableUiState.update {
                    it.copy(
                        error = event.cause.message ?: "Не удалось обработать голосовой ввод",
                        isVoiceMode = false,
                        voiceDraft = it.voiceDraft.copy(isRecording = false),
                        calendarEventDraft = it.calendarEventDraft?.copy(isVoiceInputActive = false),
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
            mutableUiState.update { it.copy(error = "Голосовой черновик ограничен 3000 символами") }
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
            mutableUiState.update { it.copy(error = "Голосовой черновик ограничен 3000 символами") }
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
                    state.copy(
                        messages = persistedMessages + pendingMessages,
                        isHistoryLoaded = true,
                    )
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
        const val SPEECH_VOICE_PREVIEW_TEXT = "Здравствуйте! Это пример звучания выбранного голоса."
        const val MAX_MODEL_FILE_NAME_LENGTH = 128
        const val MAX_MODEL_FILE_BYTES = 8L * 1024 * 1024 * 1024
        const val VOICE_MODE_SHUTDOWN_DELAY_MILLIS = 1_000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val MILLIS_PER_DAY = 24L * 60L * MILLIS_PER_MINUTE
    }
}
