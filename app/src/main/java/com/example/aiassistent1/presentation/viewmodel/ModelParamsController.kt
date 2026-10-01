package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.model.GenerationParams

/** Called on the ViewModel's main dispatcher; a running request owns its parameter snapshot. */
internal class ModelParamsController(private val engine: LLMEngine) {
    private var applied: GenerationParams? = null

    fun applyWhenIdle(state: ChatUiState, summarizing: Boolean) {
        if (state.areModelParamsLoaded && !state.isProcessing && !state.isStopping && !summarizing) {
            applyForRequest(state.modelParams)
        }
    }

    fun applyForRequest(params: GenerationParams) {
        if (params != applied) {
            engine.updateParams(params)
            applied = params
        }
    }
}
