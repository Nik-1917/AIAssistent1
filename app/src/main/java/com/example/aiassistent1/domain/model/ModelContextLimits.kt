package com.example.aiassistent1.domain.model

/** A model's declared training window; an unknown window keeps the application's old ceiling. */
data class ModelContextLimits(val trainedContextLength: Int? = null, val deviceMaximum: Int? = null,
    private val contextMultiple: Int = 2) {
    init { require(contextMultiple == 2 || contextMultiple == 4) }
    private val modelMaximum = trainedContextLength?.takeIf { it >= 2 } ?: FALLBACK_CONTEXT_SIZE
    private val declaredMaximum = minOf(modelMaximum, deviceMaximum?.coerceAtLeast(0) ?: modelMaximum)
    private val step = if (declaredMaximum >= GenerationParams.CONTEXT_STEP) GenerationParams.CONTEXT_STEP else contextMultiple

    // Round down so snapping a slider can never exceed the value in the file.
    val maximum: Int = declaredMaximum / step * step
    val minimum: Int = minOf(GenerationParams.MIN_CONTEXT_SIZE, maximum)
    val adjustable: Boolean get() = maximum > minimum

    // Compose allocates an array for discrete ticks. Large windows still snap in normalize().
    val sliderSteps: Int = ((maximum - minimum) / step - 1).coerceAtLeast(0)
        .takeIf { it <= 256 } ?: 0

    fun normalize(value: Int): Int {
        if (maximum == 0) return 0
        val bounded = value.coerceIn(minimum, maximum).toLong()
        return (((bounded + step / 2) / step) * step)
            .coerceIn(minimum.toLong(), maximum.toLong()).toInt()
    }

    companion object {
        const val FALLBACK_CONTEXT_SIZE = 8192
    }
}
