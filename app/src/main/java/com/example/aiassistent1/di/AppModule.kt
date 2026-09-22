package com.example.aiassistent1.di

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.example.aiassistent1.calendar.core.domain.CalendarEventRepository
import com.example.aiassistent1.calendar.core.domain.CreateCalendarEventUseCase
import com.example.aiassistent1.calendar.core.domain.DeleteCalendarEventUseCase
import com.example.aiassistent1.calendar.core.domain.ObserveCalendarEventsUseCase
import com.example.aiassistent1.calendar.core.domain.PrepareCalendarEventUpdateUseCase
import com.example.aiassistent1.calendar.core.domain.ResolveCalendarUpdateTargetUseCase
import com.example.aiassistent1.calendar.core.domain.SearchCalendarEventsUseCase
import com.example.aiassistent1.calendar.core.domain.UpdateCalendarEventUseCase
import com.example.aiassistent1.calendar.core.domain.CalendarCommandExecutor
import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import com.example.aiassistent1.calendar.storage.android.RoomCalendarEventRepository
import com.example.aiassistent1.domain.mapper.CalendarDeleteCommandMapper
import com.example.aiassistent1.domain.mapper.CalendarUpdateCommandMapper
import com.example.aiassistent1.calendar.storage.android.local.CalendarDatabase
import com.example.aiassistent1.data.engine.LlamatikEngine
import com.example.aiassistent1.domain.context.ModelContextBuilder
import com.example.aiassistent1.data.local.ChatDatabase
import com.example.aiassistent1.data.local.NoteDatabase
import com.example.aiassistent1.data.local.ConferenceDatabase
import com.example.aiassistent1.data.provider.*
import com.example.aiassistent1.data.repository.*
import com.example.aiassistent1.domain.interfaces.*
import com.example.aiassistent1.domain.parser.AssistantResponseParser
import com.example.aiassistent1.domain.provider.SystemPromptProvider
import com.example.aiassistent1.domain.usecase.FormatCalendarFieldUseCase
import com.example.aiassistent1.domain.usecase.SendMessageUseCase
import com.example.aiassistent1.domain.usecase.CreateConferenceSummaryUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object AppModule {
	@Volatile
	private var chatDatabase: ChatDatabase? = null

	@Volatile
	private var noteDatabase: NoteDatabase? = null

	@Volatile
	private var noteRepository: NoteRepository? = null

	@Volatile
	private var calendarDatabase: CalendarDatabase? = null

	@Volatile
	private var calendarEventRepository: CalendarEventRepository? = null

	@Volatile
	private var voiceDraftRepository: VoiceDraftRepository? = null

	@Volatile
	private var settingsRepository: SettingsRepository? = null

	@Volatile
	private var voiceModelProvider: VoiceModelProvider? = null

	@Volatile
	private var audioFeedbackManager: AudioFeedbackManager? = null

	@Volatile
	private var audioProcessingManager: AudioProcessingManager? = null

	@Volatile
	private var keywordSpotter: KeywordSpotter? = null

	@Volatile
	private var speakerIdentifier: SpeakerIdentifier? = null

	@Volatile
	private var voiceProfileManager: VoiceProfileManager? = null

	@Volatile
	private var conferenceDatabase: ConferenceDatabase? = null

	@Volatile
	private var conferenceRepository: ConferenceRepositoryImpl? = null

	@Volatile
	private var conferenceManager: ConferenceManager? = null

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

	fun provideVoiceModelProvider(context: Context): VoiceModelProvider = voiceModelProvider ?: synchronized(this) {
		voiceModelProvider ?: BundledVoiceModelProvider(
			context.applicationContext,
		).also { voiceModelProvider = it }
	}

	fun provideSpeechRecognizer(context: Context): SpeechRecognizer = SherpaOnnxSpeechRecognizer(
		context.applicationContext,
		provideVoiceModelProvider(context),
	)

	fun provideSpeechSynthesizer(context: Context): SpeechSynthesizer = SherpaOnnxSpeechSynthesizer(
		context.applicationContext,
		provideVoiceModelProvider(context),
		provideSettingsRepository(context),
	)

	fun provideVoiceActivityDetector(context: Context): VoiceActivityDetector = SherpaOnnxVoiceActivityDetector(
		context.applicationContext,
		provideVoiceModelProvider(context),
	)

	fun provideKeywordSpotter(context: Context): KeywordSpotter = keywordSpotter ?: synchronized(this) {
		keywordSpotter ?: SherpaOnnxKeywordSpotter(
			context.applicationContext,
			provideVoiceModelProvider(context),
			provideSettingsRepository(context)
		).also { keywordSpotter = it }
	}

	fun provideSpeakerIdentifier(context: Context): SpeakerIdentifier = speakerIdentifier ?: synchronized(this) {
		speakerIdentifier ?: SherpaOnnxSpeakerIdentifier(
			context.applicationContext,
			provideVoiceModelProvider(context)
		).also { speakerIdentifier = it }
	}

	fun provideVoiceProfileManager(context: Context): VoiceProfileManager = voiceProfileManager ?: synchronized(this) {
		voiceProfileManager ?: VoiceProfileManager(
			context.applicationContext,
			provideSpeakerIdentifier(context),
			provideSettingsRepository(context)
		).also { voiceProfileManager = it }
	}

	fun provideVoiceInputProvider(context: Context): InputProvider = SherpaOnnxVoiceInputProvider(
		context.applicationContext,
		provideSpeechRecognizer(context),
		provideVoiceActivityDetector(context),
		provideKeywordSpotter(context),
		provideAudioFeedbackManager(context),
		provideVoiceProfileManager(context),
		provideSettingsRepository(context),
		provideAudioProcessingManager(context),
	)

	fun provideSpeechPlayback(context: Context): SpeechPlayback = SherpaOnnxSpeechPlayback(
		provideSpeechSynthesizer(context),
		provideAudioProcessingManager(context),
		provideAudioFeedbackManager(context),
	)

	fun provideAudioFeedbackManager(context: Context): AudioFeedbackManager = audioFeedbackManager ?: synchronized(this) {
		audioFeedbackManager ?: AudioFeedbackManager(
			context.applicationContext,
			provideSettingsRepository(context)
		).also { audioFeedbackManager = it }
	}

	fun provideAudioProcessingManager(context: Context): AudioProcessingManager = audioProcessingManager ?: synchronized(this) {
		audioProcessingManager ?: AudioProcessingManager().also { audioProcessingManager = it }
	}

	fun provideSystemPromptProvider(): SystemPromptProvider =
        SystemPromptProvider()

	fun provideModelContextBuilder(): ModelContextBuilder = ModelContextBuilder()

    fun provideAssistantResponseParser(): AssistantResponseParser =
        AssistantResponseParser()

	fun provideSendMessageUseCase(llmEngine: LLMEngine): SendMessageUseCase = SendMessageUseCase(
		llmEngine,
        provideSystemPromptProvider()
	)

	fun provideFormatCalendarFieldUseCase(llmEngine: LLMEngine): FormatCalendarFieldUseCase =
		FormatCalendarFieldUseCase(llmEngine)

	fun provideCalendarEventRepository(context: Context): CalendarEventRepository =
		calendarEventRepository ?: synchronized(this) {
			calendarEventRepository ?: RoomCalendarEventRepository(
				provideCalendarDatabase(context).calendarEventDao(),
			).also { calendarEventRepository = it }
		}

	fun provideCreateCalendarEventUseCase(context: Context): CreateCalendarEventUseCase =
		CreateCalendarEventUseCase(provideCalendarEventRepository(context))

	fun provideSearchCalendarEventsUseCase(context: Context): SearchCalendarEventsUseCase =
		SearchCalendarEventsUseCase(provideCalendarEventRepository(context))

	fun provideObserveCalendarEventsUseCase(context: Context): ObserveCalendarEventsUseCase =
		ObserveCalendarEventsUseCase(provideCalendarEventRepository(context))

	fun provideUpdateCalendarEventUseCase(context: Context): UpdateCalendarEventUseCase =
		UpdateCalendarEventUseCase(provideCalendarEventRepository(context))

	fun provideResolveCalendarUpdateTargetUseCase(context: Context): ResolveCalendarUpdateTargetUseCase =
		ResolveCalendarUpdateTargetUseCase(provideCalendarEventRepository(context))

	fun providePrepareCalendarEventUpdateUseCase(): PrepareCalendarEventUpdateUseCase =
		PrepareCalendarEventUpdateUseCase()

	fun provideCalendarUpdateCommandMapper(): CalendarUpdateCommandMapper =
		CalendarUpdateCommandMapper()

	fun provideCalendarDeleteCommandMapper(): CalendarDeleteCommandMapper =
		CalendarDeleteCommandMapper()

	fun provideCalendarCommandMapper(): CalendarCommandMapper = CalendarCommandMapper()

	fun provideCalendarCommandExecutor(context: Context): CalendarCommandExecutor =
		CalendarCommandExecutor(provideCalendarEventRepository(context))

	fun provideDeleteCalendarEventUseCase(context: Context): DeleteCalendarEventUseCase =
		DeleteCalendarEventUseCase(provideCalendarEventRepository(context))

	fun provideChatRepository(context: Context): ChatRepository = RoomChatRepository(
		provideChatDatabase(context).chatMessageDao(),
	)

	fun provideNoteRepository(context: Context): NoteRepository = noteRepository ?: synchronized(this) {
		noteRepository ?: RoomNoteRepository(
			provideNoteDatabase(context).noteDao(),
		).also { noteRepository = it }
	}

	fun provideVoiceDraftRepository(context: Context): VoiceDraftRepository = voiceDraftRepository ?: synchronized(this) {
		voiceDraftRepository ?: DataStoreVoiceDraftRepository(
			context.applicationContext.voiceDraftStore(),
		).also { voiceDraftRepository = it }
	}

	fun provideConferenceRepository(context: Context): ConferenceRepositoryImpl = conferenceRepository ?: synchronized(this) {
		conferenceRepository ?: ConferenceRepositoryImpl(
			provideConferenceDatabase(context)
		).also { conferenceRepository = it }
	}

	fun provideConferenceManager(context: Context): ConferenceManager = conferenceManager ?: synchronized(this) {
		conferenceManager ?: ConferenceManager(
			context.applicationContext,
			provideConferenceRepository(context),
			provideSpeechRecognizer(context),
			provideVoiceActivityDetector(context),
			provideSettingsRepository(context),
			provideAudioProcessingManager(context),
		).also { conferenceManager = it }
	}

	private fun provideChatDatabase(context: Context): ChatDatabase = chatDatabase ?: synchronized(this) {
		chatDatabase ?: Room.databaseBuilder(
			context.applicationContext,
			ChatDatabase::class.java,
			"ai_assistant.db",
		).fallbackToDestructiveMigration().build().also { chatDatabase = it }
	}

	private fun provideNoteDatabase(context: Context): NoteDatabase = noteDatabase ?: synchronized(this) {
		noteDatabase ?: Room.databaseBuilder(
			context.applicationContext,
			NoteDatabase::class.java,
			"assistant_notes.db",
		).build().also { noteDatabase = it }
	}

	private fun provideCalendarDatabase(context: Context): CalendarDatabase = calendarDatabase ?: synchronized(this) {
		calendarDatabase ?: Room.databaseBuilder(
			context.applicationContext,
			CalendarDatabase::class.java,
			"calendar_core.db",
		).addMigrations(CalendarDatabase.MIGRATION_1_2, CalendarDatabase.MIGRATION_2_3).build().also { calendarDatabase = it }
	}

	private fun provideConferenceDatabase(context: Context): ConferenceDatabase = conferenceDatabase ?: synchronized(this) {
		conferenceDatabase ?: Room.databaseBuilder(
			context.applicationContext,
			ConferenceDatabase::class.java,
			"conference.db",
		).addMigrations(ConferenceDatabase.MIGRATION_1_2).build().also { conferenceDatabase = it }
	}
}
