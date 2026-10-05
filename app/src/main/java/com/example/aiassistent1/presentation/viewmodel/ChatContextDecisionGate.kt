package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.model.ChatContextChoice
import com.example.aiassistent1.domain.model.ChatContextPressure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-dispatcher owner. A late click cannot resolve another request or retry. */
internal class ChatContextDecisionGate {
    private val state = MutableStateFlow<ChatContextPressure?>(null)
    val pressure = state.asStateFlow()
    private var answer: CompletableDeferred<ChatContextChoice>? = null

    suspend fun await(pressure: ChatContextPressure): ChatContextChoice {
        check(answer == null)
        val pending = CompletableDeferred<ChatContextChoice>()
        answer = pending
        state.value = pressure
        try { return pending.await() } finally {
            pending.cancel()
            if (answer === pending) {
                answer = null
                state.value = null
            }
        }
    }

    fun resolve(id: String, choice: ChatContextChoice) {
        if (state.value?.id == id) answer?.complete(choice)
    }
}
