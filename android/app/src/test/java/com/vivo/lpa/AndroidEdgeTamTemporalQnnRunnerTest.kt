package com.vivo.lpa

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.floor
import kotlin.random.Random

class AndroidEdgeTamTemporalQnnRunnerTest {
    @Test
    fun nhwcToNchwPreservesFloatBits() {
        val input = ByteBuffer.allocate(2 * 2 * 3 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(12) { input.putFloat(it.toFloat()) }

        val output = AndroidEdgeTamTemporalQnnRunner.transposeNhwcToNchw(
            input.array(),
            height = 2,
            width = 2,
            channels = 3
        )
        val values = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        val actual = FloatArray(12) { values.float }

        assertArrayEquals(
            floatArrayOf(0f, 3f, 6f, 9f, 1f, 4f, 7f, 10f, 2f, 5f, 8f, 11f),
            actual,
            0f
        )
    }

    @Test
    fun alignCornersFalseResizeUsesPixelCenters() {
        val resized = AndroidEdgeTamTemporalQnnRunner.resizeFloatBilinear(
            floatArrayOf(0f, 1f, 2f, 3f),
            2,
            2,
            4,
            4
        )

        assertEquals(0f, resized.first(), 0f)
        assertEquals(3f, resized.last(), 0f)
        assertEquals(0.75f, resized[1 * 4 + 1], 1e-6f)
        assertEquals(2.25f, resized[2 * 4 + 2], 1e-6f)
    }

    @Test
    fun precomputedResizeAxesRemainFloatBitExact() {
        val random = Random(245)
        val input = FloatArray(13 * 11) { random.nextFloat() * 8f - 4f }

        val actual = AndroidEdgeTamTemporalQnnRunner.resizeFloatBilinear(
            input,
            13,
            11,
            47,
            39
        )

        assertArrayEquals(referenceResize(input, 13, 11, 47, 39), actual, 0f)
    }

    private fun referenceResize(
        input: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): FloatArray {
        val output = FloatArray(targetWidth * targetHeight)
        val scaleX = sourceWidth.toDouble() / targetWidth
        val scaleY = sourceHeight.toDouble() / targetHeight
        for (y in 0 until targetHeight) {
            val sourceY = (y + 0.5) * scaleY - 0.5
            val rawTop = floor(sourceY).toInt()
            val top = rawTop.coerceIn(0, sourceHeight - 1)
            val bottom = (rawTop + 1).coerceIn(0, sourceHeight - 1)
            val yWeight = (sourceY - floor(sourceY)).toFloat().coerceIn(0f, 1f)
            for (x in 0 until targetWidth) {
                val sourceX = (x + 0.5) * scaleX - 0.5
                val rawLeft = floor(sourceX).toInt()
                val left = rawLeft.coerceIn(0, sourceWidth - 1)
                val right = (rawLeft + 1).coerceIn(0, sourceWidth - 1)
                val xWeight = (sourceX - floor(sourceX)).toFloat().coerceIn(0f, 1f)
                val topValue = input[top * sourceWidth + left] * (1f - xWeight) +
                    input[top * sourceWidth + right] * xWeight
                val bottomValue = input[bottom * sourceWidth + left] * (1f - xWeight) +
                    input[bottom * sourceWidth + right] * xWeight
                output[y * targetWidth + x] =
                    topValue * (1f - yWeight) + bottomValue * yWeight
            }
        }
        return output
    }
}
