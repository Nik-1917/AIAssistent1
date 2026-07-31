package com.example.aiassistent1.data.provider

import android.content.Context
import android.os.Build
import android.os.Environment
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
            val isInDownload = modelFile.absolutePath.contains("Download")
            
            require(modelFile.exists()) {
                "Модель не найдена по пути: ${modelFile.absolutePath}. Загрузите файл $modelFileName."
            }
            
            if (isInDownload && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    throw IllegalStateException("Нет разрешения на 'Доступ ко всем файлам'. Пожалуйста, разрешите доступ в настройках.")
                }
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

    fun getFile(): File {
        val downloadFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), modelFileName)
        val privateFile = File(context.getExternalFilesDir("models"), modelFileName)
        
        if (downloadFile.exists() && Environment.isExternalStorageManager()) {
            return downloadFile
        }
        
        if (privateFile.exists()) {
            return privateFile
        }
        
        if (downloadFile.exists()) {
            return downloadFile
        }
        
        return privateFile
    }
}