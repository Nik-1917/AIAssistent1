package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.aiassistent1.domain.model.AssistantDisplayName

@Composable
internal fun AssistantNameSettings(currentName: String, enabled: Boolean, onSave: (String) -> Unit) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    OutlinedTextField(value = name, onValueChange = { if (it.length <= AssistantDisplayName.MAX_LENGTH) name = it },
        label = { Text("Имя ассистента") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("Имя в хедере. Запись слова v3 заполняет его автоматически. Правка имени не меняет слово активации и проверку голоса. Пустое поле возвращает AI Assistant.",
        style = MaterialTheme.typography.bodySmall)
    TextButton(enabled = enabled && name != currentName, onClick = { onSave(name) }) {
        Text("Сохранить имя")
    }
}
