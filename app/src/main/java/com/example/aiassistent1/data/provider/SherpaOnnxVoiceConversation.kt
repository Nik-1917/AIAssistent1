package com.example.aiassistent1.data.provider

import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.aiassistent1.domain.formatter.SpeechTextChunker
import com.example.aiassistent1.domain.interfaces.*
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.domain.usecase.PersonalKeywordActivation
import com.example.aiassistent1.domain.usecase.PersonalKeywordWakeSession
import com.example.aiassistent1.domain.usecase.PersonalWakeResult
import com.example.aiassistent1.domain.usecase.VoiceCommandAcceptance
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

class SherpaOnnxVoiceInputProvider(
    private val context: Context,
    private val recognizer: SpeechRecognizer,
    private val vad: VoiceActivityDetector,
    private val keywordSpotter: KeywordSpotter,
    private val feedbackManager: AudioFeedbackManager,
    private val voiceProfileManager: VoiceProfileManager,
    private val settingsRepository: SettingsRepository,
    private val processing: AudioProcessingManager,
    private val personalKeyword: PersonalKeywordActivation? = null,
) : InputProvider, PersonalKeywordControls, VoiceProfileControls, AutoCloseable {
    private data class CapturedUtterance(val samples: FloatArray, val detected: Boolean, val speechStartedAt: Long,
        val playback: AssistantSpeechEchoGuard.Context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val input = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 2)
    private val errors = MutableSharedFlow<VoiceInputError>(extraBufferCapacity = 2)
    private val activity = MutableStateFlow(AudioSessionState.Idle)
    private val speechStarts = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    private val lock = Any()
    private val readiness = VoiceCaptureReadiness()
    private var recordingJob: Job? = null
    private var activeSessionId: Long? = null
    private var nextSessionId = 0L
    private var captureWake = false
    private var captureEnrollment = false
    private val activationIssue = MutableStateFlow<String?>(null)
    override fun observeActivationIssue() = activationIssue.asStateFlow()
    override fun observeKeywordRecording() = activity.map { it == AudioSessionState.Enrolling }.distinctUntilChanged()
    override fun observeVoiceRecording() = observeKeywordRecording()
    override suspend fun voiceProfileStatus() = voiceProfileManager.status()
    override suspend fun enrollVoice() {
        val samples = try { captureEnrollmentSegment() }
        catch (timeout: TimeoutCancellationException) {
            throw IllegalStateException("За 12 секунд голос не записан. Произнесите короткую фразу и сделайте паузу.", timeout)
        }
        try { check(voiceProfileManager.enroll(samples)) { "Не удалось записать голос. Повторите запись обычным голосом." } }
        finally { samples.fill(0f) }
        activationIssue.value = null
    }
    override suspend fun deleteVoice() { voiceProfileManager.delete(); activationIssue.value = null }
    override suspend fun selectVoiceId(enabled: Boolean) {
        settingsRepository.setVoiceIdEnabled(enabled)
        activationIssue.value = null
    }
    override suspend fun keywordStatus() = personalKeyword?.status(prepareModel = false)
        ?: PersonalKeywordStatus(false, false, false, "Персональная модель недоступна")
    override suspend fun enrollKeyword() {
        val service = checkNotNull(personalKeyword) { "Персональная модель недоступна" }
        try {
            service.enrollNamedFrom(
                capture = ::captureEnrollmentSegment,
                recognize = { recognizer.recognize(it).getOrThrow() },
                saveDisplayName = settingsRepository::setAssistantDisplayName,
            )
        }
        catch (timeout: TimeoutCancellationException) {
            throw IllegalStateException("За 12 секунд слово не записано. Повторите запись и сделайте паузу после слова.", timeout)
        }
        activationIssue.value = null
    }
    override suspend fun deleteKeyword() {
        checkNotNull(personalKeyword).delete()
        activationIssue.value = null
    }
    override suspend fun selectActivationMode(mode: VoiceActivationMode) {
        if (mode == VoiceActivationMode.PERSONAL_WORD) {
            val status = checkNotNull(personalKeyword).status(prepareModel = true)
            check(status.canActivate) { status.message }
        }
        settingsRepository.setVoiceActivationMode(mode)
        activationIssue.value = null
    }
    private val warmUpJob = scope.launch {
        try { vad.prepare(); recognizer.prepare(); processing.prepareInput() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Lazy initialization will report errors through the input session. */ }
        catch (_: LinkageError) { /* The capture session reports native-library failures. */ }
    }
    override fun observeInput() = input
    override fun observeErrors() = errors
    override fun observeActivity() = activity
    override fun observeSpeechStart() = speechStarts
    override fun start() = startCapture(false)
    override fun startContinuous() = startCapture(false)
    override fun startWakeWord() = startCapture(true)
    override suspend fun awaitReady(sessionId: Long) = readiness.await(sessionId)

    override suspend fun captureEnrollmentSegment(): FloatArray {
        val segment = CompletableDeferred<FloatArray>()
        var enrollmentJob: Job? = null
        var session: com.example.aiassistent1.service.GenerationForegroundService.Session? = null
        try {
            return withTimeout(12_000) {
                withContext(Dispatchers.Main.immediate) {
                    session = com.example.aiassistent1.service.GenerationForegroundService.acquire(
                        context, com.example.aiassistent1.service.GenerationForegroundService.Kind.Microphone)
                }
                session?.awaitReady()
                synchronized(lock) {
                    check(recordingJob?.isActive != true) { "Сначала остановите голосовой ввод" }
                    startCapture(false, segment)
                    enrollmentJob = recordingJob
                }
                segment.await()
            }
        } finally {
            withContext(NonCancellable) {
                enrollmentJob?.cancelAndJoin()
                withContext(Dispatchers.Main.immediate) { session?.close() }
            }
        }
    }

    private fun startCapture(wake: Boolean, enrollment: CompletableDeferred<FloatArray>? = null): Long = synchronized(lock) {
        check(!captureEnrollment || recordingJob?.isActive != true) { "Дождитесь завершения записи ключевой фразы" }
        activeSessionId?.let {
            if (recordingJob?.isActive == true && (captureWake == wake || vad.isSpeechDetected())) return@synchronized it
        }
        check(ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) { "Нет разрешения на запись аудио" }
        val previous = recordingJob
        previous?.cancel()
        captureWake = wake
        captureEnrollment = enrollment != null
        val id = ++nextSessionId
        activeSessionId = id
        readiness.begin(id)
        activity.value = AudioSessionState.Idle
        traceVoiceStartup("capture_requested session=$id")
        recordingJob = scope.launch {
            try {
                previous?.join()
                warmUpJob.join()
                // A best-effort background warm-up may have failed. Readiness requires success.
                vad.prepare()
                recognizer.prepare()
                capture(id, wake, enrollment)
            }
            catch (cancelled: CancellationException) { enrollment?.cancel(cancelled); throw cancelled }
            catch (error: Exception) {
                readiness.end(id, error)
                if (enrollment != null) enrollment.completeExceptionally(error)
                else if (isCurrent(id)) errors.emit(VoiceInputError(id, error))
            }
            catch (error: LinkageError) {
                val wrapped = IllegalStateException("Не удалось загрузить аудиобиблиотеку", error)
                readiness.end(id, wrapped)
                if (enrollment != null) enrollment.completeExceptionally(wrapped)
                else if (isCurrent(id)) errors.emit(VoiceInputError(id, wrapped))
            }
            finally {
                readiness.end(id)
                synchronized(lock) { if (activeSessionId == id) { activeSessionId = null; captureEnrollment = false; activity.value = AudioSessionState.Idle } }
            }
        }
        id
    }
    override fun stop() = synchronized(lock) {
        activeSessionId?.let { readiness.end(it) }
        activeSessionId = null
        recordingJob?.cancel()
        activity.value = AudioSessionState.Idle
    }
    override suspend fun stopAndAwait() {
        val stopping = synchronized(lock) {
            stop()
            recordingJob
        }
        stopping?.join()
        traceVoiceStartup("capture_released")
    }
    private fun isCurrent(id: Long) = synchronized(lock) { activeSessionId == id }
    override fun close() {
        stop(); warmUpJob.cancel()
        scope.launch {
            try {
                recordingJob?.join(); warmUpJob.join()
                try { personalKeyword?.close() }
                finally {
                    try { recognizer.close() }
                    finally {
                        try { vad.close() }
                        finally { keywordSpotter.close() }
                    }
                }
            } finally { scope.cancel() }
        }
    }

    private suspend fun capture(id: Long, wake: Boolean, enrollment: CompletableDeferred<FloatArray>? = null) = coroutineScope {
        val token = Any()
        MicrophoneCoordinator.acquire(token)
        var recorder: AudioRecord? = null
        var processor: AudioProcessingManager.Session? = null
        try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) throw SecurityException("Нет разрешения на запись аудио")
            val minBuffer = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "Запись 16 кГц недоступна" }
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 8192))
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Не удалось открыть микрофон" }
            processor = processing.open(recorder, true, true)
            val initial = settingsRepository.readAudioPreferences()
            val useKws = wake && keywordSpotter.isAvailable()
            if (useKws) keywordSpotter.prepare()
            val segments = Channel<CapturedUtterance>(4)
            // A bounded observer of the SAME processed VAD utterance. It never controls activation.
            // Experimental active routing below is separate from this diagnostic observer.
            val shadowSegments = if (wake && personalKeyword?.available == true)
                Channel<FloatArray>(Channel.CONFLATED, onUndeliveredElement = { it.fill(0f) }) else null
            val shadowJob = shadowSegments?.let { queue ->
                launch {
                    for (segment in queue) {
                        try {
                            personalKeyword?.evaluate(segment, WakeWordEngine.METRIC_KWS_SHADOW)
                        } finally { segment.fill(0f) }
                    }
                }
            }
            val feedbackPlaying = AtomicBoolean(false)
            val resetAfterFeedback = AtomicBoolean(false)
            suspend fun activationFeedback() {
                activity.value = AudioSessionState.Listening
                feedbackPlaying.set(true)
                try { withTimeoutOrNull(2_000) { feedbackManager.playActivationSound() } }
                finally { resetAfterFeedback.set(true); feedbackPlaying.set(false) }
            }
            val recognition = launch {
                var activatedUntil = 0L
                var activeWord = initial.wakeWord
                var activeEngine = initial.wakeWordEngine
                val metricSession = PersonalKeywordWakeSession(
                    preferences = settingsRepository::readAudioPreferences,
                    evaluate = { personalKeyword?.evaluate(it, WakeWordEngine.METRIC_KWS)
                        ?: MetricKwsDecision.Unavailable("model_unavailable") },
                    verifySpeaker = voiceProfileManager::verify,
                    recognize = { recognizer.recognize(it).getOrThrow() },
                    onActivation = { activationFeedback() },
                    onVerifiedSpeech = { if (isCurrent(id) && settingsRepository.readAudioPreferences().voiceInterruptionEnabled) speechStarts.emit(id) },
                    clock = SystemClock::elapsedRealtime,
                    speakerReady = { !voiceProfileManager.needsEnrollment() },
                )
                var activePolicy = initial.inputPolicy()
                for ((segment, detected, speechStartedAt, playbackContext) in segments) {
                    val prefs = settingsRepository.readAudioPreferences()
                    val policy = prefs.inputPolicy()
                    // A decoded word alone does not establish that the user spoke. Check the
                    // captured narrator reference before any KWS activation or interruption event.
                    val source = processing.speechEchoGuard.assess(segment, playbackContext)
                    if (source.narrator) {
                        traceVoiceSource(id, segment.size, source, rejected = true)
                        continue
                    }
                    val sourceTranscript = if (source.needsTextCheck) recognizer.recognize(segment).getOrThrow() else null
                    val rejected = sourceTranscript != null && processing.speechEchoGuard.rejectsText(source, sourceTranscript)
                    traceVoiceSource(id, segment.size, source, rejected)
                    if (rejected) continue
                    if (activePolicy != policy) { activatedUntil = 0; activePolicy = policy }
                    if (prefs.wakeWordEngine == WakeWordEngine.METRIC_KWS_SHADOW && segment.size <= 128_000) {
                        shadowSegments?.trySend(segment.copyOf())
                    }
                    if (prefs.wakeWord != activeWord || prefs.wakeWordEngine != activeEngine) {
                        activatedUntil = 0; activeWord = prefs.wakeWord; activeEngine = prefs.wakeWordEngine
                    }
                    if (wake && prefs.wakeWordEnabled) {
                        when (val result = metricSession.handle(segment, speechStartedAt)) {
                            is PersonalWakeResult.Legacy -> Unit
                            is PersonalWakeResult.Unavailable -> {
                                activationIssue.value = if (result.reason == "voice_id_required") "Voice ID включён: сначала запишите голос в настройках."
                                    else "Персональная активация недоступна: проверьте модель и запись слова или выберите другой способ активации."
                                activity.value = AudioSessionState.Rejected
                                continue
                            }
                            PersonalWakeResult.Rejected -> { activity.value = AudioSessionState.Rejected; continue }
                            PersonalWakeResult.Activated -> { activationIssue.value = null; continue }
                            PersonalWakeResult.Waiting -> continue
                            is PersonalWakeResult.Command -> {
                                if (isCurrent(id)) input.emit(VoiceInputEvent(id, result.text))
                                activity.value = AudioSessionState.Waiting
                                continue
                            }
                        }
                    }
                    if (prefs.voiceIdEnabled && voiceProfileManager.needsEnrollment()) {
                        activationIssue.value = "Voice ID включён: сначала запишите голос в настройках."
                        activity.value = AudioSessionState.Rejected
                        continue
                    }
                    val requireName = wake && prefs.wakeWordEnabled && SystemClock.elapsedRealtime() > activatedUntil
                    if (requireName && useKws && !detected) continue
                    var text: String? = null
                    if (requireName && !useKws) {
                        val transcript = sourceTranscript ?: recognizer.recognize(segment).getOrThrow()
                        text = WakeWordTokens.removePrefix(transcript, prefs.wakeWord) ?: continue
                    }
                    if (prefs.voiceIdEnabled && !voiceProfileManager.verify(segment)) {
                        activity.value = AudioSessionState.Rejected
                        continue
                    }
                    if (settingsRepository.readAudioPreferences().inputPolicy() != policy) continue
                    if (requireName) {
                        // An accepted name/KWS activation is intentional input, unlike provisional VAD.
                        if (settingsRepository.readAudioPreferences().voiceInterruptionEnabled && isCurrent(id)) speechStarts.emit(id)
                        activationFeedback()
                        activatedUntil = SystemClock.elapsedRealtime() + 10_000
                    }
                    val transcript = text ?: (sourceTranscript ?: recognizer.recognize(segment).getOrThrow()).let {
                        if (detected) WakeWordTokens.removePrefix(it, prefs.wakeWord) ?: it else it
                    }
                    if (settingsRepository.readAudioPreferences().inputPolicy() != policy) continue
                    if (VoiceCommandAcceptance.hasSpeechText(transcript) && isCurrent(id)) {
                        activationIssue.value = null
                        // Empty ASR, rejected activation and failed Voice ID never stop playback.
                        if (settingsRepository.readAudioPreferences().voiceInterruptionEnabled) speechStarts.emit(id)
                        input.emit(VoiceInputEvent(id, transcript))
                        activatedUntil = 0
                        activity.value = if (wake) AudioSessionState.Waiting else AudioSessionState.Listening
                    }
                }
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            val pcm = ShortArray(512)
            val sampleClock = RecordingSampleClock()
            val preRoll = VoiceUtterancePreRoll()
            val timeline = VoiceCaptureTimeline()
            var keywordDetected = false
            var wasSpeech = false
            var speechStartedAt = 0L
            var captureReady = false
            while (currentCoroutineContext().isActive) {
                val read = withContext(Dispatchers.IO) { recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING) }
                check(read >= 0) { "Ошибка микрофона: $read" }
                if (read == 0) continue
                val firstSampleTime = sampleClock.firstSampleTimeNs(recorder, read)
                if (!feedbackPlaying.get() && resetAfterFeedback.getAndSet(false)) {
                    vad.reset(); keywordSpotter.reset()
                    preRoll.reset()
                    timeline.reset()
                    processor.discardBufferedInput()
                    keywordDetected = false; wasSpeech = false; speechStartedAt = 0L
                }
                val samples = processor.process(FloatArray(read) { pcm[it] / 32768f }, firstSampleTime)
                if (!captureReady && samples.isNotEmpty() && sampleClock.isReady && processor.isReady()) {
                    synchronized(lock) {
                        if (activeSessionId == id) {
                            activity.value = if (enrollment != null) AudioSessionState.Enrolling
                                else if (wake) AudioSessionState.Waiting else AudioSessionState.Listening
                            traceVoiceStartup("capture_ready session=$id profile=${processor.profile} clock=${sampleClock.timing}")
                            captureReady = readiness.ready(id)
                        }
                    }
                }
                if (samples.isEmpty() || feedbackPlaying.get()) continue
                timeline.append(samples.size, processor.outputStartTimeNs)
                if (useKws && keywordSpotter.accept(samples) != null) keywordDetected = true
                preRoll.append(samples)
                val completed = vad.acceptTimed(samples)
                val speech = vad.isSpeechDetected()
                if (speech && !wasSpeech) {
                    speechStartedAt = SystemClock.elapsedRealtime()
                    preRoll.speechStarted()
                }
                wasSpeech = speech
                for (timed in completed) {
                    val segment = preRoll.complete(timed.startSample, timed.samples)
                    if (enrollment != null) {
                        segments.close()
                        recognition.join()
                        enrollment.complete(segment)
                        return@coroutineScope
                    }
                    val prefixSize = segment.size - timed.samples.size
                    val captured = checkNotNull(timeline.range(timed.startSample - prefixSize, segment.size)) {
                        "Не удалось определить время голосового фрагмента. Повторите запрос."
                    }
                    val playbackContext = processing.speechEchoGuard.snapshot(captured.startNs, captured.endNs)
                    check(segments.trySend(CapturedUtterance(segment, keywordDetected,
                        speechStartedAt.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(), playbackContext)).isSuccess) {
                        "Распознавание не успевает за речью. Повторите запрос."
                    }
                    keywordDetected = false
                }
                if (!speech && completed.isNotEmpty()) speechStartedAt = 0L
            }
            segments.close()
            recognition.join()
            shadowSegments?.close()
            shadowJob?.cancelAndJoin()
        } finally {
            try {
                recorder?.let { runCatching { it.stop() }; it.release() }
            } finally {
                try { processor?.let(processing::close) }
                finally {
                    try { vad.reset() }
                    finally {
                        try { keywordSpotter.reset() }
                        finally { MicrophoneCoordinator.release(token) }
                    }
                }
            }
        }
    }

    private fun traceVoiceSource(id: Long, samples: Int, source: AssistantSpeechEchoGuard.Assessment, rejected: Boolean) {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        android.util.Log.d("VoiceInput", "VOICE_SOURCE session=$id samples=$samples " +
            "overlap=${source.context.overlapsNarrator} similarity=${source.similarity} " +
            "textCheck=${source.needsTextCheck} rejected=$rejected")
    }

    private fun traceVoiceStartup(message: String) {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            android.util.Log.d("VoiceInput", "VOICE_STARTUP $message")
    }
}

