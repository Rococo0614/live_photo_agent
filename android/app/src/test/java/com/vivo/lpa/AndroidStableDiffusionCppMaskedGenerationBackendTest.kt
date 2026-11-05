package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidStableDiffusionCppMaskedGenerationBackendTest {
    @Test
    fun squareCropContainsMaskAndStaysInsideFrameAtEdge() {
        val width = 1_000
        val height = 600
        val mask = ByteArray(width * height)
        for (y in 500 until 550) for (x in 900 until 950) {
            mask[y * width + x] = 0xff.toByte()
        }

        val crop = AndroidStableDiffusionCppMaskedGenerationBackend.computeSquareCrop(
            mask,
            width,
            height,
            padding = 96
        )

        assertEquals(256, crop.size)
        assertTrue(crop.left >= 0 && crop.left + crop.size <= width)
        assertTrue(crop.top >= 0 && crop.top + crop.size <= height)
        assertTrue(900 >= crop.left && 950 <= crop.left + crop.size)
        assertTrue(500 >= crop.top && 550 <= crop.top + crop.size)
    }

    @Test
    fun nearestMaskScalingPreservesBinaryValues() {
        val mask = byteArrayOf(
            0, 0, 0, 0,
            0, 0xff.toByte(), 0xff.toByte(), 0,
            0, 0xff.toByte(), 0xff.toByte(), 0,
            0, 0, 0, 0
        )

        val scaled = AndroidStableDiffusionCppMaskedGenerationBackend.scaleMaskNearest(
            mask,
            sourceWidth = 4,
            crop = AndroidGenerationSquareCrop(0, 0, 4),
            outputSize = 8
        )

        assertEquals(16, scaled.count { (it.toInt() and 0xff) == 255 })
        assertTrue(scaled.all { (it.toInt() and 0xff) in setOf(0, 255) })
    }

    @Test
    fun numericalGateRejectsAllWhiteBackendFailure() {
        val white = ByteArray(32 * 32 * 3) { 0xff.toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            AndroidStableDiffusionCppMaskedGenerationBackend.validateGeneratedRgb(white, 32)
        }

        val gradient = ByteArray(32 * 32 * 3) { index -> (index % 251).toByte() }
        AndroidStableDiffusionCppMaskedGenerationBackend.validateGeneratedRgb(gradient, 32)
    }
}
