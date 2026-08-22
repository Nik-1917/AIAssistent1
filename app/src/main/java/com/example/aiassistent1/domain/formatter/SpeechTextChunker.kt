package com.example.aiassistent1.domain.formatter

internal const val SPEECH_SECTION_BOUNDARY = "\uE200"
internal const val SPEECH_ENGLISH_PHRASE_BOUNDARY = "\uE201"

/** Produces one TTS request per sentence and safely splits only oversized sentences. */
object SpeechTextChunker {
    const val MAX_CHUNK_LENGTH = 240

    fun split(text: String, maxChunkLength: Int = MAX_CHUNK_LENGTH): List<String> {
        require(maxChunkLength > 0) { "Максимальная длина фрагмента должна быть положительной" }
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return emptyList()

        return normalized.split(SPEECH_SECTION_BOUNDARY, SPEECH_ENGLISH_PHRASE_BOUNDARY)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .flatMap { section ->
                extractSentences(section).flatMap { sentence ->
                    splitOversizedSentence(sentence, maxChunkLength)
                }
            }
    }

    private fun extractSentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        var sentenceStart = 0
        var index = 0
        while (index < text.length) {
            if (text[index] !in SENTENCE_ENDINGS) {
                index++
                continue
            }

            var sentenceEnd = index + 1
            while (sentenceEnd < text.length && text[sentenceEnd] in SENTENCE_ENDINGS) sentenceEnd++
            while (sentenceEnd < text.length && text[sentenceEnd] in CLOSING_CHARACTERS) sentenceEnd++
            if (sentenceEnd < text.length && !text[sentenceEnd].isWhitespace()) {
                index = sentenceEnd
                continue
            }

            text.substring(sentenceStart, sentenceEnd).trim()
                .takeIf(String::isNotEmpty)
                ?.let(sentences::add)
            while (sentenceEnd < text.length && text[sentenceEnd].isWhitespace()) sentenceEnd++
            sentenceStart = sentenceEnd
            index = sentenceEnd
        }
        text.substring(sentenceStart).trim()
            .takeIf(String::isNotEmpty)
            ?.let(sentences::add)
        return sentences
    }

    private fun splitOversizedSentence(sentence: String, maxChunkLength: Int): List<String> {
        if (sentence.length <= maxChunkLength) return listOf(sentence)
        val chunks = mutableListOf<String>()
        var remaining = sentence
        while (remaining.length > maxChunkLength) {
            val lastAllowedIndex = maxChunkLength - 1
            val comma = remaining.lastIndexOf(',', startIndex = lastAllowedIndex)
            val space = remaining.lastIndexOf(' ', startIndex = lastAllowedIndex)
            val endExclusive = when {
                comma >= 0 -> comma + 1
                space > 0 -> space
                else -> maxChunkLength
            }
            chunks += remaining.substring(0, endExclusive).trim()
            remaining = remaining.substring(endExclusive).trimStart()
        }
        if (remaining.isNotEmpty()) chunks += remaining
        return chunks
    }

    private val SENTENCE_ENDINGS = setOf('.', '!', '?', '…')
    private val CLOSING_CHARACTERS = setOf('"', '\'', '»', ')', ']', '}')
}
