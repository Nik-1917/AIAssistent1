package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.model.GenerationEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Compatibility for callers that consume append-only deltas. */
fun Flow<GenerationEvent>.textDeltas(): Flow<String> = flow {
    var emitted = false
    collect { event ->
        when (event) {
            is GenerationEvent.Delta -> { emitted = true; emit(event.text) }
            is GenerationEvent.Restart -> check(!emitted) { "Генерация прервана. Повторите запрос." }
        }
    }
}

/** Each value replaces the unfinished text in the existing message record. */
fun Flow<GenerationEvent>.textSnapshots(): Flow<String> = flow {
    val text = StringBuilder()
    collect { event ->
        when (event) {
            is GenerationEvent.Delta -> { text.append(event.text); emit(text.toString()) }
            is GenerationEvent.Restart -> text.clear()
        }
    }
}
