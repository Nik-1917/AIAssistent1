package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.ChatRepository
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import com.example.aiassistent1.domain.model.AppSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

class ObserveAppSessionUseCase(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<AppSession> = settingsRepository.navigationState
        .distinctUntilChanged()
        .flatMapLatest { navigation ->
            combine(
                chatRepository.observeMessages(navigation.chatId),
                settingsRepository.chatScrollPosition,
            ) { messages, scrollPosition ->
                AppSession(navigation, messages, scrollPosition)
            }
        }
}
