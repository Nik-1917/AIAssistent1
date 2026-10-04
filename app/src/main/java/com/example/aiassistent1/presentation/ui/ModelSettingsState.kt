package com.example.aiassistent1.presentation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.aiassistent1.domain.model.GenerationParams
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** One editable snapshot; delayed DataStore echoes must not move a dragged slider back. */
internal class ModelSettingsState(initialParams: GenerationParams) {
    var params by mutableStateOf(initialParams.normalizedForAutomaticSettings())
        private set

    private var persisted = params
    private var submitted: GenerationParams? = null

    fun update(transform: (GenerationParams) -> GenerationParams) {
        params = transform(params).normalizedForAutomaticSettings()
    }

    fun acceptPersisted(value: GenerationParams) {
        val normalized = value.normalizedForAutomaticSettings()
        if (params.trainedContextLength != normalized.trainedContextLength ||
            params.deviceContextLimit != normalized.deviceContextLimit) {
            val wasBlocked = params.deviceContextLimit?.canLoad == false
            // A new file limit also bounds unsaved edits and pending DataStore acknowledgements.
            fun GenerationParams.withCurrentLimit() =
                copy(trainedContextLength = normalized.trainedContextLength,
                    deviceContextLimit = normalized.deviceContextLimit,
                    contextSize = if (wasBlocked) normalized.contextSize else contextSize,
                    maxTokens = if (wasBlocked) normalized.maxTokens else maxTokens).normalizedForAutomaticSettings()
            params = params.withCurrentLimit()
            persisted = persisted.withCurrentLimit()
            submitted = submitted?.withCurrentLimit()
        }
        val awaiting = submitted
        if (awaiting != null && normalized != awaiting) return
        val hasLocalChanges = params != (awaiting ?: persisted)
        persisted = normalized
        submitted = null
        if (!hasLocalChanges) params = normalized
    }

    fun flush(onSave: (GenerationParams) -> Unit) {
        if (params == (submitted ?: persisted)) return
        val snapshot = params
        submitted = snapshot
        onSave(snapshot)
    }

    /** The caller runs this only in the expanded section's RESUMED lifecycle. */
    suspend fun saveWhileActive(onSave: (GenerationParams) -> Unit) {
        try {
            while (currentCoroutineContext().isActive) {
                delay(SAVE_INTERVAL_MILLIS)
                flush(onSave)
            }
        } finally {
            // Collapse, dismissal and ON_PAUSE must retain the last movement before the next tick.
            flush(onSave)
        }
    }

    private companion object {
        const val SAVE_INTERVAL_MILLIS = 1_000L
    }
}
