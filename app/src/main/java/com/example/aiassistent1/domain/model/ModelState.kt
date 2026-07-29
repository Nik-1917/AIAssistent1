package com.example.aiassistent1.domain.model

sealed interface ModelState {
    data object Unloaded : ModelState
    data object Loading : ModelState
    data object Ready : ModelState
    data class Error(val message: String) : ModelState
}