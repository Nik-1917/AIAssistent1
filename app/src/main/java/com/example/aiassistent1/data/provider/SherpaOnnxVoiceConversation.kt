package com.example.aiassistent1.data.provider

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.aiassistent1.domain.interfaces.InputProvider
import com.example.aiassistent1.domain.interfaces.SpeechPlayback
import com.example.aiassistent1.domain.interfaces.SpeechRecognizer
import com.example.aiassistent1.domain.interfaces.SpeechSynthesizer
import com.example.aiassistent1.domain.interfaces.VoiceActivityDetector
import com.example.aiassistent1.domain.model.VoiceInputError
import com.example.aiassistent1.domain.model.VoiceInputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SherpaOnnxVoiceInputProvider(
    private val context: Context,
    private val recognizer: SpeechRecognizer,
    private val vad: VoiceActivityDetector,
) : InputProvider, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val input = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 1)
    private val errors = MutableSharedFlow<VoiceInputError>(extraBufferCapacity = 1)
    private val lock = Any()
    private var recordingJob: Job? = null
    private var activeSessionId: Long? = null
    private var nextSessionId = 0L

    override fun observeInput(): Flow<VoiceInputEvent> = input

    override fun observeErrors(): Flow<VoiceInputError> = errors

    override fun start(): Long = startCapture()

    override fun startContinuous(): Long = startCapture()

    private fun startCapture(): Long {
        synchronized(lock) {
            activeSessionId?.let { sessionId ->
                if (recordingJob?.isActive == true) return sessionId
            }
            check(
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            ) { "Нет разрешения на запись аудио" }
            val previousRecordingJob = recordingJob
            val sessionId = ++nextSessionId
            activeSessionId = sessionId
            recordingJob = scope.launch {
                previousRecordingJob?.join()
                try {
                    captureSpeech(sessionId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Voice capture failed", error)
                    if (isCurrentSession(sessionId)) errors.emit(VoiceInputError(sessionId, error))
                }
            }
            return sessionId
        }
    }

    override fun stop() {
        synchronized(lock) {
            activeSessionId = null
            recordingJob?.cancel()
        }
    }

    override fun close() {
        stop()
        scope.cancel()
        recognizer.close()
        vad.close()
    }

    private suspend fun captureSpeech(sessionId: Long) {
        val recorder = createRecorder()
        val pcm = ShortArray(FRAME_SIZE)
        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Не удалось запустить запись с микрофона"
            }
            Log.d(TAG, "Voice capture started")
            captureSegmentedSpeech(recorder, pcm, sessionId)
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release()
            vad.reset()
            synchronized(lock) {
                if (activeSessionId == sessionId) activeSessionId = null
            }
            Log.d(TAG, "Voice capture stopped")
        }
    }

    private suspend fun captureSegmentedSpeech(
        recorder: AudioRecord,
        pcm: ShortArray,
        sessionId: Long,
    ) {
        while (currentCoroutineContext().isActive) {
            val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
            check(count >= 0) { "Ошибка чтения данных микрофона: $count" }
            if (count == 0) continue
            val samples = FloatArray(count) { index -> pcm[index] / SHORT_SCALE }
            val segments = vad.accept(samples)
            if (segments.isNotEmpty()) Log.d(TAG, "Voice segment completed")
            segments.forEach { segment ->
                val recognition = recognizer.recognize(segment)
                recognition.exceptionOrNull()?.let { error ->
                    Log.e(TAG, "Speech recognition failed", error)
                }
                val transcript = recognition.getOrNull()
                    ?.takeIf(String::isNotBlank)
                if (transcript != null && currentCoroutineContext().isActive && isCurrentSession(sessionId)) {
                    Log.d(TAG, "Speech recognized, length=${transcript.length}")
                    input.emit(VoiceInputEvent(sessionId, transcript))
                }
            }
        }
    }

    private fun createRecorder(): AudioRecord {
        val minimumBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBufferSize > 0) { "Микрофон не поддерживает PCM ${SAMPLE_RATE} Hz" }
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimumBufferSize, FRAME_SIZE * Short.SIZE_BYTES))
            .build()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "Не удалось инициализировать микрофон"
        }
        return recorder
    }

    private fun isCurrentSession(sessionId: Long): Boolean = synchronized(lock) {
        activeSessionId == sessionId
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SIZE = 512
        const val SHORT_SCALE = 32_768f
        const val TAG = "VoiceInput"
    }

}

class SherpaOnnxSpeechPlayback(
    private val synthesizer: SpeechSynthesizer,
) : SpeechPlayback {
    private val mutex = Mutex()
    private var track: AudioTrack? = null

    override suspend fun speak(text: String): Result<Unit> = runCatching {
        mutex.withLock {
            val speech = synthesizer.synthesize(text).getOrThrow()
            val activeTrack = createTrack(speech.sampleRate, speech.samples.size).also { track = it }
            try {
                activeTrack.play()
                activeTrack.write(speech.samples, 0, speech.samples.size, AudioTrack.WRITE_BLOCKING)
                activeTrack.stop()
            } finally {
                activeTrack.release()
                if (track === activeTrack) track = null
            }
        }
    }

    override fun stop() {
        track?.pause()
        track?.flush()
    }

    override fun close() {
        stop()
        track?.release()
        track = null
        synthesizer.close()
    }

    private fun createTrack(sampleRate: Int, sampleCount: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(maxOf(sampleCount * Float.SIZE_BYTES, MINIMUM_BUFFER_BYTES))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private companion object {
        const val MINIMUM_BUFFER_BYTES = 8_192
    }
}