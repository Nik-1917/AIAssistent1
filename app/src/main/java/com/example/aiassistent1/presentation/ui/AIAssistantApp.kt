package com.example.aiassistent1.presentation.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiassistent1.domain.model.AppDestination
import com.example.aiassistent1.presentation.viewmodel.CalendarViewModel
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel

@Composable
fun AIAssistantApp(
    chatViewModel: ChatViewModel,
    calendarViewModel: CalendarViewModel,
    modifier: Modifier = Modifier,
    onContentReady: () -> Unit = {},
) {
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val navigation = uiState.navigationState
    if (navigation == null) {
        if (uiState.sessionError != null) {
            Surface(modifier = modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Не удалось загрузить сохранённую страницу и сообщения.")
                    Button(onClick = chatViewModel::observeAppSession) {
                        Text("Повторить")
                    }
                }
            }
            SideEffect(onContentReady)
        }
        return
    }

    AnimatedContent(
        targetState = navigation.destination,
        modifier = modifier,
        transitionSpec = {
            if (targetState == AppDestination.CALENDAR) {
                (slideInHorizontally(animationSpec = tween(360)) { fullWidth -> fullWidth } + fadeIn())
                    .togetherWith(
                        slideOutHorizontally(animationSpec = tween(300)) { fullWidth -> -fullWidth / 5 } + fadeOut(),
                    )
            } else {
                (slideInHorizontally(animationSpec = tween(360)) { fullWidth -> -fullWidth } + fadeIn())
                    .togetherWith(
                        slideOutHorizontally(animationSpec = tween(300)) { fullWidth -> fullWidth / 5 } + fadeOut(),
                    )
            }
        },
        label = "ChatCalendarNavigation",
    ) { currentDestination ->
        when (currentDestination) {
            AppDestination.CHAT -> ChatScreen(
                viewModel = chatViewModel,
                onOpenCalendar = chatViewModel::openCalendar,
            )

            AppDestination.CALENDAR -> CalendarScreen(
                viewModel = calendarViewModel,
                onNavigateBack = {
                    chatViewModel.setChatMode(true)
                    chatViewModel.returnToConversation()
                },
                onOpenChat = { chatViewModel.setChatMode(false) },
            )
        }
    }
    SideEffect(onContentReady)
}
