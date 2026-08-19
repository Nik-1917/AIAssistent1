package com.example.aiassistent1.data.provider

import android.content.Context
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DebugModelProvider(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) : ModelProvider {
    override suspend fun getModelPath(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val modelFileName = settingsRepository.selectedModel.value
            val modelFile = getFile(modelFileName)
            require(modelFile.exists()) {
                "Модель не найдена по пути: ${modelFile.absolutePath}. Загрузите файл $modelFileName."
            }

            require(modelFile.canRead()) {
                "Файл найден, но система запрещает его чтение. Проверьте разрешения приложения."
            }
            
            require(modelFile.length() > 0L) {
                "Файл модели пуст. Попробуйте перезаписать его."
            }
            modelFile.absolutePath
        }
    }

    fun getFile(modelFileName: String): File {
        require(modelFileName.isSafeModelFileName()) { "Некорректное имя файла модели" }
        val modelsDirectory = requireNotNull(context.getExternalFilesDir("models")) {
            "Папка моделей приложения недоступна"
        }.canonicalFile
        val modelFile = File(modelsDirectory, modelFileName).canonicalFile
        require(modelFile.parentFile == modelsDirectory) { "Некорректный путь к модели" }
        return modelFile
    }

    private fun String.isSafeModelFileName(): Boolean =
        isNotBlank() && length <= MAX_MODEL_FILE_NAME_LENGTH &&
            !contains('/') && !contains('\\') && endsWith(".gguf", ignoreCase = true)

    private companion object {
        const val MAX_MODEL_FILE_NAME_LENGTH = 128
    }
}
