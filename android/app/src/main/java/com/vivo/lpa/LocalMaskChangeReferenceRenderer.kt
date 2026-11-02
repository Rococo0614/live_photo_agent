package com.vivo.lpa

import kotlin.math.roundToInt

/**
 * Pure CPU oracle for future GPU/SDK differential tests.
 *
 * It operates on in-memory ARGB pixels, never reads or writes media, and is not a production video
 * renderer. Pixels outside the mask remain bit-exact.
 */
object LocalMaskChangeReferenceRenderer {
    fun renderArgb(
        width: Int,
        height: Int,
        sourceArgb: IntArray,
        maskAlpha: ByteArray,
        change: LocalDeterministicChange
    ): IntArray {
        require(width > 0 && height > 0)
        require(sourceArgb.size == width * height)
        require(maskAlpha.size == sourceArgb.size)
        return when (change) {
            is LocalDeterministicChange.ColorTint -> colorTint(sourceArgb, maskAlpha, change)
            is LocalDeterministicChange.Blur -> blur(width, height, sourceArgb, maskAlpha, change)
        }
    }

    private fun colorTint(
        source: IntArray,
        mask: ByteArray,
        change: LocalDeterministicChange.ColorTint
    ): IntArray {
        val color = change.colorHex.removePrefix("#").toInt(16)
        val targetR = color ushr 16 and 0xff
        val targetG = color ushr 8 and 0xff
        val targetB = color and 0xff
        return IntArray(source.size) { index ->
            val alpha = maskValue(mask[index]) * change.intensity
            blendRgb(source[index], targetR, targetG, targetB, alpha)
        }
    }

    private fun blur(
        width: Int,
        height: Int,
        source: IntArray,
        mask: ByteArray,
        change: LocalDeterministicChange.Blur
    ): IntArray {
        val radius = change.radiusPx.roundToInt().coerceIn(1, 64)
        val red = boxBlurChannel(width, height, source, radius, 16)
        val green = boxBlurChannel(width, height, source, radius, 8)
        val blue = boxBlurChannel(width, height, source, radius, 0)
        return IntArray(source.size) { index ->
            blendRgb(
                source[index],
                red[index],
                green[index],
                blue[index],
                maskValue(mask[index])
            )
        }
    }

    private fun boxBlurChannel(
        width: Int,
        height: Int,
        source: IntArray,
        radius: Int,
        shift: Int
    ): IntArray {
        val horizontal = IntArray(source.size)
        for (y in 0 until height) {
            var sum = 0
            var left = 0
            var right = minOf(width - 1, radius)
            for (x in left..right) sum += source[y * width + x] ushr shift and 0xff
            for (x in 0 until width) {
                horizontal[y * width + x] = sum / (right - left + 1)
                val nextLeft = maxOf(0, x + 1 - radius)
                val nextRight = minOf(width - 1, x + 1 + radius)
                while (left < nextLeft) {
                    sum -= source[y * width + left] ushr shift and 0xff
                    left += 1
                }
                while (right < nextRight) {
                    right += 1
                    sum += source[y * width + right] ushr shift and 0xff
                }
            }
        }

        val output = IntArray(source.size)
        for (x in 0 until width) {
            var sum = 0
            var top = 0
            var bottom = minOf(height - 1, radius)
            for (y in top..bottom) sum += horizontal[y * width + x]
            for (y in 0 until height) {
                output[y * width + x] = sum / (bottom - top + 1)
                val nextTop = maxOf(0, y + 1 - radius)
                val nextBottom = minOf(height - 1, y + 1 + radius)
                while (top < nextTop) {
                    sum -= horizontal[top * width + x]
                    top += 1
                }
                while (bottom < nextBottom) {
                    bottom += 1
                    sum += horizontal[bottom * width + x]
                }
            }
        }
        return output
    }

    private fun blendRgb(source: Int, red: Int, green: Int, blue: Int, alpha: Double): Int {
        if (alpha <= 0.0) return source
        val bounded = alpha.coerceIn(0.0, 1.0)
        val sourceA = source ushr 24 and 0xff
        val sourceR = source ushr 16 and 0xff
        val sourceG = source ushr 8 and 0xff
        val sourceB = source and 0xff
        val outR = mix(sourceR, red, bounded)
        val outG = mix(sourceG, green, bounded)
        val outB = mix(sourceB, blue, bounded)
        return sourceA shl 24 or (outR shl 16) or (outG shl 8) or outB
    }

    private fun mix(source: Int, target: Int, alpha: Double): Int =
        (source * (1.0 - alpha) + target * alpha).roundToInt().coerceIn(0, 255)

    private fun maskValue(value: Byte): Double = (value.toInt() and 0xff) / 255.0
}
