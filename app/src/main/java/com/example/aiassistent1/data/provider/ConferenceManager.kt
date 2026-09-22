package com.example.aiassistent1.data.provider

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.aiassistent1.data.local.*
import com.example.aiassistent1.data.repository.ConferenceRepositoryImpl
import com.example.aiassistent1.domain.interfaces.*
import com.example.aiassistent1.domain.model.TranscriptLimits
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

data class ConferenceRecordingState(
    val recording: Boolean = false, val finishing: Boolean = false,
    val conferenceId: Long? = null, val elapsedMs: Long = 0, val error: String? = null,
    val processingProfile: String = "",
)

class ConferenceManager(
    private val context: Context,
    private val repository: ConferenceRepositoryImpl,
    private val recognizer: SpeechRecognizer,
    private val vad: VoiceActivityDetector,
    private val settings: SettingsRepository,
    private val processing: AudioProcessingManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val stopRequested = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(ConferenceRecordingState())
    val state = mutableState.asStateFlow()
    private val pendingSummary = MutableStateFlow<Long?>(null)
    val summaryRequest = pendingSummary.asStateFlow()
    fun summaryHandled(id: Long) { pendingSummary.compareAndSet(id, null) }

    @Synchronized fun startConference(title: String) {
        if (job?.isActive == true) return
        stopRequested.set(false)
        mutableState.value = ConferenceRecordingState(recording = true)
        job = scope.launch { record(title.trim().ifBlank { "Конференция" }.take(160)) }
    }
    fun stopConference() { stopRequested.set(true) }

    private suspend fun record(title: String) {
        val token = Any()
        var ownsMicrophone = false
        var recorder: AudioRecord? = null
        var processor: AudioProcessingManager.Session? = null
        var id: Long? = null
        var samplesWritten = 0L
        var failure: String? = null
        var file: File? = null
        try {
            MicrophoneCoordinator.awaitAcquire(token)
            ownsMicrophone = true
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) { "Нет разрешения на запись аудио" }
            repository.recoverInterrupted()
            val prefs = settings.readAudioPreferences()
            val directory = File(context.filesDir, "conferences")
            check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать папку записей" }
            val start = System.currentTimeMillis()
            file = File(directory, "conference_${start}.mp3")
            val documentUri = prefs.recordingDirectory?.let {
                val tree = android.net.Uri.parse(it)
                val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
                    android.provider.DocumentsContract.getTreeDocumentId(tree))
                checkNotNull(android.provider.DocumentsContract.createDocument(context.contentResolver, parent,
                    "audio/mpeg", file.name)) { "Не удалось создать MP3 в выбранной папке" }
            }
            val minBuffer = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "Устройство не поддерживает запись 16 кГц" }
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 8192))
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Микрофон недоступен" }
            processor = processing.open(recorder, prefs.conferenceEffects, false)
            val conferenceId = repository.createConference(ConferenceEntity(title = title,
                filePath = documentUri?.toString() ?: file.absolutePath, startTime = start))
            id = conferenceId
            mutableState.update { it.copy(conferenceId = conferenceId, processingProfile = processor.profile) }
            val activeProcessor = processor
            supervisorScope {
                val frames = Channel<FloatArray>(500)
                val transcriptFailure = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val transcription = launch(Dispatchers.Default) {
                    try {
                        vad.prepare()
                        suspend fun save(segments: List<TimedVoiceSegment>) {
                            for (segment in segments) {
                                val result = recognizer.recognize(segment.samples)
                                val text = result.getOrThrow().trim()
                                if (text.isNotBlank()) {
                                    // Bound individual Room rows, including adversarial ASR output.
                                    val chunks = text.chunked(2000)
                                    for (chunk in chunks) {
                                        if (!repository.addTranscriptLine(TranscriptLineEntity(conferenceId = conferenceId,
                                                text = chunk, timestamp = segment.startSample * 1000 / 16_000))) {
                                            error("Достигнут лимит текста. Аудиозапись сохранена.")
                                        }
                                    }
                                }
                            }
                        }
                        for (frame in frames) save(vad.acceptTimed(frame))
                        save(vad.flushTimed())
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        transcriptFailure.set("Транскрипт неполный: ${error.message}")
                        mutableState.update { it.copy(error = transcriptFailure.get()) }
                        // Drain queued frames to keep the recording independent of ASR failure.
                        for (ignored in frames) { /* Audio continues on the capture worker. */ }
                    } finally { vad.reset() }
                }
                try {
                    Mp3Encoder().use { encoder ->
                        val audioOutput = if (documentUri == null) FileOutputStream(file)
                        else android.os.ParcelFileDescriptor.AutoCloseOutputStream(
                            checkNotNull(context.contentResolver.openFileDescriptor(documentUri, "w")))
                        audioOutput.use { output ->
                            recorder.startRecording()
                            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                            val pcm = ShortArray(512)
                            var capturedSamples = 0L
                            var lastSavedSecond = 0L
                            try {
                                while (!stopRequested.get() && currentCoroutineContext().isActive &&
                                    capturedSamples < TranscriptLimits.MAX_DURATION_MS * 16) {
                                    val remaining = (TranscriptLimits.MAX_DURATION_MS * 16 - capturedSamples).coerceAtMost(pcm.size.toLong()).toInt()
                                    val read = recorder.read(pcm, 0, remaining, AudioRecord.READ_BLOCKING)
                                    check(read >= 0) { "Ошибка микрофона: $read" }
                                    if (read == 0) continue
                                    capturedSamples += read
                                    val processed = activeProcessor.process(FloatArray(read) { pcm[it] / 32768f })
                                    if (processed.isEmpty()) continue
                                    val encodedPcm = ShortArray(processed.size) {
                                        (processed[it] * 32767).toInt().coerceIn(-32768, 32767).toShort()
                                    }
                                    output.write(encoder.encode(encodedPcm))
                                    samplesWritten += processed.size
                                    mutableState.update { it.copy(elapsedMs = samplesWritten * 1000 / 16_000) }
                                    if (samplesWritten / 16_000 - lastSavedSecond >= 5) {
                                        lastSavedSecond = samplesWritten / 16_000
                                        repository.updateProgress(conferenceId, samplesWritten * 1000 / 16_000)
                                    }
                                    if (frames.trySend(processed).isFailure) {
                                        transcriptFailure.compareAndSet(null, "Транскрипт неполный: распознавание не успевает за записью")
                                        mutableState.update { it.copy(error = transcriptFailure.get()) }
                                        // Stop transcription rather than assign false timestamps after a dropped frame.
                                        transcription.cancel()
                                        frames.close()
                                    }
                                }
                            } finally {
                                val tail = activeProcessor.flush()
                                if (tail.isNotEmpty()) {
                                    output.write(encoder.encode(ShortArray(tail.size) { (tail[it] * 32767).toInt().coerceIn(-32768, 32767).toShort() }))
                                    samplesWritten += tail.size
                                    frames.trySend(tail)
                                }
                                output.write(encoder.flush())
                                output.fd.sync()
                            }
                        }
                    }
                } finally {
                    runCatching { recorder.stop() }
                    mutableState.update { it.copy(recording = false, finishing = true) }
                    frames.close()
                    if (withTimeoutOrNull(120_000) { transcription.join(); true } != true) {
                        transcription.cancelAndJoin()
                        transcriptFailure.set("Транскрипт неполный: превышено время завершения")
                    }
                }
                failure = transcriptFailure.get()
            }
        } catch (cancelled: CancellationException) {
            failure = "Запись прервана"
            throw cancelled
        } catch (error: Exception) { failure = error.message ?: "Ошибка записи" }
        catch (error: LinkageError) { failure = "Не удалось загрузить аудиобиблиотеку: ${error.message}" }
        finally {
            withContext(NonCancellable) {
                recorder?.let { runCatching { it.stop() }; it.release() }
                processor?.let(processing::close)
                if (ownsMicrophone) MicrophoneCoordinator.release(token)
                id?.let {
                    runCatching { repository.finish(it, if (samplesWritten == 0L) "failed" else "complete",
                        samplesWritten * 1000 / 16_000, failure) }
                    if (samplesWritten > 0 && settings.readAudioPreferences().autoSummary) pendingSummary.value = it
                }
                mutableState.update { it.copy(recording = false, finishing = false, error = failure) }
            }
        }
    }
}
