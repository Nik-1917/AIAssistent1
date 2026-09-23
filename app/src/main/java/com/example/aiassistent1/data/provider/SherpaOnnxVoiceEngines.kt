package com.example.aiassistent1.data.provider

import android.content.Context
import com.example.aiassistent1.domain.interfaces.KeywordSpotter as IKeywordSpotter
import com.example.aiassistent1.domain.interfaces.SpeakerIdentifier
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
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
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

    override suspend fun prepare() {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (recognizer == null) {
                    recognizer = createRecognizer(modelProvider.getAssets().getOrThrow())
                }
            }
        }
    }

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

    override suspend fun synthesize(text: String): Result<SynthesizedSpeech> = runCatching {
        require(text.isNotBlank()) { "Текст для озвучивания пуст" }
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val activeTts = tts ?: createTts(modelProvider.getAssets().getOrThrow())
                    .also { tts = it }
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
    @Volatile private var speechDetected = false

    override suspend fun prepare() {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (vad == null) {
                    vad = createVad(modelProvider.getAssets().getOrThrow())
                }
            }
        }
    }

    override suspend fun accept(samples: FloatArray): List<FloatArray> = acceptTimed(samples).map { it.samples }

    override fun isSpeechDetected(): Boolean = speechDetected

    override suspend fun flushTimed(): List<com.example.aiassistent1.domain.interfaces.TimedVoiceSegment> = mutex.withLock {
        val activeVad = vad ?: return@withLock emptyList()
        activeVad.flush()
        speechDetected = activeVad.isSpeechDetected()
        drain(activeVad)
    }

    private fun drain(activeVad: Vad) = buildList {
        while (!activeVad.empty()) {
            val segment = activeVad.front()
            add(com.example.aiassistent1.domain.interfaces.TimedVoiceSegment(segment.start.toLong(), segment.samples))
            activeVad.pop()
        }
    }

    override suspend fun acceptTimed(samples: FloatArray): List<com.example.aiassistent1.domain.interfaces.TimedVoiceSegment> {
        require(samples.isNotEmpty()) { "Аудиофрагмент пуст" }
        return mutex.withLock {
            val activeVad = vad ?: createVad(modelProvider.getAssets().getOrThrow()).also { vad = it }
            activeVad.acceptWaveform(samples)
            // The UI reads the cached flag without entering the native capture engine.
            speechDetected = activeVad.isSpeechDetected()
            drain(activeVad)
        }
    }

    override fun reset() {
        speechDetected = false
        vad?.reset()
    }

    override fun close() {
        speechDetected = false
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

class SherpaOnnxKeywordSpotter(
    private val context: Context,
    private val modelProvider: VoiceModelProvider,
    private val settingsRepository: SettingsRepository,
) : IKeywordSpotter {
    private val mutex = Mutex()
    private var kws: KeywordSpotter? = null
    private var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
    private var configuredWord: String? = null

    private val available by lazy { listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
        .all { name -> runCatching { context.assets.open("voice/kws/$name").use { it.read() >= 0 } }.getOrDefault(false) }
    }
    override fun isAvailable(): Boolean = available

    override suspend fun prepare() = withContext(Dispatchers.Default) {
        val word = settingsRepository.readAudioPreferences().wakeWord
        mutex.withLock { ensureStream(word) }
    }

    override suspend fun accept(samples: FloatArray): String? = withContext(Dispatchers.Default) {
        val word = settingsRepository.readAudioPreferences().wakeWord
        mutex.withLock {
            ensureStream(word)
            val engine = checkNotNull(kws)
            val activeStream = checkNotNull(stream)
            activeStream.acceptWaveform(samples, 16_000)
            while (engine.isReady(activeStream)) engine.decode(activeStream)
            engine.getResult(activeStream).keyword.takeIf(String::isNotBlank)?.also { engine.reset(activeStream) }
        }
    }

    private fun ensureStream(word: String) {
        check(isAvailable()) { "Совместимая KWS-модель не установлена" }
        if (kws == null) {
            // Never load the bundled offline GigaAM as a streaming model.
            kws = KeywordSpotter(context.assets, KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(encoder = "voice/kws/encoder.onnx",
                        decoder = "voice/kws/decoder.onnx", joiner = "voice/kws/joiner.onnx"),
                    tokens = "voice/kws/tokens.txt", modelType = "zipformer2", numThreads = 1),
                keywordsFile = "",
            ))
        }
        if (stream == null || configuredWord != word) {
            val tokens = context.assets.open("voice/kws/tokens.txt").bufferedReader().useLines { lines ->
                lines.map { it.substringBeforeLast(' ') }.filter { it.isNotBlank() && !it.startsWith("<") }.toList()
            }
            val encoded = com.example.aiassistent1.domain.model.WakeWordTokens.encode(word, tokens)
            stream?.release()
            stream = kws!!.createStream("$encoded @$word")
            configuredWord = word
        }
    }
    override fun reset() { stream?.let { kws?.reset(it) } }
    override fun close() {
        stream?.release(); stream = null
        kws?.release(); kws = null; configuredWord = null
    }
}

class SherpaOnnxSpeakerIdentifier(
    private val context: Context,
    private val modelProvider: VoiceModelProvider,
) : SpeakerIdentifier {
    private val mutex = Mutex()
    private var extractor: SpeakerEmbeddingExtractor? = null
    override suspend fun prepare() = withContext(Dispatchers.Default) {
        mutex.withLock { getExtractor() }
        Unit
    }
    private suspend fun getExtractor(): SpeakerEmbeddingExtractor =
        extractor ?: run {
            val path = modelProvider.getAssets().getOrThrow().speakerModel
                ?: error("Модель Voice ID не установлена")
            context.assets.open(path).use { check(it.read() != -1) }
            SpeakerEmbeddingExtractor(context.assets,
                SpeakerEmbeddingExtractorConfig(model = path, numThreads = 1, debug = false, provider = "cpu"))
                .also { extractor = it }
        }

    override suspend fun computeEmbedding(samples: FloatArray): FloatArray? = withContext(Dispatchers.Default) {
        if (samples.size < 8_000 || samples.any { !it.isFinite() }) return@withContext null
        mutex.withLock {
            val active = getExtractor()
            val stream = active.createStream()
            try {
                stream.acceptWaveform(samples, 16_000)
                stream.inputFinished()
                if (!active.isReady(stream)) null
                else active.compute(stream).takeIf(com.example.aiassistent1.domain.model.VoiceEmbedding::isValid)
            } finally { stream.release() }
        }
    }
    override fun verify(embedding1: FloatArray, embedding2: FloatArray, threshold: Float) =
        com.example.aiassistent1.domain.model.VoiceEmbedding.matches(embedding1, embedding2, threshold)
    override fun close() { extractor?.release(); extractor = null }
}
