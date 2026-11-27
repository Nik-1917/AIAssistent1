package com.example.aiassistent1.presentation.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.domain.model.StoredModel
import com.example.aiassistent1.presentation.viewmodel.ChatUiState
import com.example.aiassistent1.presentation.viewmodel.ModelAvailability
import com.example.aiassistent1.presentation.viewmodel.ModelStartup

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ModelStartupDialog(
    state: ChatUiState,
    onStart: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
) {
    val startup = state.modelStartup
    var dismissed by rememberSaveable(startup, state.selectedModel, state.modelStartupDialogRequestId) {
        mutableStateOf(false)
    }
    if (startup == ModelStartup.Ready || state.isProcessing || dismissed) return
    val loading = startup as? ModelStartup.Loading
    val importing = state.modelState as? ModelState.Importing
    val modelLabel = startupModelLabel(LocalContext.current, loading?.fileName ?: state.selectedModel, state.availableModels)
    var choosing by rememberSaveable { mutableStateOf(false) }
    var actionRowHeightPx by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = { dismissed = true },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        loading == null && importing != null -> "Импортируем модель"
                        else -> "ИИ не запущен"
                    },
                    modifier = Modifier.weight(1f),
                )
                // The icon defines the layout edge; Compose still expands its touch target to 48 dp.
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    IconButton(
                        onClick = { dismissed = true },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Закрыть окно запуска ИИ",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    loading == null && importing != null -> LinearProgressIndicator(
                        progress = { importing.progress }, modifier = Modifier.fillMaxWidth(),
                    )
                    else -> {
                        Text("Первый запуск может занять до минуты.")
                        if (modelLabel.isNotBlank()) {
                            Text(
                                "Выбрана модель - $modelLabel",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (startup is ModelStartup.Failed) {
                            Text(
                                startup.message,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (loading != null) {
                val actionHeight = with(LocalDensity.current) { actionRowHeightPx.toDp() }.coerceAtLeast(48.dp)
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = actionHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Запуск модели" },
                    )
                }
            } else if (importing == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().onSizeChanged { actionRowHeightPx = it.height },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1.5f, fill = false)) {
                        TextButton(
                            onClick = { choosing = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(start = 0.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
                        ) {
                            Text("Выбрать модель", maxLines = 1, autoSize = actionLabelSize)
                        }
                        ModelPickerMenu(
                            expanded = choosing, onDismiss = { choosing = false }, modelState = state.modelState,
                            selectedModel = state.selectedModel, availableModels = state.availableModels,
                            onSelectModel = onSelect, onDeleteModel = onDelete, onLoadModel = onImport,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp),
                        enabled = state.modelAvailability != ModelAvailability.Checking,
                        onClick = { if (state.modelAvailability == ModelAvailability.Available) onStart() else choosing = true },
                        contentPadding = actionPadding,
                    ) {
                        Text("Запустить", maxLines = 1, autoSize = actionLabelSize)
                    }
                }
            }
        },
    )
}

private val actionPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
private val actionLabelSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 14.sp)
private val qwen3PointSixBName = Regex("""(?:^|[-_ ])Qwen3-0\.?6B(?:$|[-_ ])""", RegexOption.IGNORE_CASE)

private fun startupModelLabel(context: Context, fileName: String, models: List<StoredModel>): String {
    val model = models.firstOrNull { it.fileName == fileName }
    val fileStem = model?.displayName ?: fileName.substringBeforeLast('.')
    val name = if (qwen3PointSixBName.containsMatchIn(fileStem)) "Qwen3-06B" else fileStem
    return if (model == null) name else "$name ${Formatter.formatShortFileSize(context, model.sizeBytes)}"
}
