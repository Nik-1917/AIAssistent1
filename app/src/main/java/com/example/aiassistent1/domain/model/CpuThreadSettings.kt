package com.example.aiassistent1.domain.model

/** One process-wide CPU snapshot shared by settings defaults, the slider and native inference. */
object CpuThreadSettings {
    val availableProcessors: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun automaticThreadCount(processorCount: Int = availableProcessors): Int =
        when (val available = processorCount.coerceAtLeast(1)) {
            in 1..4 -> available
            in 5..8 -> 4
            else -> 6
        }

    fun boundedThreadCount(value: Int, processorCount: Int = availableProcessors): Int =
        value.coerceIn(1, processorCount.coerceAtLeast(1))
}
