package com.vivo.lpa

import android.graphics.Bitmap
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max

data class OwlV2TextInput(val inputIds: IntArray, val attentionMask: IntArray) {
    init {
        require(inputIds.size == OwlV2ClipTokenizer.SEQUENCE_LENGTH)
        require(attentionMask.size == inputIds.size)
    }
}

/** CLIP byte-level BPE used by the official OWLv2 checkpoint. */
class OwlV2ClipTokenizer private constructor(
    private val vocabulary: Map<String, Int>,
    mergeRanks: Map<Pair<String, String>, Int>
) {
    private val ranks = mergeRanks.toMap()
    private val cache = ConcurrentHashMap<String, List<String>>()
    private val byteEncoder = byteEncoder()

    fun encode(canonicalQuery: String): OwlV2TextInput {
        require(canonicalQuery.isNotBlank())
        require(canonicalQuery.all { it.isWhitespace() || it.code in 0x20..0x7e }) {
            "first OWLv2 Android bridge requires a Qwen-authored ASCII canonical query"
        }
        val normalized = canonicalQuery.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
        val bpeIds = mutableListOf<Int>()
        val matcher = TOKEN_PATTERN.matcher(normalized)
        while (matcher.find()) {
            val bytes = matcher.group().toByteArray(StandardCharsets.UTF_8)
            val encoded = buildString(bytes.size) {
                bytes.forEach { byte -> append(requireNotNull(byteEncoder[byte.toInt() and 0xff])) }
            }
            bpe(encoded).forEach { token ->
                bpeIds += vocabulary[token] ?: END_OF_TEXT_ID
            }
        }
        require(bpeIds.size <= SEQUENCE_LENGTH - 2) {
            "canonical locator query exceeds ${SEQUENCE_LENGTH - 2} CLIP BPE tokens"
        }
        val ids = IntArray(SEQUENCE_LENGTH)
        val attention = IntArray(SEQUENCE_LENGTH)
        val sequence = listOf(START_OF_TEXT_ID) + bpeIds + END_OF_TEXT_ID
        sequence.forEachIndexed { index, id ->
            ids[index] = id
            attention[index] = 1
        }
        return OwlV2TextInput(ids, attention)
    }

    private fun bpe(token: String): List<String> = cache.getOrPut(token) {
        if (token.isEmpty()) return@getOrPut emptyList()
        var word = token.dropLast(1).map(Char::toString) + (token.last() + "</w>")
        while (word.size > 1) {
            val pairs = word.zipWithNext().toSet()
            val pair = pairs.minByOrNull { ranks[it] ?: Int.MAX_VALUE } ?: break
            if (pair !in ranks) break
            val merged = mutableListOf<String>()
            var index = 0
            while (index < word.size) {
                if (index < word.lastIndex && word[index] == pair.first &&
                    word[index + 1] == pair.second
                ) {
                    merged += pair.first + pair.second
                    index += 2
                } else {
                    merged += word[index]
                    index += 1
                }
            }
            word = merged
        }
        word
    }

    companion object {
        const val SEQUENCE_LENGTH = 16
        const val START_OF_TEXT_ID = 49406
        const val END_OF_TEXT_ID = 49407

        private val TOKEN_PATTERN = Pattern.compile(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|" +
                "[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )

        fun fromFiles(vocabularyJson: File, mergesText: File): OwlV2ClipTokenizer {
            require(vocabularyJson.isFile && mergesText.isFile)
            val json = org.json.JSONObject(vocabularyJson.readText())
            val vocabulary = json.keys().asSequence().associateWith { json.getInt(it) }
            val merges = mergesText.useLines { lines ->
                lines.drop(1).take(48_894).mapIndexed { index, line ->
                    val pieces = line.trim().split(' ')
                    require(pieces.size == 2) { "invalid CLIP merge row $index" }
                    (pieces[0] to pieces[1]) to index
                }.toMap()
            }
            return OwlV2ClipTokenizer(vocabulary, merges)
        }

        internal fun fromTables(
            vocabulary: Map<String, Int>,
            mergesInRankOrder: List<Pair<String, String>>
        ): OwlV2ClipTokenizer = OwlV2ClipTokenizer(
            vocabulary,
            mergesInRankOrder.mapIndexed { index, pair -> pair to index }.toMap()
        )

        private fun byteEncoder(): Map<Int, Char> {
            val bytes = (
                ('!'..'~').map(Char::code) +
                    ('¡'..'¬').map(Char::code) +
                    ('®'..'ÿ').map(Char::code)
                ).toMutableList()
            val characters = bytes.toMutableList()
            var extra = 0
            for (byte in 0..255) {
                if (byte !in bytes) {
                    bytes += byte
                    characters += 256 + extra
                    extra += 1
                }
            }
            return bytes.indices.associate { bytes[it] to characters[it].toChar() }
        }
    }
}

/** Exact host-side shape/order contract for the official fast OWLv2 image processor. */
object OwlV2ImagePreprocessor {
    const val IMAGE_SIZE = 960
    private val means = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val standardDeviations = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    fun preprocess(bitmap: Bitmap): FloatArray {
        require(!bitmap.isRecycled)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return preprocessArgb(bitmap.width, bitmap.height, pixels)
    }

    internal fun preprocessArgb(
        width: Int,
        height: Int,
        argb: IntArray,
        targetSize: Int = IMAGE_SIZE
    ): FloatArray {
        require(width > 0 && height > 0 && targetSize > 0)
        require(argb.size == width * height)
        val paddedSize = max(width, height)
        val output = FloatArray(3 * targetSize * targetSize)
        val factor = paddedSize.toDouble() / targetSize
        val sigma = max((factor - 1.0) / 2.0, 0.0)
        for (channel in 0..2) {
            val shift = 16 - channel * 8
            val padded = FloatArray(paddedSize * paddedSize) { 0.5f }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    padded[y * paddedSize + x] =
                        ((argb[y * width + x] ushr shift) and 0xff) / 255f
                }
            }
            val filtered = if (sigma > 0.0) gaussianBlur(padded, paddedSize, sigma) else padded
            val resized = bilinearResize(filtered, paddedSize, targetSize)
            val outputOffset = channel * targetSize * targetSize
            for (index in resized.indices) {
                output[outputOffset + index] =
                    (resized[index] - means[channel]) / standardDeviations[channel]
            }
        }
        return output
    }

    private fun gaussianBlur(input: FloatArray, size: Int, sigma: Double): FloatArray {
        val radius = ceil(3.0 * sigma).toInt()
        if (radius == 0) return input
        val kernel = FloatArray(radius * 2 + 1)
        var total = 0.0
        for (offset in -radius..radius) {
            val value = exp(-(offset * offset) / (2.0 * sigma * sigma))
            kernel[offset + radius] = value.toFloat()
            total += value
        }
        kernel.indices.forEach { kernel[it] = (kernel[it] / total).toFloat() }
        val horizontal = FloatArray(input.size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                var value = 0f
                for (offset in -radius..radius) {
                    value += input[y * size + reflect(x + offset, size)] * kernel[offset + radius]
                }
                horizontal[y * size + x] = value
            }
        }
        val output = FloatArray(input.size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                var value = 0f
                for (offset in -radius..radius) {
                    value += horizontal[reflect(y + offset, size) * size + x] *
                        kernel[offset + radius]
                }
                output[y * size + x] = value
            }
        }
        return output
    }

    private fun bilinearResize(input: FloatArray, sourceSize: Int, targetSize: Int): FloatArray {
        if (sourceSize == targetSize) return input.copyOf()
        val output = FloatArray(targetSize * targetSize)
        val scale = sourceSize.toDouble() / targetSize
        for (y in 0 until targetSize) {
            val sourceY = (y + 0.5) * scale - 0.5
            val rawTop = floor(sourceY).toInt()
            val top = rawTop.coerceIn(0, sourceSize - 1)
            val bottom = (rawTop + 1).coerceIn(0, sourceSize - 1)
            val yWeight = (sourceY - floor(sourceY)).toFloat().coerceIn(0f, 1f)
            for (x in 0 until targetSize) {
                val sourceX = (x + 0.5) * scale - 0.5
                val rawLeft = floor(sourceX).toInt()
                val left = rawLeft.coerceIn(0, sourceSize - 1)
                val right = (rawLeft + 1).coerceIn(0, sourceSize - 1)
                val xWeight = (sourceX - floor(sourceX)).toFloat().coerceIn(0f, 1f)
                val topValue = input[top * sourceSize + left] * (1f - xWeight) +
                    input[top * sourceSize + right] * xWeight
                val bottomValue = input[bottom * sourceSize + left] * (1f - xWeight) +
                    input[bottom * sourceSize + right] * xWeight
                output[y * targetSize + x] = topValue * (1f - yWeight) +
                    bottomValue * yWeight
            }
        }
        return output
    }

    private fun reflect(index: Int, size: Int): Int {
        if (size <= 1) return 0
        var value = index
        while (value < 0 || value >= size) {
            value = if (value < 0) -value else 2 * size - value - 2
        }
        return value
    }
}
