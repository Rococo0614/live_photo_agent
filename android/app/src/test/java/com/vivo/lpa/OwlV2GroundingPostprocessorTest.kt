package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OwlV2GroundingPostprocessorTest {
    @Test
    fun hostNmsKeepsDistinctCandidatesAndRequestsLanguageClarification() {
        val result = process(expectedCount = 1)

        assertEquals(OwlV2GroundingDisposition.NEED_USER, result.disposition)
        assertEquals(2, result.candidates.size)
        assertEquals(listOf(0, 2), result.candidates.map { it.rawIndex })
        assertEquals(150.0, result.candidates[0].box.left, 1e-9)
        assertEquals(450.0, result.candidates[0].box.right, 1e-9)
        assertFalse(result.contentMutationAllowed)
        assertTrue(Regex("[0-9a-f]{64}").matches(result.evidenceFingerprint))
    }

    @Test
    fun expectedCardinalityProducesCandidateSetWithoutExposingBoxToGoalIr() {
        val result = process(expectedCount = 2)
        val observation = result.toShadowObservation("change-1", "asset-1", 100)

        assertEquals(OwlV2GroundingDisposition.CANDIDATE_SET_READY, result.disposition)
        assertEquals("an orange cat", observation.semanticTarget)
        assertEquals(2, observation.candidateCount)
        assertEquals(result.evidenceFingerprint, observation.locatorEvidenceFingerprint)
    }

    @Test
    fun noCandidateRequestsObservationRatherThanUserSelection() {
        val result = OwlV2GroundingPostprocessor.process(
            canonicalQuery = "a cat",
            sourceText = "猫",
            stateFingerprint = "state-1",
            imageWidth = 1080,
            imageHeight = 1440,
            boxesXyxy = floatArrayOf(0f, 0f, 10f, 10f),
            scores = floatArrayOf(0.01f),
            labels = intArrayOf(0)
        )

        assertEquals(OwlV2GroundingDisposition.NEED_OBSERVATION, result.disposition)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun physicalCandidateChangeInvalidatesEvidenceFingerprint() {
        val first = process(expectedCount = 2)
        val changed = OwlV2GroundingPostprocessor.process(
            canonicalQuery = "an orange cat",
            sourceText = "橘猫",
            stateFingerprint = "state-1",
            imageWidth = 1080,
            imageHeight = 1440,
            boxesXyxy = boxes().also { it[8] += 1f },
            scores = scores(),
            labels = intArrayOf(0, 0, 0, 0),
            expectedCount = 2
        )

        assertNotEquals(first.evidenceFingerprint, changed.evidenceFingerprint)
    }

    @Test
    fun overlappingDifferentLabelsAreNotSuppressed() {
        val result = OwlV2GroundingPostprocessor.process(
            canonicalQuery = "cat or dog",
            sourceText = null,
            stateFingerprint = "state-1",
            imageWidth = 960,
            imageHeight = 960,
            boxesXyxy = floatArrayOf(
                100f, 100f, 300f, 300f,
                110f, 110f, 290f, 290f
            ),
            scores = floatArrayOf(0.9f, 0.8f),
            labels = intArrayOf(0, 1),
            expectedCount = 2
        )

        assertEquals(2, result.candidates.size)
        assertEquals(OwlV2GroundingDisposition.CANDIDATE_SET_READY, result.disposition)
    }

    @Test
    fun malformedTensorShapesAndEvidenceHashesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            OwlV2GroundingPostprocessor.process(
                "cat", null, "state", 10, 10,
                floatArrayOf(0f, 0f, 1f), floatArrayOf(0.5f), intArrayOf(0)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShadowTargetGroundingObservation("op", "cat", "asset", 1, 0, "not-a-sha")
        }
    }

    private fun process(expectedCount: Int): OwlV2GroundingResult =
        OwlV2GroundingPostprocessor.process(
            canonicalQuery = "an orange cat",
            sourceText = "橘猫",
            stateFingerprint = "state-1",
            imageWidth = 1080,
            imageHeight = 1440,
            boxesXyxy = boxes(),
            scores = scores(),
            labels = intArrayOf(0, 0, 0, 0),
            expectedCount = expectedCount
        )

    private fun boxes() = floatArrayOf(
        100f, 100f, 300f, 300f,
        110f, 110f, 290f, 290f,
        500f, 100f, 700f, 300f,
        50f, 50f, 80f, 80f
    )

    private fun scores() = floatArrayOf(0.9f, 0.8f, 0.7f, 0.05f)
}
