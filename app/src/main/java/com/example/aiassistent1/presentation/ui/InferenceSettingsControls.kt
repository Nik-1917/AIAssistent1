package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.aiassistent1.domain.model.ComputeMode
import com.example.aiassistent1.domain.model.InferenceBackend
import com.example.aiassistent1.domain.model.InferenceExecution

@Composable
internal fun InferenceSettingsControls(
    mode: ComputeMode,
    onModeChange: (ComputeMode) -> Unit,
    enabled: Boolean,
    execution: InferenceExecution,
) {
    Text("Вычислитель для следующих запросов", style = MaterialTheme.typography.titleSmall)
    Text("Общий режим для календаря, чата и выжимок.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ComputeMode.entries.forEach { value ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilterChip(selected = mode == value, enabled = enabled, onClick = { onModeChange(value) },
                    label = { Text(if (value == ComputeMode.AUTO) "Авто" else "CPU") })
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(3.dp)
                        .background(
                            color = if (mode == value) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                        ),
                )
            }
        }
    }
    Text("Выбирайте CPU если размер модели меньше 1.5 GB",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("В режиме «Авто» приложение проверяет Vulkan, затем OpenCL. Если оба способа недоступны, модель работает на CPU.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        "Сведения о последнем запуске модели будут здесь. Показатели обновляются после каждого успешного завершения генерации ответа.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (execution.checkingGpu) {
        Text("Проверка GPU…", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (execution.backend == null) {
        execution.fallbackReason?.let { InferenceValue("Проверка вычислителей", it) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Последний запуск", style = MaterialTheme.typography.titleSmall)
        execution.modelName?.let { InferenceValue("Модель", it) }
        InferenceValue("Запрошено", if (execution.requestedMode == ComputeMode.AUTO) "Авто" else "CPU")
        val backend = when {
            execution.backend == InferenceBackend.CPU -> "CPU"
            execution.offloadedLayers < execution.totalLayers -> "GPU + CPU · ${execution.backend.displayName}"
            else -> "GPU · ${execution.backend.displayName}"
        }
        InferenceValue("Фактически используется", backend)
        execution.deviceName?.let { InferenceValue("Устройство", it) }
        if (execution.offloadedLayers > 0) {
            InferenceValue("Слоёв на GPU", "${execution.offloadedLayers} из ${execution.totalLayers}")
        }
        execution.fallbackReason?.let { InferenceValue("Причина выбора вычислителя", it) }
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
