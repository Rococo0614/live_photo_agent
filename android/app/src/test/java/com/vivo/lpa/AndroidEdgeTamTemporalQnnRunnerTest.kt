package com.vivo.lpa

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
