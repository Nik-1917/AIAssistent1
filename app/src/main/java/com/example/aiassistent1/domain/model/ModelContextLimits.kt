package com.example.aiassistent1.domain.model

/** A model's declared training window; an unknown window keeps the application's old ceiling. */
data class ModelContextLimits(val trainedContextLength: Int? = null) {
    private val declaredMaximum = trainedContextLength?.takeIf { it >= 2 } ?: FALLBACK_CONTEXT_SIZE
    private val step = if (declaredMaximum >= GenerationParams.CONTEXT_STEP) GenerationParams.CONTEXT_STEP else 2

    // Round down so snapping a slider can never exceed the value in the file.
    val maximum: Int = declaredMaximum / step * step
    val minimum: Int = minOf(GenerationParams.MIN_CONTEXT_SIZE, maximum)
    val adjustable: Boolean get() = maximum > minimum

    // Compose allocates an array for discrete ticks. Large windows still snap in normalize().
    val sliderSteps: Int = ((maximum - minimum) / step - 1).coerceAtLeast(0)
        .takeIf { it <= 256 } ?: 0

    fun normalize(value: Int): Int {
        val bounded = value.coerceIn(minimum, maximum).toLong()
        return (((bounded + step / 2) / step) * step)
            .coerceIn(minimum.toLong(), maximum.toLong()).toInt()
    }

    companion object {
        const val FALLBACK_CONTEXT_SIZE = 8192
    }
}
