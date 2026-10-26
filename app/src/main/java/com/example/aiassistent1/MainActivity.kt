package com.example.aiassistent1

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.aiassistent1.di.AppModule
import com.example.aiassistent1.domain.model.ScreenOrientation
import com.example.aiassistent1.domain.model.ScreenPreferences
import com.example.aiassistent1.presentation.ui.AIAssistantApp
import com.example.aiassistent1.presentation.viewmodel.CalendarViewModel
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel
import com.example.aiassistent1.ui.theme.*
import kotlinx.coroutines.launch

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
                    chatContextRepository = AppModule.provideChatContextRepository(applicationContext),
                    llmEngine = llmEngine,
                    voiceInput = AppModule.provideVoiceInputProvider(applicationContext),
                    voiceDraftRepository = AppModule.provideVoiceDraftRepository(applicationContext),
                    savedTextDraftRepository = AppModule.provideSavedTextDraftRepository(applicationContext),
                    inputDraftController = AppModule.provideChatInputDraftController(applicationContext),
                    calendarDraftRepository = AppModule.provideCalendarDraftRepository(applicationContext),
                    speechPlayback = AppModule.provideSpeechPlayback(applicationContext),
                    settingsRepository = AppModule.provideSettingsRepository(applicationContext),
                    searchCalendarEvents = AppModule.provideSearchCalendarEventsUseCase(applicationContext),
                    observeSavedCalendarEvents = AppModule.provideObserveCalendarEventsByIdUseCase(applicationContext),
                    assistantResponseParser = AppModule.provideAssistantResponseParser(),
                    modelContextBuilder = AppModule.provideModelContextBuilder(),
                    formatCalendarField = AppModule.provideFormatCalendarFieldUseCase(llmEngine),
                    calendarCommandExecutor = AppModule.provideCalendarCommandExecutor(applicationContext),
                    calendarCommandMapper = AppModule.provideCalendarCommandMapper(),
                    calendarTimeZones = AppModule.provideCalendarTimeZones(applicationContext),
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
                    getCalendarEvent = AppModule.provideGetCalendarEventUseCase(applicationContext),
                    calendarTimeZones = AppModule.provideCalendarTimeZones(applicationContext),
                    settingsRepository = AppModule.provideSettingsRepository(applicationContext),
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppModule.provideSettingsRepository(applicationContext).screenPreferences.collect { preferences ->
                    val orientation = preferences.toRequestedOrientation()
                    if (requestedOrientation != orientation) {
                        requestedOrientation = orientation
                    }
                }
            }
        }
        // Keep the launch window until Compose has the saved page and its history.
        var contentReady = false
        val contentView = findViewById<View>(android.R.id.content)
        contentView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!contentReady) return false
                contentView.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        setContent {
            AIAssistent1Theme {
                AIAssistantApp(
                    chatViewModel = chatViewModel,
                    calendarViewModel = calendarViewModel,
                    onContentReady = { contentReady = true },
                )
            }
        }
    }
}

internal fun ScreenPreferences.toRequestedOrientation(): Int = when {
    !orientationLocked -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    orientation == ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}
