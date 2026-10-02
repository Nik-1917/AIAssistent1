package com.example.aiassistent1.presentation.ui

import com.example.aiassistent1.domain.model.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ModelMemoryDescriptionTest {
    private val locale = Locale.forLanguageTag("ru-RU")
    private val model = ModelMemoryMetadata("qwen3", 128L * 1024 * 1024, 2, 64, 128, 4, 2, 16, 16, 1000)
    private val capacity = requireNotNull(ModelMemoryCalculator.cost(model.copy(fileBytes = 2_999_999_999), MemoryRuntimeSettings(), 4096)).totalBytes
    private val params = GenerationParams(contextSize = 4096, maxTokens = 2048, trainedContextLength = 32768,
        deviceContextLimit = DeviceContextLimit(32768, MemoryLimitStatus.ESTIMATED,
            modelFileBudget = ModelFileBudget(capacity, model)))

    @Test fun `description uses a bounded file size explicit context and selected model basis`() {
        assertEquals("Расчётный максимальный размер модели: до 2,99 ГБ при контексте 4096 токенов.\n" +
            "Оценка размера файла GGUF для параметров выбранной модели.", modelFileSizeDescription(params, locale))
        assertEquals("1,99 ГБ", formatModelFileLimit(1_999_999_999, locale))
        assertEquals("999,99 МБ", formatModelFileLimit(999_999_999, locale))
        assertEquals("999,99 КБ", formatModelFileLimit(999_999, locale))
        assertEquals("999 Б", formatModelFileLimit(999, locale))
        assertEquals("1.99 ГБ", formatModelFileLimit(1_999_999_999, Locale.US))
    }

    @Test fun `unsaved slider and batch edits update description while ratio alone does not`() {
        val state = ModelSettingsState(params)
        val initial = modelFileSizeDescription(state.params, locale)
        state.update { it.withContextSize(8192) }
        val larger = modelFileSizeDescription(state.params, locale)
        assertTrue(larger.contains("при контексте 8192 токенов"))
        assertFalse(larger.contains("до 2,99 ГБ"))
        assertNotEquals(initial, larger)
        state.update { it.withContextResponseRatio(ContextResponseRatio.FOUR_TO_ONE) }
        assertEquals(larger, modelFileSizeDescription(state.params, locale))
        state.update { it.copy(batchSizeAuto = false, batchSize = 1024) }
        assertNotEquals(larger, modelFileSizeDescription(state.params, locale))
    }

    @Test fun `fresh RAM changes the description without saving settings`() {
        val state = ModelSettingsState(params)
        val initial = modelFileSizeDescription(state.params, locale)
        val memory = requireNotNull(params.deviceContextLimit)
        state.acceptPersisted(params.copy(deviceContextLimit = memory.copy(
            modelFileBudget = requireNotNull(memory.modelFileBudget).copy(capacityBytes = capacity - 1_000_000_000))))
        assertNotEquals(initial, modelFileSizeDescription(state.params, locale))
        val writes = mutableListOf<GenerationParams>()
        state.flush { writes += it }
        assertTrue(writes.isEmpty())
    }

    @Test fun `insufficient memory for selected file uses explicit minimum context instead of zero`() {
        val memory = requireNotNull(params.deviceContextLimit)
        val blocked = params.copy(deviceContextLimit = memory.copy(maximumContext = 0,
            status = MemoryLimitStatus.INSUFFICIENT_MEMORY)).normalizedForSettings()
        assertEquals(0, blocked.contextSize)
        val description = modelFileSizeDescription(blocked, locale)
        assertTrue(description.contains("при контексте 512 токенов"))
        assertTrue(description.contains("при минимальном контексте"))
        assertFalse(description.contains("при контексте 0"))
    }

    @Test fun `unknown or exhausted memory never displays a fabricated size`() {
        val memory = requireNotNull(params.deviceContextLimit)
        for (status in listOf(MemoryLimitStatus.LOW_MEMORY, MemoryLimitStatus.MEMORY_UNAVAILABLE, MemoryLimitStatus.METADATA_UNAVAILABLE)) {
            assertEquals("Расчётный максимальный размер модели: не удалось определить.",
                modelFileSizeDescription(params.copy(deviceContextLimit = memory.copy(status = status)), locale))
        }
        assertTrue(modelFileSizeDescription(params.copy(deviceContextLimit = null), locale).contains("не удалось определить"))
        val empty = params.copy(deviceContextLimit = memory.copy(modelFileBudget = ModelFileBudget(0, model)))
        assertEquals("При контексте 4096 токенов доступной памяти недостаточно для файла модели.", modelFileSizeDescription(empty, locale))
    }
}
