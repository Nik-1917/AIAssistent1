package com.example.aiassistent1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.aiassistent1.di.AppModule
import com.example.aiassistent1.presentation.ui.AIAssistantApp
import com.example.aiassistent1.presentation.viewmodel.CalendarViewModel
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel
import com.example.aiassistent1.ui.theme.*

class MainActivity : ComponentActivity() {
    private val chatViewModel: ChatViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val llmEngine = AppModule.provideLlmEngine(applicationContext)
                return ChatViewModel(
                    context = applicationContext,
                    chatRepository = AppModule.provideChatRepository(applicationContext),
                    sendMessage = AppModule.provideSendMessageUseCase(llmEngine),
                    createCalendarEvent = AppModule.provideCreateCalendarEventUseCase(applicationContext),
                    llmEngine = llmEngine,
                    voiceInput = AppModule.provideVoiceInputProvider(applicationContext),
                    voiceDraftRepository = AppModule.provideVoiceDraftRepository(applicationContext),
                    speechPlayback = AppModule.provideSpeechPlayback(applicationContext),
                    settingsRepository = AppModule.provideSettingsRepository(applicationContext),
                    searchCalendarEvents = AppModule.provideSearchCalendarEventsUseCase(applicationContext),
                    assistantResponseParser = AppModule.provideAssistantResponseParser(),
                    modelContextBuilder = AppModule.provideModelContextBuilder(),
                ) as T
            }
        }
    }

    private val calendarViewModel: CalendarViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass.isAssignableFrom(CalendarViewModel::class.java))
                return CalendarViewModel(
                    observeCalendarEvents = AppModule.provideObserveCalendarEventsUseCase(applicationContext),
                    createCalendarEvent = AppModule.provideCreateCalendarEventUseCase(applicationContext),
                    updateCalendarEvent = AppModule.provideUpdateCalendarEventUseCase(applicationContext),
                    deleteCalendarEvent = AppModule.provideDeleteCalendarEventUseCase(applicationContext),
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIAssistent1Theme {
                AIAssistantApp(
                    chatViewModel = chatViewModel,
                    calendarViewModel = calendarViewModel,
                )
            }
        }
    }
}
