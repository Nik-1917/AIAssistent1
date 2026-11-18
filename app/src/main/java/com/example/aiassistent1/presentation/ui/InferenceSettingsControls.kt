package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.aiassistent1.domain.model.ComputeMode
import com.example.aiassistent1.domain.model.InferenceBackend
import com.example.aiassistent1.domain.model.InferenceExecution
import com.example.aiassistent1.domain.model.ModelProfile

@Composable
internal fun InferenceSettingsControls(
    profile: ModelProfile,
    onProfileChange: (ModelProfile) -> Unit,
    mode: ComputeMode,
    onModeChange: (ComputeMode) -> Unit,
    enabled: Boolean,
    execution: InferenceExecution,
) {
    PrimaryTabRow(selectedTabIndex = profile.ordinal) {
        ModelProfile.entries.forEach { value ->
            Tab(selected = profile == value, onClick = { onProfileChange(value) },
                text = { Text(if (value == ModelProfile.CALENDAR) "Календарь" else "Чат и выжимки") })
        }
    }
    Text("Вычислитель для следующих запросов", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ComputeMode.entries.forEach { value ->
            FilterChip(selected = mode == value, enabled = enabled, onClick = { onModeChange(value) },
                label = { Text(if (value == ComputeMode.AUTO) "Авто" else "CPU") })
        }
    }
    Text("Авто проверяет совместимость модели с GPU и доступную память. При ошибке GPU ответ перезапускается на CPU.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (execution.checkingGpu) {
        Text("Проверка GPU…", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (execution.backend == null) {
        execution.fallbackReason?.let { InferenceValue("Причина выбора CPU", it) }
        Text("Сведения о запуске появятся после загрузки модели.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Последний запуск", style = MaterialTheme.typography.titleSmall)
        execution.modelName?.let { InferenceValue("Модель", it) }
        InferenceValue("Запрошено", if (execution.requestedMode == ComputeMode.AUTO) "Авто" else "CPU")
        val backend = when {
            execution.backend == InferenceBackend.CPU -> "CPU"
            execution.offloadedLayers < execution.totalLayers -> "GPU + CPU · Vulkan"
            else -> "GPU · Vulkan"
        }
        InferenceValue("Фактически используется", backend)
        execution.deviceName?.let { InferenceValue("Устройство", it) }
        if (execution.offloadedLayers > 0) {
            InferenceValue("Слоёв на GPU", "${execution.offloadedLayers} из ${execution.totalLayers}")
        }
        execution.fallbackReason?.let { InferenceValue("Причина перехода на CPU", it) }
        execution.initializationMillis?.let { InferenceValue("Подготовка модели", "$it мс") }
        execution.timeToFirstTokenMillis?.let { InferenceValue("До первого токена", "$it мс") }
        execution.prefillTokensPerSecond?.let { InferenceValue("Обработка запроса", "${String.format("%.1f", it)} токенов/с") }
        execution.decodeTokensPerSecond?.let { InferenceValue("Генерация ответа", "${String.format("%.1f", it)} токенов/с") }
    }
}

@Composable
private fun InferenceValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
