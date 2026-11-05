package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidGeneratedPatchPropagatorTest {
    @Test
    fun generatedAnchorPatchMovesWithCurrentCertifiedMask() {
        val width = 5
        val height = 5
        val source = IntArray(width * height) { 0xff102030.toInt() }
        val generated = source.copyOf().also { pixels ->
            for (y in 1..2) for (x in 1..2) pixels[y * width + x] = 0xff00ccff.toInt()
        }
        val mask = ByteArray(width * height).also { values ->
            for (y in 2..3) for (x in 2..3) values[y * width + x] = 0xff.toByte()
        }
        val output = AndroidGeneratedPatchPropagator.renderArgb(
            width,
            height,
            source,
            generated,
            mask,
            AndroidMaskBounds(1, 1, 3, 3),
            AndroidMaskBounds(2, 2, 4, 4)
        )

        assertEquals(4, output.indices.count { output[it] != source[it] })
        assertEquals(0xff00ccff.toInt(), output[2 * width + 2])
        assertEquals(source[0], output[0])
    }

    @Test
    fun rigidTrajectoryGateRejectsLargeScaleOrAspectChanges() {
        val anchor = AndroidMaskBounds(10, 10, 110, 50)
        AndroidGenerationRegionPolicy.requireRigidTrajectory(
            listOf(anchor, AndroidMaskBounds(20, 15, 125, 57)),
            anchorIndex = 0
        )

        assertThrows(IllegalArgumentException::class.java) {
            AndroidGenerationRegionPolicy.requireRigidTrajectory(
                listOf(anchor, AndroidMaskBounds(0, 0, 220, 120)),
                anchorIndex = 0
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidGenerationRegionPolicy.requireRigidTrajectory(
                listOf(anchor, AndroidMaskBounds(0, 0, 45, 100)),
                anchorIndex = 0
            )
        }
    }
}
