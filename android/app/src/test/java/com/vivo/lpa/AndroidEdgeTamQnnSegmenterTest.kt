package com.vivo.lpa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.random.Random

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
    fun humanTargetDistributesPositivePointsAcrossUpperAndLowerBody() {
        val prompt = AndroidEdgeTamQnnSegmenter.promptFor(
            grounding("a person sliding on a skateboard"),
            imageWidth = 1_000,
            imageHeight = 800
        )

        assertArrayEquals(floatArrayOf(0.2f, 0.425f, 0.2f, 0.625f), prompt.coordinates, 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 1f), prompt.labels, 0f)
    }

    @Test
    fun nonHumanTargetKeepsValidatedPositivePointPrompt() {
        val prompt = AndroidEdgeTamQnnSegmenter.promptFor(
            grounding("an orange cat walking on green grass"),
            imageWidth = 1_000,
            imageHeight = 800
        )

        assertArrayEquals(floatArrayOf(0.2f, 0.5f, 0.2f, 0.575f), prompt.coordinates, 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 1f), prompt.labels, 0f)
    }

    @Test
    fun broadHumanBoxMovesPromptsAwayFromBackgroundBiasedCenter() {
        val prompt = AndroidEdgeTamQnnSegmenter.promptFor(
            grounding(
                "woman",
                OwlV2InternalBox(100.0, 100.0, 600.0, 500.0)
            ),
            imageWidth = 1_000,
            imageHeight = 800
        )

        assertArrayEquals(floatArrayOf(0.25f, 0.3f, 0.25f, 0.45f), prompt.coordinates, 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 1f), prompt.labels, 0f)
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

    @Test
    fun precomputedResizeAxesRemainBitExactToPerPixelReference() {
        val random = Random(45)
        val sourceSize = 17
        val logits = FloatArray(sourceSize * sourceSize) { random.nextFloat() * 4f - 2f }
        val width = 53
        val height = 31

        val actual = AndroidEdgeTamQnnSegmenter.resizeAndThreshold(
            logits,
            sourceSize,
            width,
            height
        )

        assertArrayEquals(referenceResizeAndThreshold(logits, sourceSize, width, height), actual)
    }

    private fun referenceResizeAndThreshold(
        logits: FloatArray,
        sourceSize: Int,
        width: Int,
        height: Int
    ): ByteArray {
        val output = ByteArray(width * height)
        val scaleX = sourceSize.toDouble() / width
        val scaleY = sourceSize.toDouble() / height
        for (y in 0 until height) {
            val sourceY = (y + 0.5) * scaleY - 0.5
            val rawTop = floor(sourceY).toInt()
            val top = rawTop.coerceIn(0, sourceSize - 1)
            val bottom = (rawTop + 1).coerceIn(0, sourceSize - 1)
            val yWeight = (sourceY - floor(sourceY)).toFloat().coerceIn(0f, 1f)
            for (x in 0 until width) {
                val sourceX = (x + 0.5) * scaleX - 0.5
                val rawLeft = floor(sourceX).toInt()
                val left = rawLeft.coerceIn(0, sourceSize - 1)
                val right = (rawLeft + 1).coerceIn(0, sourceSize - 1)
                val xWeight = (sourceX - floor(sourceX)).toFloat().coerceIn(0f, 1f)
                val topValue = logits[top * sourceSize + left] * (1f - xWeight) +
                    logits[top * sourceSize + right] * xWeight
                val bottomValue = logits[bottom * sourceSize + left] * (1f - xWeight) +
                    logits[bottom * sourceSize + right] * xWeight
                if (topValue * (1f - yWeight) + bottomValue * yWeight > 0f) {
                    output[y * width + x] = 0xff.toByte()
                }
            }
        }
        return output
    }

    private fun grounding(
        query: String,
        box: OwlV2InternalBox = OwlV2InternalBox(100.0, 200.0, 300.0, 600.0)
    ) = OwlV2GroundingResult(
        canonicalQuery = query,
        sourceText = null,
        expectedCount = 1,
        candidates = listOf(OwlV2InternalCandidate(
            candidateId = "candidate-0",
            rawIndex = 0,
            label = 0,
            score = 0.9f,
            box = box
        )),
        disposition = OwlV2GroundingDisposition.CANDIDATE_SET_READY,
        evidenceFingerprint = "0".repeat(64)
    )
}
