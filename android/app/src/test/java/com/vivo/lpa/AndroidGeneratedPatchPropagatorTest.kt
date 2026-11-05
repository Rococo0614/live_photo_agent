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
    fun disconnectedPartUsesExtrapolatedRigidTransformInsteadOfEdgeStripe() {
        val width = 8
        val height = 4
        val source = IntArray(width * height) { 0xff101010.toInt() }
        val generated = source.copyOf().also { pixels ->
            pixels[1 * width + 1] = 0xff00ccff.toInt()
            pixels[1 * width + 6] = 0xffff3300.toInt()
        }
        val mask = ByteArray(width * height).also { values ->
            values[1 * width + 2] = 0xff.toByte()
            values[1 * width + 7] = 0xff.toByte()
        }

        val output = AndroidGeneratedPatchPropagator.renderArgb(
            width,
            height,
            source,
            generated,
            mask,
            AndroidMaskBounds(1, 1, 3, 3),
            AndroidMaskBounds(2, 1, 4, 3),
            AndroidMaskBounds(2, 1, 8, 2)
        )

        assertEquals(0xff00ccff.toInt(), output[1 * width + 2])
        assertEquals(0xffff3300.toInt(), output[1 * width + 7])
    }

    @Test
    fun unchangedAnchorBackgroundIsNotPropagatedThroughExpandedMask() {
        val width = 8
        val height = 4
        val source = IntArray(width * height) { 0xff101010.toInt() }
        val anchorSource = source.copyOf()
        val generated = source.copyOf().also { pixels ->
            pixels[1 * width + 1] = 0xff00ccff.toInt()
        }
        val mask = ByteArray(width * height).also { values ->
            values[1 * width + 2] = 0xff.toByte()
            values[1 * width + 7] = 0xff.toByte()
        }

        val output = AndroidGeneratedPatchPropagator.renderArgb(
            width,
            height,
            source,
            generated,
            mask,
            AndroidMaskBounds(1, 1, 3, 3),
            AndroidMaskBounds(2, 1, 4, 3),
            AndroidMaskBounds(2, 1, 8, 2),
            anchorSource
        )

        assertEquals(0xff00ccff.toInt(), output[1 * width + 2])
        assertEquals(source[1 * width + 7], output[1 * width + 7])
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
