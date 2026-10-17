package com.example.aiassistent1.presentation.ui

import com.example.aiassistent1.domain.model.FloatingControlPositions
import kotlin.math.roundToInt

/** Converts the old center-based offsets once, preserving their displayed pixel positions. */
internal fun anchorFloatingControlsToHeader(
    positions: FloatingControlPositions,
    containerHeightPx: Int,
    density: Float,
): FloatingControlPositions {
    if (positions.isRelativeToHeader) return positions

    // These are the legacy layout dimensions; keep its separate rounding for each card.
    val oldCalendarSizePx = (66f * density).roundToInt()
    val oldCalendarBaseY = (containerHeightPx - oldCalendarSizePx) / 2 - (167f * density).roundToInt()
    val oldSpeechBaseY = (containerHeightPx / 2f + (33f - 167f + 5f) * density).roundToInt()
    return positions.copy(
        speechCardYdp = positions.speechCardYdp + oldSpeechBaseY / density,
        calendarButtonYdp = positions.calendarButtonYdp + oldCalendarBaseY / density,
        isRelativeToHeader = true,
    )
}
