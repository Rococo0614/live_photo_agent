package com.vivo.lpa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalMaskChangeReferenceRendererTest {
    @Test
    fun colorTintOnlyChangesMaskedPixelsAndPreservesAlpha() {
        val source = intArrayOf(0x80000000.toInt(), 0xffffffff.toInt())

        val output = LocalMaskChangeReferenceRenderer.renderArgb(
            2,
            1,
            source,
            byteArrayOf(0xff.toByte(), 0),
            LocalDeterministicChange.ColorTint("#ff0000", 0.5)
        )

        assertEquals(0x80800000.toInt(), output[0])
        assertEquals(source[1], output[1])
    }

    @Test
    fun softMaskProducesDeterministicFractionalBlend() {
        val output = LocalMaskChangeReferenceRenderer.renderArgb(
            1,
            1,
            intArrayOf(0xff000000.toInt()),
            byteArrayOf(128.toByte()),
            LocalDeterministicChange.ColorTint("#ffffff", 1.0)
        )

        assertEquals(0xff808080.toInt(), output.single())
    }

    @Test
    fun blurUsesNeighbourhoodButNeverChangesPixelsOutsideMask() {
        val black = 0xff000000.toInt()
        val source = intArrayOf(black, 0xffffffff.toInt(), black)

        val output = LocalMaskChangeReferenceRenderer.renderArgb(
            3,
            1,
            source,
            byteArrayOf(0, 0xff.toByte(), 0),
            LocalDeterministicChange.Blur(1.0)
        )

        assertArrayEquals(intArrayOf(black, 0xff555555.toInt(), black), output)
    }

    @Test
    fun dimensionsAndMaskSizeAreValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalMaskChangeReferenceRenderer.renderArgb(
                2,
                2,
                intArrayOf(0),
                byteArrayOf(0),
                LocalDeterministicChange.Blur(1.0)
            )
        }
    }
}
