package com.example.aiassistent1.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel
import com.example.aiassistent1.presentation.viewmodel.VoiceDraftState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val lastMessage = uiState.messages.lastOrNull()
    val viewportEndOffset = listState.layoutInfo.viewportEndOffset
    var pendingMicrophoneAction by remember { mutableStateOf<MicrophoneAction?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Логика умной прокрутки: отключаем автопрокрутку, если пользователь ушел вверх
    var shouldAutoScroll by remember { mutableStateOf(true) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            // Если пользователь начал скроллить вручную, проверяем, находится ли он внизу
            val isAtBottom = !listState.canScrollForward
            if (!isAtBottom) {
                shouldAutoScroll = false
            }
        }
    }

    LaunchedEffect(listState.canScrollForward) {
        // Если пользователь вернулся в самый низ, включаем автопрокрутку обратно
        if (!listState.canScrollForward) {
            shouldAutoScroll = true
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importModel(it) }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingMicrophoneAction
        pendingMicrophoneAction = null
        if (granted) {
            when (action) {
                MicrophoneAction.TAP -> viewModel.onMicrophoneTap()
                MicrophoneAction.LONG_PRESS -> viewModel.startVoiceDraft()
                null -> Unit
            }
        }
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        viewModel.onCalendarPermissionResult(allGranted)
    }

    LaunchedEffect(uiState.needsCalendarPermission) {
        if (uiState.needsCalendarPermission) {
            calendarPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                )
            )
        }
    }

    val requestMicrophoneAction: (MicrophoneAction) -> Unit = { action ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            when (action) {
                MicrophoneAction.TAP -> viewModel.onMicrophoneTap()
                MicrophoneAction.LONG_PRESS -> viewModel.startVoiceDraft()
            }
        } else {
            pendingMicrophoneAction = action
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshModelStatus()
            }
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.stopVoiceCaptureForBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var isExtendingScreenOn by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isProcessing) {
        if (!uiState.isProcessing) {
            isExtendingScreenOn = true
            delay(5000) // Удерживаем экран включенным еще 5 секунд после завершения печати
            isExtendingScreenOn = false
        }
    }

    DisposableEffect(view, uiState.voiceDraft.isRecording, uiState.isProcessing, isExtendingScreenOn) {
        view.keepScreenOn = uiState.voiceDraft.isRecording || uiState.isProcessing || isExtendingScreenOn
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearSnackbar()
        }
    }

    LaunchedEffect(lastMessage?.id) {
        // При появлении нового сообщения (ID изменился) всегда включаем автопрокрутку
        if (lastMessage != null) {
            shouldAutoScroll = true
        }
    }

    LaunchedEffect(lastMessage?.id, lastMessage?.content, viewportEndOffset) {
        if (lastMessage != null && shouldAutoScroll) {
            listState.scrollToItem(uiState.messages.lastIndex, Int.MAX_VALUE)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ChatTopBar(
                modelState = uiState.modelState,
                isProcessing = uiState.isProcessing,
                hasMessages = uiState.messages.isNotEmpty(),
                isModelMissing = uiState.isModelMissing,
                needsPermission = uiState.needsPermission,
                selectedModel = uiState.selectedModel,
                availableModels = uiState.availableModels,
                onStop = viewModel::stopGeneration,
                onClearChat = viewModel::clearChat,
                onLoadModel = { 
                    if (uiState.needsPermission) viewModel.openPermissionSettings()
                    else filePickerLauncher.launch("*/*")
                },
                onSelectModel = viewModel::selectModel,
                onOpenSettings = { showSettingsDialog = true }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            InputPanel(
                textInputEnabled = !uiState.isProcessing &&
                    uiState.modelState !is ModelState.Loading &&
                    !uiState.isModelMissing &&
                    !uiState.voiceDraft.isVisible,
                microphoneEnabled = !uiState.isProcessing &&
                    uiState.modelState !is ModelState.Loading &&
                    !uiState.isModelMissing,
                isProcessing = uiState.isProcessing,
                isVoiceMode = uiState.isVoiceMode,
                onSend = viewModel::sendMessage,
                onStop = viewModel::stopGeneration,
                onVoiceTap = { requestMicrophoneAction(MicrophoneAction.TAP) },
                onVoiceLongPress = { requestMicrophoneAction(MicrophoneAction.LONG_PRESS) },
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(bottom = paddingValues.calculateBottomPadding() + 10.dp)
                    .fillMaxSize(),
            ) {
                val modelState = uiState.modelState

                if (modelState is ModelState.Importing) {
                    ImportProgress(progress = modelState.progress)
                }

                if (uiState.messages.isEmpty()) {
                    EmptyConversation(
                        isModelMissing = uiState.isModelMissing,
                        needsPermission = uiState.needsPermission,
                        onLoadModel = { filePickerLauncher.launch("*/*") },
                        onOpenSettings = viewModel::openPermissionSettings
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = paddingValues.calculateTopPadding() + 12.dp,
                            end = 16.dp,
                            bottom = 36.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(
                            items = uiState.messages,
                            key = { _, message -> message.id },
                        ) { index, message ->
                            val isLast = index == uiState.messages.lastIndex
                            val showRetry = isLast && !uiState.isProcessing
                                
                            MessageBubble(
                                message = message,
                                onRetry = if (showRetry) viewModel::retry else null,
                                onCopy = { text ->
                                    viewModel.copyToClipboard(text)
                                }
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = uiState.voiceDraft.isVisible,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = paddingValues.calculateBottomPadding() + 8.dp,
                    ),
                enter = expandVertically(expandFrom = Alignment.Bottom),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
            ) {
                 VoiceDraftCard(
                    state = uiState.voiceDraft,
                    onDelete = viewModel::deleteVoiceDraft,
                    onSend = viewModel::sendVoiceDraft,
                )
            }
        }
    }

    if (showSettingsDialog) {
        ModelSettingsDialog(
            modelName = uiState.selectedModel,
            params = uiState.modelParams,
            onDismiss = { showSettingsDialog = false },
            onSave = { updatedParams ->
                viewModel.updateModelParams(updatedParams)
                showSettingsDialog = false
            }
        )
    }
}

@Composable
private fun ImportProgress(progress: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Копирование модели: ${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatTopBar(
    modelState: ModelState,
    isProcessing: Boolean,
    hasMessages: Boolean,
    isModelMissing: Boolean,
    needsPermission: Boolean,
    selectedModel: String,
    availableModels: List<String>,
    onStop: () -> Unit,
    onClearChat: () -> Unit,
    onLoadModel: () -> Unit,
    onSelectModel: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column(
                modifier = Modifier.clickable { showMenu = true }
            ) {
                Text(text = "AI Assistant")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isModelMissing) "Модель не найдена" else modelState.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "($selectedModel)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                onSelectModel(model)
                                showMenu = false
                            },
                            trailingIcon = {
                                if (model == selectedModel) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            }
                        )
                    }
                }
            }
        },
        actions = {
            if (isModelMissing && !needsPermission && modelState !is ModelState.Importing) {
                IconButton(onClick = onLoadModel) {
                    Icon(Icons.Default.CloudUpload, contentDescription = "Загрузить модель")
                }
            }
            if (needsPermission) {
                IconButton(onClick = onLoadModel) {
                    Icon(Icons.Default.CloudUpload, contentDescription = "Выдать разрешение", tint = MaterialTheme.colorScheme.error)
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Настройки модели")
            }
            if (hasMessages) {
                IconButton(onClick = onClearChat) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить чат")
                }
            }
            if (isProcessing) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Остановить генерацию")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun EmptyConversation(
    isModelMissing: Boolean,
    needsPermission: Boolean,
    onLoadModel: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (isModelMissing) "Для начала работы нужно загрузить модель" else "Напишите сообщение, чтобы начать разговор",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            if (isModelMissing) {
                Spacer(modifier = Modifier.height(16.dp))
                if (needsPermission) {
                    Text(
                        text = "Файл найден в папке 'Загрузки',\nно нужно разрешение на доступ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onOpenSettings) {
                        Text("Выдать разрешение")
                    }
                } else {
                    Text(
                        text = "Если файл уже в папке 'Загрузки', проверьте разрешения приложения",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onLoadModel) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Выбрать файл .gguf")
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onRetry: (() -> Unit)? = null,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleShape = RoundedCornerShape(12.dp)
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val haptic = LocalHapticFeedback.current

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .graphicsLayer {
                    shape = bubbleShape
                    clip = true
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCopy(message.content)
                        }
                    )
                }
                .animateContentSize(animationSpec = tween(durationMillis = 120)),
            shape = bubbleShape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = if (isUser) "Вы" else "Ассистент",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message.content.ifBlank { "Генерация ответа..." },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (message.isInterrupted) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Генерация остановлена",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                if (onRetry != null) {
                    IconButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Повторить генерацию",
                            tint = Color(0xFF2196F3),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InputPanel(
    textInputEnabled: Boolean,
    microphoneEnabled: Boolean,
    isProcessing: Boolean,
    isVoiceMode: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onVoiceTap: () -> Unit,
    onVoiceLongPress: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val canSend = textInputEnabled && text.trim().isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_MESSAGE_LENGTH) },
            modifier = Modifier
                .weight(1f)
                .offset(y = (-15).dp),
            enabled = textInputEnabled,
            placeholder = { Text("Сообщение") },
            supportingText = {
                Text(
                    text = "${text.length}/$MAX_MESSAGE_LENGTH",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    fontSize = 12.sp,
                )
            },
            shape = RoundedCornerShape(12.dp),
            maxLines = 4,
        )
        Spacer(modifier = Modifier.width(8.dp))
        VoiceMicrophoneButton(
            modifier = Modifier.offset(y = (-45).dp),
            enabled = microphoneEnabled,
            isVoiceMode = isVoiceMode,
            onTap = onVoiceTap,
            onLongPress = onVoiceLongPress,
        )
        if (isProcessing) {
            IconButton(
                modifier = Modifier.offset(y = (-40).dp),
                onClick = onStop,
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Остановить генерацию")
            }
        } else {
            IconButton(
                modifier = Modifier.offset(y = (-45).dp),
                enabled = canSend,
                onClick = {
                    onSend(text)
                    text = ""
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить сообщение")
            }
        }
    }
}

