package com.example.aiassistent1.domain.model

import java.util.Locale

object WakeWordTokens {
    fun encode(word: String, vocabulary: List<String>): String {
        val tokens = vocabulary.sortedByDescending { it.length }
        // Support character and SentencePiece vocabularies, reject unrepresentable names.
        val candidates = listOf(word, word.lowercase(Locale.ROOT), word.uppercase(Locale.ROOT))
        for (candidate in candidates) {
            for (text in listOf("▁" + candidate.replace(" ", "▁"), candidate)) {
                val result = mutableListOf<String>()
                var offset = 0
                while (offset < text.length) {
                    val token = tokens.firstOrNull { text.startsWith(it, offset) } ?: break
                    result.add(token); offset += token.length
                }
                if (offset == text.length) return result.joinToString(" ")
            }
        }
        error("Имя не поддерживается словарём установленной KWS-модели")
    }
    fun removePrefix(transcript: String, word: String): String? {
        val match = Regex("^\\s*" + Regex.escape(word) + "(?=\\s|[,.!?:;—-]|$)[\\s,.!?:;—-]*",
            RegexOption.IGNORE_CASE).find(transcript) ?: return null
        return transcript.substring(match.range.last + 1).trim()
    }
}
