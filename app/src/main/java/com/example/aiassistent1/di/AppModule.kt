package com.example.aiassistent1.di

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.example.aiassistent1.data.engine.LlamatikEngine
import com.example.aiassistent1.data.local.ChatDatabase
import com.example.aiassistent1.data.provider.AndroidCalendarProvider
import com.example.aiassistent1.data.provider.DebugModelProvider
import com.example.aiassistent1.data.provider.BundledVoiceModelProvider
import com.example.aiassistent1.data.provider.SherpaOnnxSpeechPlayback
import com.example.aiassistent1.data.provider.SherpaOnnxSpeechRecognizer
import com.example.aiassistent1.data.provider.SherpaOnnxSpeechSynthesizer
import com.example.aiassistent1.data.provider.SherpaOnnxVoiceInputProvider
import com.example.aiassistent1.data.provider.SherpaOnnxVoiceActivityDetector
import com.example.aiassistent1.data.repository.*
import com.example.aiassistent1.domain.interfaces.ChatRepository
import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import com.example.aiassistent1.domain.interfaces.SpeechRecognizer
import com.example.aiassistent1.domain.interfaces.SpeechPlayback
import com.example.aiassistent1.domain.interfaces.SpeechSynthesizer
import com.example.aiassistent1.domain.interfaces.InputProvider
import com.example.aiassistent1.domain.interfaces.CalendarProvider
import com.example.aiassistent1.domain.interfaces.VoiceActivityDetector
import com.example.aiassistent1.domain.interfaces.VoiceDraftRepository
import com.example.aiassistent1.domain.interfaces.VoiceModelProvider
import com.example.aiassistent1.domain.usecase.AddCalendarEventUseCase
import com.example.aiassistent1.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object AppModule {
	@Volatile
	private var chatDatabase: ChatDatabase? = null

	@Volatile
	private var voiceDraftRepository: VoiceDraftRepository? = null

	@Volatile
	private var settingsRepository: SettingsRepository? = null

	fun provideSettingsRepository(context: Context): SettingsRepository = settingsRepository ?: synchronized(this) {
		settingsRepository ?: DataStoreSettingsRepository(
			context.applicationContext,
			CoroutineScope(SupervisorJob() + Dispatchers.Main)
		).also { settingsRepository = it }
	}

	fun provideModelProvider(context: Context): ModelProvider = DebugModelProvider(
		context.applicationContext,
		provideSettingsRepository(context),
	)

	fun provideLlmEngine(context: Context): LLMEngine = LlamatikEngine(
		modelProvider = provideModelProvider(context),
	)

	fun provideVoiceModelProvider(context: Context): VoiceModelProvider = BundledVoiceModelProvider(
		context.applicationContext,
	)

	fun provideSpeechRecognizer(context: Context): SpeechRecognizer = SherpaOnnxSpeechRecognizer(
		context.applicationContext,
		provideVoiceModelProvider(context),
	)

	fun provideSpeechSynthesizer(context: Context): SpeechSynthesizer = SherpaOnnxSpeechSynthesizer(
		context.applicationContext,
		provideVoiceModelProvider(context),
	)

	fun provideVoiceActivityDetector(context: Context): VoiceActivityDetector = SherpaOnnxVoiceActivityDetector(
		context.applicationContext,
		provideVoiceModelProvider(context),
	)

	fun provideVoiceInputProvider(context: Context): InputProvider = SherpaOnnxVoiceInputProvider(
		context.applicationContext,
		provideSpeechRecognizer(context),
		provideVoiceActivityDetector(context),
	)

	fun provideSpeechPlayback(context: Context): SpeechPlayback = SherpaOnnxSpeechPlayback(
		provideSpeechSynthesizer(context),
	)

	fun provideSystemPromptProvider(): com.example.aiassistent1.domain.provider.SystemPromptProvider = 
        com.example.aiassistent1.domain.provider.SystemPromptProvider()

    fun provideAssistantResponseParser(): com.example.aiassistent1.domain.parser.AssistantResponseParser = 
        com.example.aiassistent1.domain.parser.AssistantResponseParser()

	fun provideSendMessageUseCase(llmEngine: LLMEngine): SendMessageUseCase = SendMessageUseCase(
		llmEngine,
        provideSystemPromptProvider()
	)

	fun provideCalendarProvider(context: Context): CalendarProvider = AndroidCalendarProvider(
		context.applicationContext,
	)

	fun provideAddCalendarEventUseCase(context: Context): AddCalendarEventUseCase = AddCalendarEventUseCase(
		provideCalendarProvider(context),
	)

	fun provideSearchCalendarEventsUseCase(context: Context): com.example.aiassistent1.domain.usecase.SearchCalendarEventsUseCase = 
        com.example.aiassistent1.domain.usecase.SearchCalendarEventsUseCase(
		    provideCalendarProvider(context),
	    )

	fun provideChatRepository(context: Context): ChatRepository = RoomChatRepository(
		provideChatDatabase(context).chatMessageDao(),
	)

	fun provideVoiceDraftRepository(context: Context): VoiceDraftRepository = voiceDraftRepository ?: synchronized(this) {
		voiceDraftRepository ?: DataStoreVoiceDraftRepository(
			context.applicationContext.voiceDraftStore(),
		).also { voiceDraftRepository = it }
	}

	private fun provideChatDatabase(context: Context): ChatDatabase = chatDatabase ?: synchronized(this) {
		chatDatabase ?: Room.databaseBuilder(
			context.applicationContext,
			ChatDatabase::class.java,
			"ai_assistant.db",
		).build().also { chatDatabase = it }
	}
}