@Composable
private fun VoiceDraftCard(
    state: VoiceDraftState,
    onDelete: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(state.text) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isRecording) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (state.isRecording) "Запись" else "Голосовой черновик",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Удалить голосовой черновик",
                    )
                }
            }
            Text(
                text = state.text.ifBlank { "Говорите..." },
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.text.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp, max = 144.dp)
                    .verticalScroll(scrollState)
                    .padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    enabled = state.text.isNotBlank(),
                    onClick = onSend,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Отправить голосовой черновик",
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceMicrophoneButton(
    enabled: Boolean,
    isVoiceMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val hapticFeedback = LocalHapticFeedback.current
    val holdProgress by animateFloatAsState(
        targetValue = if (isPressed && !longPressTriggered) 1f else 0f,
        animationSpec = tween(durationMillis = VOICE_DRAFT_LONG_PRESS_TIMEOUT_MILLIS.toInt()),
        label = "voiceDraftHoldProgress",
    )
    val contentDescription = if (isVoiceMode) {
        "Выключить голосовой режим"
    } else {
        "Включить голосовой режим или удерживать для голосового черновика"
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                onClick { currentOnTap(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    longPressTriggered = false
                    var gestureFinished = false
                    val up = withTimeoutOrNull(VOICE_DRAFT_LONG_PRESS_TIMEOUT_MILLIS) {
                        waitForUpOrCancellation().also { gestureFinished = true }
                    }
                    if (!gestureFinished) {
                        longPressTriggered = true
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnLongPress()
                        waitForUpOrCancellation()
                    } else if (up != null) {
                        currentOnTap()
                    }
                    isPressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isPressed && !longPressTriggered) {
            CircularProgressIndicator(
                progress = { holdProgress },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 2.dp,
            )
        }
        Icon(
            imageVector = if (isVoiceMode) Icons.Default.MicOff else Icons.Default.Mic,
            contentDescription = null,
        )
    }
}

@Composable
fun ModelSettingsDialog(
    modelName: String,
    params: GenerationParams,
    onDismiss: () -> Unit,
    onSave: (GenerationParams) -> Unit,
) {
    var temperature by remember { mutableStateOf(params.temperature) }
    var contextSize by remember { mutableStateOf(params.contextSize.toFloat()) }
    var maxTokens by remember { mutableStateOf(params.maxTokens.toFloat()) }
    var topP by remember { mutableStateOf(params.topP) }
    var repeatPenalty by remember { mutableStateOf(params.repeatPenalty) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Настройки для $modelName",
                    style = MaterialTheme.typography.titleLarge
                )

                SettingSlider(
                    label = "Temperature: ${String.format("%.2f", temperature)}",
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f
                )

                SettingSlider(
                    label = "Context Size: ${contextSize.toInt()}",
                    value = contextSize,
                    onValueChange = { contextSize = it },
                    valueRange = 512f..8192f,
                    steps = 15
                )

                SettingSlider(
                    label = "Max Tokens: ${maxTokens.toInt()}",
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    valueRange = 64f..2048f,
                    steps = 30
                )

                SettingSlider(
                    label = "Top P: ${String.format("%.2f", topP)}",
                    value = topP,
                    onValueChange = { topP = it },
                    valueRange = 0f..1f
                )

                SettingSlider(
                    label = "Repeat Penalty: ${String.format("%.2f", repeatPenalty)}",
                    value = repeatPenalty,
                    onValueChange = { repeatPenalty = it },
                    valueRange = 1f..2f
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val default = GenerationParams()
                            temperature = default.temperature
                            contextSize = default.contextSize.toFloat()
                            maxTokens = default.maxTokens.toFloat()
                            topP = default.topP
                            repeatPenalty = default.repeatPenalty
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(Color(0xFF2196F3), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "сброс настроек по умолчанию",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text("Отмена")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        onSave(params.copy(
                            temperature = temperature,
                            contextSize = contextSize.toInt(),
                            maxTokens = maxTokens.toInt(),
                            topP = topP,
                            repeatPenalty = repeatPenalty
                        ))
                    }) {
                        Text("Сохранить")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0
) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}

private enum class MicrophoneAction {
    TAP,
    LONG_PRESS,
}

private fun ModelState.label(): String = when (this) {
    ModelState.Unloaded -> "Модель будет загружена при первом сообщении"
    ModelState.Loading -> "Загрузка модели"
    ModelState.Ready -> "Модель готова"
    is ModelState.Error -> "Ошибка: ${this.message}"
    is ModelState.Importing -> "Импорт..."
}

private const val MAX_MESSAGE_LENGTH = 500
private const val VOICE_DRAFT_LONG_PRESS_TIMEOUT_MILLIS = 2_000L
