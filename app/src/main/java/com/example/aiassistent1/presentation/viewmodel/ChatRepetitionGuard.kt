package com.example.aiassistent1.presentation.viewmodel

/** Bounded chat-only detector; ignores whitespace/JSON punctuation and handles split Unicode escapes. */
internal class ChatRepetitionGuard {
    var stopped = false
        private set
    private val recent = IntArray(MAX_PERIOD * REPEATS)
    private var end = 0
    private var size = 0
    private var highSurrogate: Char? = null
    private var escape = false
    private var unicode: StringBuilder? = null

    /** Returns the UTF-16 prefix ending exactly at the fifth occurrence, never a sixth. */
    fun accept(delta: String): Int {
        if (stopped) return 0
        for ((index, ch) in delta.withIndex()) {
            val digits = unicode
            when {
                digits != null -> {
                    if (ch.digitToIntOrNull(16) == null) {
                        unicode = null
                        decoded('\\'); decoded('u')
                        digits.forEach(::decoded)
                        decoded(ch)
                    } else {
                        digits.append(ch)
                        if (digits.length == 4) {
                            unicode = null
                            decoded(digits.toString().toInt(16).toChar())
                        }
                    }
                }
                escape -> {
                    escape = false
                    when (ch) {
                        'u' -> unicode = StringBuilder()
                        'n' -> decoded('\n')
                        'r' -> decoded('\r')
                        't' -> decoded('\t')
                        else -> decoded(ch)
                    }
                }
                ch == '\\' -> escape = true
                else -> decoded(ch)
            }
            if (stopped) return index + 1
        }
        return delta.length
    }

    private fun decoded(ch: Char) {
        val high = highSurrogate
        highSurrogate = null
        if (high != null && ch.isLowSurrogate()) {
            codePoint(Character.toCodePoint(high, ch))
        } else if (ch.isHighSurrogate()) {
            highSurrogate = ch
        } else if (!ch.isLowSurrogate()) {
            codePoint(ch.code)
        }
    }

    private fun codePoint(value: Int) {
        if (stopped) return
        recent[end] = value
        end = (end + 1) % recent.size
        size = (size + 1).coerceAtMost(recent.size)
        fun back(distance: Int) = recent[(end - 1 - distance + recent.size) % recent.size]
        for (period in 1..minOf(MAX_PERIOD, size / REPEATS)) {
            if (back(0) != back(period)) continue
            var same = true
            for (offset in period until period * REPEATS) {
                if (back(offset) != back(offset % period)) { same = false; break }
            }
            if (same && (0 until period).any { meaningful(back(it)) }) {
                stopped = true
                return
            }
        }
    }

    private fun meaningful(value: Int): Boolean = !Character.isWhitespace(value) &&
        value !in intArrayOf('{'.code, '}'.code, '['.code, ']'.code, '"'.code, '\''.code,
            ':'.code, ','.code, '`'.code, '\\'.code)

    companion object {
        const val REPEATS = 5
        private const val MAX_PERIOD = 256
    }
}
