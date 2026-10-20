package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.aiassistent1.presentation.viewmodel.SavedTextDraftsState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedTextDraftSheet(
    state: SavedTextDraftsState,
    isWorkingDraftBusy: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val formatter = remember {
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
    }
    val enabled = !state.isBusy && !isWorkingDraftBusy
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Дневники", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
            if (state.error != null) {
                Text(state.error, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry, enabled = enabled) { Text("Повторить") }
            }
            if (!state.isLoaded && state.error == null) {
                CircularProgressIndicator(Modifier.padding(16.dp))
            } else if (state.isLoaded && state.drafts.isEmpty()) {
                Text("Сохранённых черновиков пока нет", Modifier.padding(vertical = 16.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.drafts, key = { it.id }) { draft ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(formatter.format(Instant.ofEpochMilli(draft.createdAtEpochMillis)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(draft.text, modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodyLarge)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onOpen(draft.id) }, enabled = enabled) { Text("Открыть") }
                                IconButton(onClick = { onDelete(draft.id) }, enabled = enabled) {
                                    Icon(Icons.Default.Delete, contentDescription = "Удалить текстовый черновик")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