class SherpaOnnxSpeechPlayback(
    private val synthesizer: SpeechSynthesizer,
    private val processing: AudioProcessingManager = AudioProcessingManager(),
    private val feedback: AudioFeedbackManager? = null,
) : SpeechPlayback {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val resourcesReleased = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val trackLock = Any()
    private var track: AudioTrack? = null

    override suspend fun speak(text: String, onPlaybackStarted: () -> Unit): Result<Unit> =
        speakWhenReady(text, {}, onPlaybackStarted)

    override suspend fun speakWhenReady(text: String, awaitReady: suspend () -> Unit,
        onPlaybackStarted: () -> Unit): Result<Unit> {
        return try {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                check(!closed.get()) { "Воспроизведение закрыто" }
                stopped.set(false)
                var started = false
                val playbackId = processing.beginOutput()
                var output: AudioTrack? = null
                var writtenFrames = 0L
                val clock = PlaybackSampleClock()
                val renderTimestamp = AudioTimestamp()
                val silence = FloatArray(160)
                try {
                    for (chunk in SpeechTextChunker.split(text)) {
                        currentCoroutineContext().ensureActive()
                        if (stopped.get()) break
                        val speech = synthesizer.synthesize(chunk).getOrThrow()
                        val samples = PcmResampler.to16k(speech.samples, speech.sampleRate)
                        if (stopped.get()) break
                        val utteranceId = processing.prepareOutput(chunk, samples, playbackId)
                        awaitReady()
                        currentCoroutineContext().ensureActive()
                        if (stopped.get()) break
                        withContext(Dispatchers.IO) {
                            val active = output ?: run {
                                val minimum = AudioTrack.getMinBufferSize(16_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                                check(minimum > 0)
                                AudioTrack.Builder()
                                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                        .setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                                    .setBufferSizeInBytes(maxOf(minimum, 2560))
                                    .setTransferMode(AudioTrack.MODE_STREAM).build().also {
                                        output = it
                                        synchronized(trackLock) { track = it }
                                        it.play()
                                    }
                            }
                            // A sentence gap can stall the hardware clock. Feed silence until two
                            // advancing timestamps agree, before exposing its first word to capture.
                            clock.reset()
                            val prepareStartedAt = SystemClock.elapsedRealtime()
                            val deadline = prepareStartedAt + 2_000
                            var stable = false
                            var headFallback = false
                            while (!stopped.get() && !stable && !headFallback && SystemClock.elapsedRealtime() < deadline) {
                                currentCoroutineContext().ensureActive()
                                val written = active.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                                check(written > 0 || stopped.get()) { "Ошибка подготовки аудиовывода: $written" }
                                writtenFrames += written.coerceAtLeast(0)
                                if (active.getTimestamp(renderTimestamp))
                                    stable = clock.observe(renderTimestamp.framePosition, renderTimestamp.nanoTime)
                                clock.observeHead(active.playbackHeadPosition.toLong() and 0xffffffffL)
                                headFallback = SystemClock.elapsedRealtime() - prepareStartedAt >= 500 && clock.hasAdvancingHead
                            }
                            if (stopped.get()) return@withContext
                            check(stable || headFallback) { "Не удалось подготовить аудиовывод. Повторите озвучивание." }
                            android.util.Log.d("SpeechPlayback", "VOICE_STARTUP output_ready playback=$playbackId " +
                                "utterance=$utteranceId clock=${if (stable) "timestamp" else "playback_head"}")
                            if (!started) {
                                feedback?.triggerVibration()
                                started = true
                                onPlaybackStarted()
                                android.util.Log.d("SpeechPlayback", "VOICE_STARTUP speech_started playback=$playbackId")
                            }
                            var nextClockPoll = System.nanoTime() + if (stable) 10_000_000_000L else 1_000_000_000L
                            var offset = 0
                            while (offset < samples.size && !stopped.get()) {
                                currentCoroutineContext().ensureActive()
                                val count = minOf(160, samples.size - offset)
                                val frame = samples.copyOfRange(offset, offset + count)
                                val now = System.nanoTime()
                                if (now >= nextClockPoll) {
                                    if (active.getTimestamp(renderTimestamp)) clock.observe(renderTimestamp.framePosition, renderTimestamp.nanoTime)
                                    nextClockPoll = now + 10_000_000_000L
                                }
                                val renderTimeNs = clock.timeNs(writtenFrames,
                                    active.playbackHeadPosition.toLong() and 0xffffffffL, now)
                                processing.onOutputSamples(frame, 16_000, 0, renderTimeNs, utteranceId)
                                var done = 0
                                while (done < count && !stopped.get()) {
                                    val written = active.write(frame, done, count - done, AudioTrack.WRITE_BLOCKING)
                                    check(written > 0 || stopped.get()) { "Ошибка воспроизведения: $written" }
                                    done += written.coerceAtLeast(0)
                                    writtenFrames += written.coerceAtLeast(0)
                                }
                                offset += done
                            }
                            val drainDeadline = SystemClock.elapsedRealtime() + 2000
                            while (!stopped.get() && (active.playbackHeadPosition.toLong() and 0xffffffffL) < writtenFrames &&
                                SystemClock.elapsedRealtime() < drainDeadline) delay(10)
                        }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        output?.let { active ->
                            synchronized(trackLock) {
                                if (track === active) track = null
                                runCatching { active.stop() }
                                processing.onOutputStopped()
                                active.release()
                            }
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
        finally { releaseIfClosed() }
    }
    override fun stop() {
        stopped.set(true)
        synchronized(trackLock) { track?.let { runCatching { it.pause(); it.flush() } } }
        processing.onOutputStopped()
    }
    override fun close() { closed.set(true); stop(); releaseIfClosed() }
    private fun releaseIfClosed() {
        if (closed.get() && mutex.tryLock()) {
            try { if (resourcesReleased.compareAndSet(false, true)) synthesizer.close() }
            finally { mutex.unlock() }
        }
    }
}
