package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.aiassistent1.domain.model.*

@Composable
fun ChatContextDialog(pressure: ChatContextPressure, onChoice: (String, ChatContextChoice) -> Unit) {
    key(pressure.id) {
        var manual by rememberSaveable { mutableStateOf(false) }
        var rememberChoice by rememberSaveable { mutableStateOf(false) }
        var selectedIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        val cancel = { onChoice(pressure.id, ChatContextChoice.Cancel) }
        Dialog(onDismissRequest = cancel) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState())
                    .padding(20.dp).testTag("chat_context_pressure"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (manual) "Выбрать сообщения вручную" else when (pressure.reason) {
                        ContextCapacityReason.MEMORY -> "Недостаточно памяти для увеличения контекста"
                        ContextCapacityReason.MODEL_LIMIT -> "Достигнут предел контекста модели"
                    }, style = MaterialTheme.typography.titleLarge)
                    Text("История этого чата стала слишком большой. Чтобы оставить не менее 1024 токенов для ответа, " +
                        "нужно исключить часть старых сообщений из контекста модели.\nСами сообщения останутся в чате.")
                    if (manual) {
                        Text("Выберите диалоги целиком. Текущий запрос сохранится в контексте.",
                            style = MaterialTheme.typography.bodySmall)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(pressure.turns, key = { it.id }) { turn ->
                                val checked = turn.id in selectedIds
                                val toggle = {
                                    selectedIds = ArrayList(if (checked) selectedIds - turn.id else selectedIds + turn.id)
                                }
                                OutlinedCard(Modifier.fillMaxWidth().testTag("context_turn_${turn.id}").clickable(onClick = toggle)) {
                                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
                                        Checkbox(checked = checked, onCheckedChange = { toggle() })
                                        Column(Modifier.weight(1f).padding(top = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(turn.messages.first().content, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                            turn.messages.drop(1).firstOrNull()?.let { answer ->
                                                Text(answer.content, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Text("Выбрано диалогов: ${selectedIds.size}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth().clickable { rememberChoice = !rememberChoice },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                        Text("В дальнейшем делать это автоматически", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (manual) {
                        Button(onClick = { onChoice(pressure.id, ChatContextChoice.Manual(selectedIds.toSet(), rememberChoice)) },
                            enabled = selectedIds.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("context_apply")) {
                            Text("Применить и продолжить")
                        }
                        OutlinedButton(onClick = { manual = false }, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
                    } else {
                        Button(onClick = { onChoice(pressure.id, ChatContextChoice.Automatic(rememberChoice)) },
                            modifier = Modifier.fillMaxWidth().testTag("context_auto")) { Text("Освобождать автоматически") }
                        OutlinedButton(onClick = { manual = true },
                            modifier = Modifier.fillMaxWidth().testTag("context_manual")) { Text("Выбрать сообщения вручную") }
                    }
                    TextButton(onClick = cancel, modifier = Modifier.align(Alignment.End).testTag("context_cancel")) {
                        Text("Отмена")
                    }
                }
            }
        }
    }
}

@Composable
fun ChatHistorySettings(policy: ChatHistoryPolicy, onPolicyChange: (ChatHistoryPolicy) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("История при заполнении контекста", style = MaterialTheme.typography.titleSmall)
        ChatHistoryPolicy.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().clickable { onPolicyChange(option) },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = option == policy, onClick = { onPolicyChange(option) })
                Text(when (option) {
                    ChatHistoryPolicy.ASK -> "Спрашивать меня"
                    ChatHistoryPolicy.AUTOMATIC -> "Автоматически исключать старые сообщения"
                }, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("Перед ответом сохраняется место не менее чем для 1024 токенов. Сначала приложение пробует увеличить контекст. " +
            "Исключённые диалоги остаются в чате.", style = MaterialTheme.typography.bodySmall)
    }
}
