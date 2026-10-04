package com.example.aiassistent1.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Every emitted chunk is checked with the real, fully formatted prompt tokenizer. */
internal class TokenTextSplitter(
    private val budget: Int,
    private val countPrompt: suspend (String) -> Int,
) {
    fun chunks(parts: Flow<String>): Flow<String> = flow {
        require(budget > 0)
        check(countPrompt("") < budget) { "Недостаточно контекста для правил выжимки" }
        var pending = ""
        parts.collect { part ->
            pending += part
            while (pending.isNotEmpty() && countPrompt(pending) > budget) {
                var low = 0
                var high = pending.length
                // Token counts need not be monotonic: search conservatively and verify every result.
                while (low < high) {
                    val middle = low + (high - low + 1) / 2
                    val end = safeEnd(pending, middle)
                    if (end > 0 && countPrompt(pending.substring(0, end)) <= budget) low = middle
                    else high = middle - 1
                }
                var end = safeEnd(pending, low)
                check(end > 0) { "Даже один символ не помещается вместе с правилами выжимки" }
                val boundary = pending.substring(0, end).indexOfLast { it == '\n' || it == ' ' }
                if (boundary >= end / 2 && countPrompt(pending.substring(0, boundary + 1)) <= budget) end = boundary + 1
                val chunk = pending.substring(0, end)
                check(countPrompt(chunk) <= budget) { "Фрагмент превышает токенный бюджет" }
                emit(chunk)
                pending = pending.substring(end)
            }
        }
        if (pending.isNotEmpty()) {
            check(countPrompt(pending) <= budget)
            emit(pending)
        }
    }

    private fun safeEnd(text: String, end: Int): Int =
        if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end - 1 else end
}
