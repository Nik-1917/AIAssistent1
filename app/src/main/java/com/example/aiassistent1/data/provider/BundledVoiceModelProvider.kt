package com.example.aiassistent1.data.provider

import android.content.Context
import com.example.aiassistent1.domain.interfaces.VoiceModelProvider
import com.example.aiassistent1.domain.model.VoiceModelAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BundledVoiceModelProvider(
    private val context: Context,
) : VoiceModelProvider {
    override suspend fun getAssets(): Result<VoiceModelAssets> = runCatching {
        withContext(Dispatchers.IO) {
            REQUIRED_FILES.forEach(::requireNonEmptyAsset)
            VoiceModelAssets(
                asrEncoder = ASR_ENCODER,
                asrDecoder = ASR_DECODER,
                asrJoiner = ASR_JOINER,
                asrTokens = ASR_TOKENS,
                ttsModel = TTS_MODEL,
                ttsTokens = TTS_TOKENS,
                ttsDataDirectory = TTS_DATA_DIRECTORY,
                vadModel = VAD_MODEL,
            )
        }
    }

    private fun requireNonEmptyAsset(path: String) {
        context.assets.open(path).use { input ->
            check(input.read() != -1) { "Голосовая модель повреждена: $path" }
        }
    }

    private companion object {
        const val ASR_DIRECTORY = "voice/asr"
        const val ASR_ENCODER = "$ASR_DIRECTORY/encoder.int8.onnx"
        const val ASR_DECODER = "$ASR_DIRECTORY/decoder.onnx"
        const val ASR_JOINER = "$ASR_DIRECTORY/joiner.onnx"
        const val ASR_TOKENS = "$ASR_DIRECTORY/tokens.txt"
        const val TTS_DIRECTORY = "voice/tts"
        const val TTS_MODEL = "$TTS_DIRECTORY/ru_RU-denis-medium.onnx"
        const val TTS_TOKENS = "$TTS_DIRECTORY/tokens.txt"
        const val TTS_DATA_DIRECTORY = "$TTS_DIRECTORY/espeak-ng-data"
        const val VAD_MODEL = "voice/vad.onnx"

        val REQUIRED_FILES = listOf(
            ASR_ENCODER,
            ASR_DECODER,
            ASR_JOINER,
            ASR_TOKENS,
            TTS_MODEL,
            TTS_TOKENS,
            VAD_MODEL,
        )
    }
}