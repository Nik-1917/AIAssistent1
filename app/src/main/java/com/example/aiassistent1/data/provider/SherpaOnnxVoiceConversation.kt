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
) : InputProvider, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val input = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 2)
    private val errors = MutableSharedFlow<VoiceInputError>(extraBufferCapacity = 2)
    private val activity = MutableStateFlow(AudioSessionState.Idle)
    private val speechStarts = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    private val lock = Any()
    private var recordingJob: Job? = null
    private var activeSessionId: Long? = null
    private var nextSessionId = 0L
    private var captureWake = false
    private var captureEnrollment = false
    private val warmUpJob = scope.launch {
        try { vad.prepare(); recognizer.prepare() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Lazy initialization will report errors through the input session. */ }
    }
    override fun observeInput() = input
    override fun observeErrors() = errors
    override fun observeActivity() = activity
    override fun observeSpeechStart() = speechStarts
    override fun start() = startCapture(false)
    override fun startContinuous() = startCapture(false)
    override fun startWakeWord() = startCapture(true)
    override fun startBargeIn() = startCapture(false)

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
        recordingJob = scope.launch {
            try {
                previous?.join()
                warmUpJob.join()
                capture(id, wake, enrollment)
            }
            catch (cancelled: CancellationException) { enrollment?.cancel(cancelled); throw cancelled }
            catch (error: Exception) {
                if (enrollment != null) enrollment.completeExceptionally(error)
                else if (isCurrent(id)) errors.emit(VoiceInputError(id, error))
            }
            catch (error: LinkageError) {
                val wrapped = IllegalStateException("Не удалось загрузить аудиобиблиотеку", error)
                if (enrollment != null) enrollment.completeExceptionally(wrapped)
                else if (isCurrent(id)) errors.emit(VoiceInputError(id, wrapped))
            }
            finally {
                synchronized(lock) { if (activeSessionId == id) { activeSessionId = null; captureEnrollment = false; activity.value = AudioSessionState.Idle } }
            }
        }
        id
    }
    override fun stop() = synchronized(lock) {
        activeSessionId = null
        recordingJob?.cancel()
        activity.value = AudioSessionState.Idle
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
            val segments = Channel<Pair<FloatArray, Boolean>>(4)
            // A bounded observer of the SAME processed VAD utterance. It never controls activation.
            // No encoder is shipped until weights licensing and Russian/parity checks are complete.
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
            activity.value = if (enrollment != null) AudioSessionState.Enrolling
                else if (wake || initial.needsEnrollment && initial.voiceIdEnabled) AudioSessionState.Waiting else AudioSessionState.Listening
            val recognition = launch {
                var activatedUntil = 0L
                var activeWord = initial.wakeWord
                for ((segment, detected) in segments) {
                    val prefs = settingsRepository.readAudioPreferences()
                    if (prefs.wakeWordEngine == WakeWordEngine.METRIC_KWS_SHADOW && segment.size <= 128_000) {
                        shadowSegments?.trySend(segment.copyOf())
                    }
                    if (prefs.wakeWord != activeWord) { activatedUntil = 0; activeWord = prefs.wakeWord }
                    val enrollment = voiceProfileManager.needsEnrollment()
                    val requireName = (wake && SystemClock.elapsedRealtime() > activatedUntil) || enrollment
                    if (requireName && useKws && !detected) continue
                    var text: String? = null
                    if (requireName && !useKws) {
                        val transcript = recognizer.recognize(segment).getOrThrow()
                        text = WakeWordTokens.removePrefix(transcript, prefs.wakeWord) ?: continue
                    }
                    if (enrollment) {
                        activity.value = AudioSessionState.Enrolling
                        if (!voiceProfileManager.enroll(segment)) {
                            activity.value = AudioSessionState.Rejected
                            continue
                        }
                    } else if (!voiceProfileManager.verify(segment)) {
                        activity.value = AudioSessionState.Rejected
                        continue
                    }
                    if (prefs.voiceIdEnabled && prefs.bargeIn) speechStarts.emit(id)
                    if (requireName) {
                        activity.value = AudioSessionState.Listening
                        feedbackPlaying.set(true)
                        try { withTimeoutOrNull(2_000) { feedbackManager.playActivationSound() } }
                        finally { feedbackPlaying.set(false) }
                        activatedUntil = SystemClock.elapsedRealtime() + 10_000
                    }
                    val transcript = text ?: recognizer.recognize(segment).getOrThrow().let {
                        if (detected) WakeWordTokens.removePrefix(it, prefs.wakeWord) ?: it else it
                    }
                    if (transcript.isNotBlank() && isCurrent(id)) {
                        input.emit(VoiceInputEvent(id, transcript))
                        activatedUntil = 0
                        activity.value = if (wake) AudioSessionState.Waiting else AudioSessionState.Listening
                    }
                }
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            val pcm = ShortArray(512)
            var keywordDetected = false
            var wasSpeech = false
            while (currentCoroutineContext().isActive) {
                val read = withContext(Dispatchers.IO) { recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING) }
                check(read >= 0) { "Ошибка микрофона: $read" }
                if (read == 0 || feedbackPlaying.get()) continue
                val samples = processor.process(FloatArray(read) { pcm[it] / 32768f })
                if (samples.isEmpty()) continue
                if (useKws && keywordSpotter.accept(samples) != null) keywordDetected = true
                val completed = vad.accept(samples)
                val speech = vad.isSpeechDetected()
                if (speech && !wasSpeech && enrollment == null) {
                    val preferences = settingsRepository.readAudioPreferences()
                    if (preferences.bargeIn && !preferences.voiceIdEnabled) speechStarts.tryEmit(id)
                }
                wasSpeech = speech
                for (segment in completed) {
                    if (enrollment != null) {
                        segments.close()
                        recognition.join()
                        enrollment.complete(segment)
                        return@coroutineScope
                    }
                    check(segments.trySend(segment to keywordDetected).isSuccess) { "Распознавание не успевает за речью. Повторите запрос." }
                    keywordDetected = false
                }
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

    override suspend fun speak(text: String, onPlaybackStarted: () -> Unit): Result<Unit> {
        return try {
            mutex.withLock {
                check(!closed.get()) { "Воспроизведение закрыто" }
                stopped.set(false)
                var started = false
                for (chunk in SpeechTextChunker.split(text)) {
                    currentCoroutineContext().ensureActive()
                    if (stopped.get()) break
                    val speech = synthesizer.synthesize(chunk).getOrThrow()
                    val samples = PcmResampler.to16k(speech.samples, speech.sampleRate)
                    if (stopped.get()) break
                    withContext(Dispatchers.IO) {
                        val minimum = AudioTrack.getMinBufferSize(16_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                        check(minimum > 0)
                        val active = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(maxOf(minimum, 2560))
                            .setTransferMode(AudioTrack.MODE_STREAM).build()
                        synchronized(trackLock) { track = active }
                        try {
                            if (!started) {
                                feedback?.triggerVibration()
                                started = true
                                onPlaybackStarted()
                            }
                            active.play()
                            var offset = 0
                            while (offset < samples.size && !stopped.get()) {
                                currentCoroutineContext().ensureActive()
                                val count = minOf(160, samples.size - offset)
                                val frame = samples.copyOfRange(offset, offset + count)
                                val queued = (offset.toLong() - (active.playbackHeadPosition.toLong() and 0xffffffffL)).coerceAtLeast(0)
                                processing.onOutputSamples(frame, 16_000, (queued * 1000 / 16_000 + 30).toInt())
                                var done = 0
                                while (done < count && !stopped.get()) {
                                    val written = active.write(frame, done, count - done, AudioTrack.WRITE_BLOCKING)
                                    check(written > 0 || stopped.get()) { "Ошибка воспроизведения: $written" }
                                    done += written.coerceAtLeast(0)
                                }
                                offset += done
                            }
                            val deadline = SystemClock.elapsedRealtime() + 1500
                            while (!stopped.get() && active.playbackHeadPosition < offset && SystemClock.elapsedRealtime() < deadline) delay(10)
                        } finally {
                            synchronized(trackLock) {
                                if (track === active) track = null
                                runCatching { active.stop() }
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
    }
    override fun close() { closed.set(true); stop(); releaseIfClosed() }
    private fun releaseIfClosed() {
        if (closed.get() && mutex.tryLock()) {
            try { if (resourcesReleased.compareAndSet(false, true)) synthesizer.close() }
            finally { mutex.unlock() }
        }
    }
}
