package com.example.aiassistent1.di

import android.content.Context
import androidx.room.Room
import com.example.aiassistent1.data.engine.LlamatikEngine
import com.example.aiassistent1.data.local.ChatDatabase
import com.example.aiassistent1.data.provider.DebugModelProvider
import com.example.aiassistent1.data.provider.BundledVoiceModelProvider
import com.example.aiassistent1.data.provider.SherpaOnnxSpeechPlayback
import com.example.aiassistent1.data.provider.SherpaOnnxSpeechRecognizer
import com.example.aiassistent1.data.provider.SherpaOnnxSpeechSynthesizer
import com.example.aiassistent1.data.provider.SherpaOnnxVoiceInputProvider
import com.example.aiassistent1.data.provider.SherpaOnnxVoiceActivityDetector
import com.example.aiassistent1.data.repository.RoomChatRepository
import com.example.aiassistent1.domain.interfaces.ChatRepository
import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.interfaces.SpeechRecognizer
import com.example.aiassistent1.domain.interfaces.SpeechPlayback
import com.example.aiassistent1.domain.interfaces.SpeechSynthesizer
import com.example.aiassistent1.domain.interfaces.InputProvider
import com.example.aiassistent1.domain.interfaces.VoiceActivityDetector
import com.example.aiassistent1.domain.interfaces.VoiceModelProvider
import com.example.aiassistent1.domain.usecase.SendMessageUseCase

object AppModule {
	@Volatile
	private var chatDatabase: ChatDatabase? = null

	fun provideModelProvider(context: Context): ModelProvider = DebugModelProvider(
		context.applicationContext,
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

	fun provideSendMessageUseCase(llmEngine: LLMEngine): SendMessageUseCase = SendMessageUseCase(
		llmEngine,
	)

	fun provideChatRepository(context: Context): ChatRepository = RoomChatRepository(
		provideChatDatabase(context).chatMessageDao(),
	)

	private fun provideChatDatabase(context: Context): ChatDatabase = chatDatabase ?: synchronized(this) {
		chatDatabase ?: Room.databaseBuilder(
			context.applicationContext,
			ChatDatabase::class.java,
			"ai_assistant.db",
		).build().also { chatDatabase = it }
	}
}