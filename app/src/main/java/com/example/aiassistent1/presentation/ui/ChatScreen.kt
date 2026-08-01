package com.example.aiassistent1.presentation.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val lastMessage = uiState.messages.lastOrNull()
    val bottomMessageClearancePx = with(LocalDensity.current) { MESSAGE_BOTTOM_CLEARANCE.toPx() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importModel(it) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshModelStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    LaunchedEffect(lastMessage?.id, uiState.isProcessing) {
        while (lastMessage != null && uiState.isProcessing) {
            val layoutInfo = listState.layoutInfo
            val lastItem = layoutInfo.visibleItemsInfo.lastOrNull {
                it.index == uiState.messages.lastIndex
            }
            if (lastItem == null) {
                listState.scrollToItem(uiState.messages.lastIndex, Int.MAX_VALUE)
            } else {
                val overflow = lastItem.offset + lastItem.size -
                    (layoutInfo.viewportEndOffset - bottomMessageClearancePx)
                if (overflow > 0) {
                    listState.animateScrollBy(
                        value = overflow.toFloat(),
                        animationSpec = tween(durationMillis = AUTO_SCROLL_INTERVAL_MILLIS.toInt()),
                    )
                }
            }
            delay(AUTO_SCROLL_INTERVAL_MILLIS)
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
                onStop = viewModel::stopGeneration,
                onClearChat = viewModel::clearChat,
                onLoadModel = { 
                    if (uiState.needsPermission) viewModel.openPermissionSettings()
                    else filePickerLauncher.launch("*/*")
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            InputPanel(
                enabled = !uiState.isProcessing && uiState.modelState !is ModelState.Loading && !uiState.isModelMissing,
                isProcessing = uiState.isProcessing,
                onSend = viewModel::sendMessage,
                onStop = viewModel::stopGeneration,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(bottom = paddingValues.calculateBottomPadding() + 10.dp)
                .fillMaxSize(),
        ) {
            val modelState = uiState.modelState
            
            if (uiState.needsPermission || (modelState is ModelState.Error && 
                (modelState.message.contains("разрешен", ignoreCase = true) || 
                 modelState.message.contains("доступ", ignoreCase = true)))) {
                PermissionErrorBanner(onOpenSettings = viewModel::openPermissionSettings)
            }

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
                    items(
                        items = uiState.messages,
                        key = { message -> message.id },
                    ) { message ->
                        MessageBubble(message = message)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionErrorBanner(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Нужно разрешение на доступ к файлам",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Файл модели находится в папке 'Загрузки'. Чтобы прочитать его, приложению требуется специальный доступ.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Перейти в настройки")
            }
        }
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
    onStop: () -> Unit,
    onClearChat: () -> Unit,
    onLoadModel: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(text = "AI Assistant")
                Text(
                    text = if (isModelMissing) "Модель не найдена" else modelState.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .graphicsLayer {
                    shape = bubbleShape
                    clip = true
                }
                .animateContentSize(animationSpec = tween(durationMillis = 120)),
            shape = bubbleShape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
        ) {
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
        }
    }
}

@Composable
private fun InputPanel(
    enabled: Boolean,
    isProcessing: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val canSend = enabled && text.trim().isNotEmpty()

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
            enabled = enabled,
            placeholder = { Text("Сообщение") },
            supportingText = {
                Text(
                    text = "${text.length}/$MAX_MESSAGE_LENGTH",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    fontSize = 12.sp,
                )
            },
            maxLines = 4,
        )
        Spacer(modifier = Modifier.width(8.dp))
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

private fun ModelState.label(): String = when (this) {
    ModelState.Unloaded -> "Модель будет загружена при первом сообщении"
    ModelState.Loading -> "Загрузка модели"
    ModelState.Ready -> "Модель готова"
    is ModelState.Error -> "Ошибка: ${this.message}"
    is ModelState.Importing -> "Импорт..."
}

private const val MAX_MESSAGE_LENGTH = 500
private const val AUTO_SCROLL_INTERVAL_MILLIS = 160L
private val MESSAGE_BOTTOM_CLEARANCE = 24.dp