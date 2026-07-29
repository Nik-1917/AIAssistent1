package com.example.aiassistent1.data.provider

import android.content.Context
import com.example.aiassistent1.domain.interfaces.ModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DebugModelProvider(
    private val context: Context,
    private val modelFileName: String = "phi-1_5-Q3_K_M.gguf",
) : ModelProvider {
    override suspend fun getModelPath(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val modelFile = File(context.getExternalFilesDir("models"), modelFileName)
            require(modelFile.isFile && modelFile.canRead()) {
                "Модель не найдена. Передайте $modelFileName в папку files/models через adb."
            }
            require(modelFile.length() > 0L) {
                "Файл модели пуст. Передайте GGUF заново."
            }
            modelFile.absolutePath
        }
    }
}