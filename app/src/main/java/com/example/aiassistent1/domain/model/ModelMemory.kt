package com.example.aiassistent1.domain.model

/** Physical RAM, not the managed Java heap limit. Private dirty excludes reclaimable file pages. */
data class DeviceMemorySnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val lowMemoryThresholdBytes: Long,
    val lowMemory: Boolean,
    val processPrivateDirtyBytes: Long = 0,
    val is64Bit: Boolean = true,
)

/** Only scalar metadata required for a conservative full-attention memory estimate. */
data class ModelMemoryMetadata(
    val architecture: String,
    val fileBytes: Long,
    val blockCount: Int?,
    val embeddingLength: Int?,
    val feedForwardLength: Int?,
    val headCount: Int?,
    val kvHeadCount: Int?,
    val keyLength: Int?,
    val valueLength: Int?,
    val vocabularySize: Int?,
)

/** Deliberately has no response length or context/response ratio. */
data class MemoryRuntimeSettings(
    val automaticBatch: Boolean = true,
    val manualBatchSize: Int = 256,
    val gpuLayers: Int = 0,
) {
    fun batchUpperBound(context: Int): Int = if (automaticBatch) {
        minOf(context, GenerationParams.AUTO_BATCH_LIMIT)
    } else {
        GenerationParams.MANUAL_BATCH_SIZES.filter { it <= context }
            .minByOrNull { kotlin.math.abs(it.toLong() - manualBatchSize.toLong()) } ?: 1
    }
}

enum class MemoryLimitStatus {
    ESTIMATED, LOW_MEMORY, INSUFFICIENT_MEMORY, MEMORY_UNAVAILABLE, METADATA_UNAVAILABLE,
}

/** A fresh assessment, never persisted or accepted as authoritative from UI callbacks. */
data class DeviceContextLimit(
    val maximumContext: Int,
    val status: MemoryLimitStatus,
    val totalBytes: Long = 0,
    val availableBytes: Long = 0,
    val reserveBytes: Long = 0,
    /** Fresh inputs for the settings description; never persisted or used as a loading authority. */
    val modelFileBudget: ModelFileBudget? = null,
) {
    val canLoad: Boolean get() = status == MemoryLimitStatus.ESTIMATED && maximumContext >= 2

    val explanation: String get() = when (status) {
        MemoryLimitStatus.ESTIMATED -> "Расчётный предел по памяти устройства: $maximumContext токенов"
        MemoryLimitStatus.LOW_MEMORY -> "Устройству сейчас не хватает оперативной памяти. Освободите память и повторите запрос."
        MemoryLimitStatus.INSUFFICIENT_MEMORY -> "Для этой модели недостаточно оперативной памяти даже при минимальном контексте. Выберите модель меньшего размера."
        MemoryLimitStatus.MEMORY_UNAVAILABLE -> "Не удалось определить доступную оперативную память. Загрузка модели приостановлена."
        MemoryLimitStatus.METADATA_UNAVAILABLE -> "Не удалось рассчитать потребление памяти этой модели по GGUF. Загрузка модели приостановлена."
    }
}

/** Allows local context/batch edits to update the description without Android calls on the UI thread. */
data class ModelFileBudget(val capacityBytes: Long, val metadata: ModelMemoryMetadata) {
    fun maximumFileBytes(context: Int, settings: MemoryRuntimeSettings): Long? =
        ModelMemoryCalculator.maximumModelFileBytes(metadata, settings, context, capacityBytes)
}

data class ModelMemoryCost(val weightsBytes: Long, val workingBytes: Long) {
    val totalBytes: Long get() = Math.addExact(weightsBytes, workingBytes)
}

/**
 * A conservative estimate, not an allocation guarantee. All arithmetic is checked.
 * The full context contains both prompt and output; the answer is never charged a second time.
 */
object ModelMemoryCalculator {
    private const val MIB = 1024L * 1024
    // These architectures use ordinary K/V attention dimensions. Hybrid/MLA models need their own estimator.
    private val supportedArchitectures = setOf("llama", "qwen2", "qwen3", "gemma", "gemma2", "gemma3", "phi2", "phi3")

