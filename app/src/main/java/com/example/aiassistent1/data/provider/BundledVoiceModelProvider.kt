package com.example.aiassistent1.data.provider

import android.content.Context
import com.example.aiassistent1.domain.interfaces.VoiceModelProvider
import com.example.aiassistent1.domain.model.VoiceModelAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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
                ttsDataDirectory = copyTtsDataDirectory().absolutePath,
                vadModel = VAD_MODEL,
            )
        }
    }

    private fun copyTtsDataDirectory(): File {
        val targetDirectory = File(context.filesDir, TTS_DATA_DIRECTORY)
        val completedMarker = File(targetDirectory, COPY_COMPLETED_MARKER)
        if (completedMarker.exists()) return targetDirectory

        copyAssetDirectory(TTS_DATA_DIRECTORY, targetDirectory)
        check(completedMarker.createNewFile() || completedMarker.exists()) {
            "Не удалось завершить подготовку голосовых данных"
        }
        return targetDirectory
    }

    private fun copyAssetDirectory(assetPath: String, targetDirectory: File) {
        check(targetDirectory.exists() || targetDirectory.mkdirs()) {
            "Не удалось создать папку голосовых данных"
        }
        val entries = context.assets.list(assetPath).orEmpty()
        entries.forEach { entry ->
            val sourcePath = "$assetPath/$entry"
            val children = context.assets.list(sourcePath).orEmpty()
            val target = File(targetDirectory, entry)
            if (children.isEmpty()) {
                context.assets.open(sourcePath).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            } else {
                copyAssetDirectory(sourcePath, target)
            }
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
        const val COPY_COMPLETED_MARKER = ".copy-complete"

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
