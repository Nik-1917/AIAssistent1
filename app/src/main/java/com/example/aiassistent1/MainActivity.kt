package com.example.aiassistent1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.aiassistent1.di.AppModule
import com.example.aiassistent1.presentation.ui.ChatScreen
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel
import com.example.aiassistent1.ui.theme.AIAssistent1Theme

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
                    addCalendarEvent = AppModule.provideAddCalendarEventUseCase(applicationContext),
                    llmEngine = llmEngine,
                    voiceInput = AppModule.provideVoiceInputProvider(applicationContext),
                    voiceDraftRepository = AppModule.provideVoiceDraftRepository(applicationContext),
                    speechPlayback = AppModule.provideSpeechPlayback(applicationContext),
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIAssistent1Theme {
                ChatScreen(viewModel = chatViewModel)
            }
        }
    }
}