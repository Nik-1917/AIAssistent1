package com.example.aiassistent1.data.provider

import android.content.Context
import com.example.aiassistent1.domain.interfaces.SpeechRecognizer
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import com.example.aiassistent1.domain.interfaces.SpeechSynthesizer
import com.example.aiassistent1.domain.interfaces.VoiceActivityDetector
import com.example.aiassistent1.domain.interfaces.VoiceModelProvider
import com.example.aiassistent1.domain.model.SpeechRate
import com.example.aiassistent1.domain.model.SynthesizedSpeech
import com.example.aiassistent1.domain.model.VoiceModelAssets
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SherpaOnnxSpeechRecognizer(
    private val context: Context,
    private val modelProvider: VoiceModelProvider,
) : SpeechRecognizer {
    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null

    override suspend fun recognize(samples: FloatArray): Result<String> = runCatching {
        require(samples.isNotEmpty()) { "Аудиофрагмент пуст" }
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val activeRecognizer = recognizer ?: createRecognizer(modelProvider.getAssets().getOrThrow())
                    .also { recognizer = it }
                val stream = activeRecognizer.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    activeRecognizer.decode(stream)
                    activeRecognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            }
        }
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    private fun createRecognizer(assets: VoiceModelAssets): OfflineRecognizer = OfflineRecognizer(
        context.assets,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 64, dither = 0f),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = assets.asrEncoder,
                    decoder = assets.asrDecoder,
                    joiner = assets.asrJoiner,
                ),
                tokens = assets.asrTokens,
                numThreads = NUM_THREADS,
                provider = "cpu",
            ),
        ),
    )

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val NUM_THREADS = 2
    }
}

class SherpaOnnxSpeechSynthesizer(
    private val context: Context,
    private val modelProvider: VoiceModelProvider,
    private val settingsRepository: SettingsRepository,
) : SpeechSynthesizer {
    private val mutex = Mutex()
    private var tts: OfflineTts? = null
    private var activeVoice: String? = null

    override suspend fun synthesize(text: String): Result<SynthesizedSpeech> = runCatching {
        require(text.isNotBlank()) { "Текст для озвучивания пуст" }
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val assets = modelProvider.getAssets().getOrThrow()
                val activeTts = (if (activeVoice == assets.speechVoice.storageId) tts else null)
                    ?: createTts(assets).also {
                        tts?.release()
                        tts = it
                        activeVoice = assets.speechVoice.storageId
                    }
                val audio = activeTts.generate(
                    text.trim(),
                    0,
                    SpeechRate.normalize(settingsRepository.speechRate.value),
                )
                SynthesizedSpeech(samples = audio.samples, sampleRate = audio.sampleRate)
            }
        }
    }

    override fun close() {
        tts?.release()
        tts = null
        activeVoice = null
    }

    private fun createTts(assets: VoiceModelAssets): OfflineTts = OfflineTts(
        context.assets,
        OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = assets.ttsModel,
                    tokens = assets.ttsTokens,
                    dataDir = assets.ttsDataDirectory,
                ),
                numThreads = NUM_THREADS,
                provider = "cpu",
            ),
        ),
    )

    private companion object {
        const val NUM_THREADS = 2
    }
}

class SherpaOnnxVoiceActivityDetector(
    private val context: Context,
    private val modelProvider: VoiceModelProvider,
) : VoiceActivityDetector {
    private val mutex = Mutex()
    private var vad: Vad? = null

    override suspend fun accept(samples: FloatArray): List<FloatArray> {
        require(samples.isNotEmpty()) { "Аудиофрагмент пуст" }
        return mutex.withLock {
            val activeVad = vad ?: createVad(modelProvider.getAssets().getOrThrow()).also { vad = it }
            activeVad.acceptWaveform(samples)
            buildList {
                while (!activeVad.empty()) {
                    add(activeVad.front().samples)
                    activeVad.pop()
                }
            }
        }
    }

    override fun reset() {
        vad?.reset()
    }

    override fun close() {
        vad?.release()
        vad = null
    }

    private fun createVad(assets: VoiceModelAssets): Vad = Vad(
            context.assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = assets.vadModel,
                    threshold = THRESHOLD,
                    minSilenceDuration = MIN_SILENCE_DURATION_SECONDS,
                    minSpeechDuration = MIN_SPEECH_DURATION_SECONDS,
                    windowSize = WINDOW_SIZE,
                    maxSpeechDuration = MAX_SPEECH_DURATION_SECONDS,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            ),
        )

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val THRESHOLD = 0.5f
        const val MIN_SILENCE_DURATION_SECONDS = 0.7f
        const val MIN_SPEECH_DURATION_SECONDS = 0.25f
        const val MAX_SPEECH_DURATION_SECONDS = 30f
        const val WINDOW_SIZE = 512
    }
}
