package com.example.aiassistent1.data.provider

import android.content.Context
import com.example.aiassistent1.domain.interfaces.ModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DebugModelProvider(
    private val context: Context,
    private val modelFileName: String = "qwen2.5-3b-instruct-q4_k_m.gguf",
) : ModelProvider {
    override suspend fun getModelPath(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val modelFile = getFile()
            require(modelFile.exists()) {
                "Модель не найдена. Загрузите файл $modelFileName через приложение."
            }
            require(modelFile.isFile && modelFile.canRead()) {
                "Нет доступа к чтению файла модели."
            }
            require(modelFile.length() > 0L) {
                "Файл модели пуст. Попробуйте загрузить заново."
            }
            modelFile.absolutePath
        }
    }

    fun getFile(): File = File(context.getExternalFilesDir("models"), modelFileName)
}