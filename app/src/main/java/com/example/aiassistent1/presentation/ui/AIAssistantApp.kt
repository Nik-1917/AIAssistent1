package com.example.aiassistent1.presentation.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.aiassistent1.presentation.viewmodel.CalendarViewModel
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel

private enum class AppDestination {
    CHAT,
    CALENDAR,
}

@Composable
fun AIAssistantApp(
    chatViewModel: ChatViewModel,
    calendarViewModel: CalendarViewModel,
    modifier: Modifier = Modifier,
) {
    var destination by rememberSaveable { mutableStateOf(AppDestination.CHAT) }

    AnimatedContent(
        targetState = destination,
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
                onOpenCalendar = { destination = AppDestination.CALENDAR },
            )

            AppDestination.CALENDAR -> CalendarScreen(
                viewModel = calendarViewModel,
                onNavigateBack = { destination = AppDestination.CHAT },
            )
        }
    }
}
