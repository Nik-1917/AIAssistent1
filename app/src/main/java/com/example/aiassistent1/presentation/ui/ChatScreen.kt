package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val lastMessage = uiState.messages.lastOrNull()

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    LaunchedEffect(lastMessage?.id, lastMessage?.content) {
        if (lastMessage != null && listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            ?.let { it >= uiState.messages.lastIndex - 1 } != false
        ) {
            listState.scrollToItem(uiState.messages.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ChatTopBar(
                modelState = uiState.modelState,
                isProcessing = uiState.isProcessing,
                hasMessages = uiState.messages.isNotEmpty(),
                onStop = viewModel::stopGeneration,
                onClearChat = viewModel::clearChat,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            InputPanel(
                enabled = !uiState.isProcessing && uiState.modelState !is ModelState.Loading,
                isProcessing = uiState.isProcessing,
                onSend = viewModel::sendMessage,
                onStop = viewModel::stopGeneration,
            )
        },
    ) { paddingValues ->
        if (uiState.messages.isEmpty()) {
            EmptyConversation(modifier = Modifier.padding(paddingValues))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatTopBar(
    modelState: ModelState,
    isProcessing: Boolean,
    hasMessages: Boolean,
    onStop: () -> Unit,
    onClearChat: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(text = "AI Assistant")
                Text(
                    text = modelState.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
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
private fun EmptyConversation(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Напишите сообщение, чтобы начать разговор",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        Card(
            modifier = Modifier.fillMaxWidth(0.84f),
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_MESSAGE_LENGTH) },
            modifier = Modifier.weight(1f),
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
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = "Остановить генерацию")
            }
        } else {
            IconButton(
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
    is ModelState.Error -> "Модель недоступна"
}

private const val MAX_MESSAGE_LENGTH = 500