package com.example.aiassistent1.data.model

import com.example.aiassistent1.domain.model.StoredModel
import java.io.File
import java.util.Locale

/** Lists and changes only GGUF files directly inside the application's model directory. */
class ModelFileCatalog(directory: File) {
    private val root = directory.canonicalFile

    fun list(): List<StoredModel> = root.listFiles().orEmpty()
        .asSequence()
        .filter { file ->
            file.isFile && file.length() > 0L && file.name.isSafeModelFileName() &&
                runCatching { file.canonicalFile == file.absoluteFile && file.canonicalFile.parentFile == root }
                    .getOrDefault(false)
        }
        .map { StoredModel(it.name, it.length()) }
        .sortedWith(compareByDescending<StoredModel> { it.sizeBytes }
            .thenBy { it.fileName.lowercase(Locale.ROOT) }
            .thenBy { it.fileName })
        .toList()

    fun file(fileName: String): File {
        require(fileName.isSafeModelFileName()) { "Некорректное имя файла модели" }
        val directFile = File(root, fileName).absoluteFile
        val canonicalFile = directFile.canonicalFile
        require(canonicalFile == directFile && canonicalFile.parentFile == root) { "Некорректный путь к модели" }
        return directFile
    }

    fun requireSpaceForAnother() {
        require(list().size < MAX_MODELS) {
            "Можно хранить не более $MAX_MODELS моделей. Удалите одну модель, чтобы загрузить другую."
        }
    }

    fun delete(fileName: String) {
        val target = file(fileName)
        require(target.isFile) { "Файл модели не найден" }
        check(target.delete()) { "Не удалось удалить файл модели" }
    }

    private fun String.isSafeModelFileName(): Boolean =
        length <= MAX_FILE_NAME_LENGTH && endsWith(".gguf", ignoreCase = true) &&
            dropLast(GGUF_EXTENSION_LENGTH).isNotBlank() && !contains('/') && !contains('\\')

    companion object {
        const val MAX_MODELS = StoredModel.MAX_COUNT
        private const val MAX_FILE_NAME_LENGTH = 128
        private const val GGUF_EXTENSION_LENGTH = 5
    }
}
