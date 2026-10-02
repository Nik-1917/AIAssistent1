package com.example.aiassistent1.presentation.ui

import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.MemoryLimitStatus
import com.example.aiassistent1.domain.model.MemoryRuntimeSettings
import com.example.aiassistent1.domain.model.ModelContextLimits
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Uses the current editor values and the latest assessed RAM; no file or Android access. */
internal fun modelFileSizeDescription(params: GenerationParams, locale: Locale = Locale.getDefault()): String {
    val unavailable = "Расчётный максимальный размер модели: не удалось определить."
    val memory = params.deviceContextLimit ?: return unavailable
    if (memory.status != MemoryLimitStatus.ESTIMATED && memory.status != MemoryLimitStatus.INSUFFICIENT_MEMORY) return unavailable
    // A model that is too large has no enabled context slider. Show an explicit minimum-context estimate.
    val minimumContext = params.contextSize <= 0
    val context = if (minimumContext) ModelContextLimits(params.trainedContextLength,
        contextMultiple = params.contextResponseRatio.divisor).minimum else params.contextSize
    val maximum = memory.modelFileBudget?.maximumFileBytes(context,
        MemoryRuntimeSettings(params.batchSizeAuto, params.batchSize, params.gpuLayers)) ?: return unavailable
    if (maximum == 0L) return "При контексте $context токенов доступной памяти недостаточно для файла модели."
    val basis = if (minimumContext) "Оценка для параметров выбранной модели при минимальном контексте."
        else "Оценка размера файла GGUF для параметров выбранной модели."
    return "Расчётный максимальный размер модели: до ${formatModelFileLimit(maximum, locale)} при контексте $context токенов.\n$basis"
}

/** Decimal units match Android's memory labels; round down so the displayed limit cannot grow. */
internal fun formatModelFileLimit(bytes: Long, locale: Locale): String {
    require(bytes >= 0)
    val (divisor, unit) = when {
        bytes >= 1_000_000_000L -> 1_000_000_000L to "ГБ"
        bytes >= 1_000_000L -> 1_000_000L to "МБ"
        bytes >= 1_000L -> 1_000L to "КБ"
        else -> 1L to "Б"
    }
    val number = BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(divisor), 2, RoundingMode.DOWN)
    val formatter = NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        minimumFractionDigits = if (divisor == 1L) 0 else 2
        maximumFractionDigits = minimumFractionDigits
        roundingMode = RoundingMode.DOWN
    }
    return "${formatter.format(number)} $unit"
}