    fun calculate(
        metadata: ModelMemoryMetadata?,
        settings: MemoryRuntimeSettings,
        memory: DeviceMemorySnapshot?,
        loadedPrivateBytes: Long = 0,
    ): DeviceContextLimit {
        if (memory == null || memory.totalBytes <= 0 || memory.availableBytes !in 0..memory.totalBytes ||
            memory.lowMemoryThresholdBytes !in 0..memory.totalBytes || memory.processPrivateDirtyBytes < 0) {
            return DeviceContextLimit(0, MemoryLimitStatus.MEMORY_UNAVAILABLE)
        }
        val reserve = maxOf(768 * MIB, memory.totalBytes / 5, memory.lowMemoryThresholdBytes)
            .coerceAtMost(Long.MAX_VALUE - 256 * MIB) + 256 * MIB
        fun result(maximum: Int, status: MemoryLimitStatus) = DeviceContextLimit(
            maximum, status, memory.totalBytes, memory.availableBytes, reserve,
        )
        if (memory.lowMemory || memory.availableBytes <= memory.lowMemoryThresholdBytes) {
            return result(0, MemoryLimitStatus.LOW_MEMORY)
        }
        if (metadata == null || cost(metadata, settings, GenerationParams.MIN_CONTEXT_SIZE) == null) {
            return result(0, MemoryLimitStatus.METADATA_UNAVAILABLE)
        }
        // Only measured resident private-dirty allocations can be reclaimed by unloading our model.
        // File-backed clean mmap pages are already reclaimable in Android's available-memory figure.
        val credit = loadedPrivateBytes.coerceIn(0, memory.processPrivateDirtyBytes.coerceAtLeast(0))
        val capacity = (memory.availableBytes + credit.coerceAtMost(memory.totalBytes - memory.availableBytes) - reserve)
            .coerceAtLeast(0).let { if (memory.is64Bit) it else minOf(it, 768 * MIB) }
        var lower = 0
        var upper = Int.MAX_VALUE / GenerationParams.CONTEXT_STEP
        while (lower < upper) {
            val middle = lower + (upper - lower + 1) / 2
            val bytes = cost(metadata, settings, middle * GenerationParams.CONTEXT_STEP)?.totalBytes ?: Long.MAX_VALUE
            if (bytes <= capacity) lower = middle else upper = middle - 1
        }
        return result(lower * GenerationParams.CONTEXT_STEP,
            if (lower > 0) MemoryLimitStatus.ESTIMATED else MemoryLimitStatus.INSUFFICIENT_MEMORY)
            .copy(modelFileBudget = ModelFileBudget(capacity, metadata))
    }

    /** Inverts the same weight cost at a fixed context, keeping the selected model's dimensions. */
    fun maximumModelFileBytes(
        metadata: ModelMemoryMetadata,
        settings: MemoryRuntimeSettings,
        context: Int,
        capacityBytes: Long,
    ): Long? {
        if (capacityBytes < 0) return null
        val working = cost(metadata, settings, context)?.workingBytes ?: return null
        val remaining = (capacityBytes - working).coerceAtLeast(0)
        var lower = 0L
        var upper = remaining
        while (lower < upper) {
            val distance = upper - lower
            val middle = lower + distance / 2 + distance % 2
            val weights = try { weightsBytes(middle, settings.gpuLayers) } catch (_: ArithmeticException) { null }
            if (weights != null && weights <= remaining) lower = middle else upper = middle - 1
        }
        return lower
    }

    private fun weightsBytes(fileBytes: Long, gpuLayers: Int): Long = Math.addExact(
        Math.multiplyExact(fileBytes, if (gpuLayers != 0) 2L else 1L),
        maxOf(64 * MIB, fileBytes / 10),
    )

    fun cost(metadata: ModelMemoryMetadata, settings: MemoryRuntimeSettings, context: Int): ModelMemoryCost? = try {
        require(metadata.architecture in supportedArchitectures && metadata.fileBytes > 0 && context > 0)
        fun dimension(value: Int?): Long = requireNotNull(value).also { require(it > 0) }.toLong()
        val layers = dimension(metadata.blockCount)
        val embedding = dimension(metadata.embeddingLength)
        val feedForward = dimension(metadata.feedForwardLength)
        val heads = dimension(metadata.headCount)
        val kvHeads = dimension(metadata.kvHeadCount ?: metadata.headCount)
        require(kvHeads <= heads && heads % kvHeads == 0L)
        fun headDimension(explicit: Int?): Long {
            if (explicit != null) return dimension(explicit)
            require(embedding % heads == 0L)
            return embedding / heads
        }
        val key = headDimension(metadata.keyLength)
        val value = headDimension(metadata.valueLength)
        val vocabulary = dimension(metadata.vocabularySize)
        fun multiply(vararg values: Long) = values.fold(1L, Math::multiplyExact)
        fun sum(vararg values: Long) = values.fold(0L, Math::addExact)
        val batch = settings.batchUpperBound(context).toLong()
        val paddedContext = Math.addExact(context.toLong(), 255L) / 256 * 256
        // Up to FP32 K/V (4 bytes); intentionally above the usual FP16 cache.
        val kv = multiply(layers, kvHeads, sum(key, value), 4, paddedContext)
        val activations = multiply(batch, sum(multiply(12, embedding), multiply(4, feedForward)), 4)
        val logits = multiply(batch, vocabulary, 4)
        // Reserve attention workspace even when the requested Flash Attention is unavailable.
        val attention = multiply(batch, heads, paddedContext, 4)
        val working = sum(128 * MIB, kv, activations, logits, attention)
        // File size is a conservative weight estimate, with overhead and a possible GPU-side copy.
        val weights = weightsBytes(metadata.fileBytes, settings.gpuLayers)
        ModelMemoryCost(weights, working).also { it.totalBytes }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}
