package com.vivo.lpa

import java.io.File

/** Minimal BERT WordPiece tokenizer for BAAI/bge-small-zh-v1.5. */
class BertTokenizer(vocabFile: File) {
    private val vocab = vocabFile.readLines().mapIndexed { index, token -> token to index }.toMap()
    private val unk = vocab["[UNK]"] ?: 100
    private val cls = vocab["[CLS]"] ?: 101
    private val sep = vocab["[SEP]"] ?: 102
    private val pad = vocab["[PAD]"] ?: 0

    fun encode(text: String, maxLength: Int = 128): TokenIds {
        val pieces = mutableListOf<Int>()
        pieces += cls
        basicTokens(text).forEach { token ->
            wordPieces(token).forEach { if (pieces.size < maxLength - 1) pieces += it }
        }
        pieces += sep
        while (pieces.size < maxLength) pieces += pad
        return TokenIds(pieces.map { it.toLong() }.toLongArray(), LongArray(maxLength) { index ->
            if (index < pieces.indexOfLast { it != pad } + 1) 1L else 0L
        })
    }

    data class TokenIds(val ids: LongArray, val mask: LongArray)

    private fun basicTokens(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) { result += current.toString(); current.clear() }
        }
        text.lowercase().forEach { char ->
            when {
                char.isWhitespace() -> flush()
                isCjk(char) || char.isLetterOrDigit() && isPunctuation(char) -> {
                    flush(); result += char.toString()
                }
                isPunctuation(char) -> { flush(); result += char.toString() }
                else -> current.append(char)
            }
        }
        flush()
        return result
    }

    private fun wordPieces(token: String): List<Int> {
        if (token.isEmpty()) return emptyList()
        val result = mutableListOf<Int>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var found: String? = null
            while (start < end) {
                val candidate = token.substring(start, end).let { if (start > 0) "##$it" else it }
                if (vocab.containsKey(candidate)) { found = candidate; break }
                end--
            }
            if (found == null) return listOf(unk)
            result += vocab.getValue(found)
            start = end
        }
        return result
    }

    private fun isCjk(char: Char): Boolean = char.code in 0x4e00..0x9fff

    private fun isPunctuation(char: Char): Boolean =
        char in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~，。！？；：、（）【】"
}
