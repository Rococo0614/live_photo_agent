package com.vivo.lpa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidEdgeTamQnnSegmenterTest {
    @Test
    fun promptPointsAreNormalizedInteriorPoints() {
        val points = AndroidEdgeTamQnnSegmenter.promptPoints(
            OwlV2InternalBox(100.0, 200.0, 300.0, 600.0),
            imageWidth = 1_000,
            imageHeight = 800
        )

        assertArrayEquals(floatArrayOf(0.2f, 0.5f, 0.2f, 0.575f), points, 1e-6f)
    }

    @Test
    fun maskQualityCountsOnlyPixelsOutsideExpandedCandidateBox() {
        val mask = ByteArray(100) { 0 }
        mask[4 * 10 + 4] = 0xff.toByte()
        mask[9 * 10 + 9] = 0xff.toByte()

        val fraction = AndroidEdgeTamQnnSegmenter.outsideExpandedBoxFraction(
            mask,
            10,
            10,
            OwlV2InternalBox(3.0, 3.0, 6.0, 6.0)
        )

        assertEquals(0.5, fraction, 0.0)
    }

    @Test
    fun positiveLogitsBecomeOpaqueMaskValues() {
        val mask = AndroidEdgeTamQnnSegmenter.resizeAndThreshold(
            floatArrayOf(-1f, 1f, -1f, 1f),
            sourceSize = 2,
            width = 2,
            height = 2
        )

        assertEquals(0, mask[0].toInt() and 0xff)
        assertEquals(255, mask[1].toInt() and 0xff)
        assertTrue(mask.count { (it.toInt() and 0xff) > 0 } == 2)
    }
}
