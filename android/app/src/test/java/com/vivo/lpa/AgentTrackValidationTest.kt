package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTrackValidationTest {
    private val candidatePolicy = TrackValidationPolicy(
        policyId = "temporal-area-v1-candidate",
        minimumVisibleAreaRatio = 0.10
    )

    @Test
    fun topologySelectorOnlyReturnsDeviceValidatedCardinalities() {
        assertEquals(EdgeTamAttentionTopology(1, 1), EdgeTamAttentionTopologySelector.select(1, 1))
        assertEquals(EdgeTamAttentionTopology(7, 16), EdgeTamAttentionTopologySelector.select(7, 16))
        assertNull(EdgeTamAttentionTopologySelector.select(6, 7))
        assertNull(EdgeTamAttentionTopologySelector.select(7, 17))
        assertNull(EdgeTamAttentionTopologySelector.select(0, 0))
    }

    @Test
    fun temporalAreaDropFailsClosedWithoutChangingSemanticTarget() {
        val result = TrackObservationValidator.validate(
            TrackObservationInput(
                frameIndex = 3,
                timestampMs = 3_000,
                modelObjectPresent = true,
                rawMaskPixels = 2_500,
                candidateCount = 1
            ),
            lastValidatedVisiblePixels = 50_000,
            policy = candidatePolicy
        )

        assertEquals(TrackObservationStatus.UNCERTAIN_TEMPORAL_COVERAGE, result.status)
        assertEquals(0.05, result.visibleAreaRatio!!, 1e-9)
        assertFalse(result.temporallyCovered)
    }

    @Test
    fun explicitAbsenceIsCoveredButProtectionAndUniquenessFailuresAreNot() {
        val absent = TrackObservationValidator.validate(
            TrackObservationInput(1, 1_000, false, 900, 1),
            lastValidatedVisiblePixels = 20_000,
            policy = candidatePolicy
        )
        val protected = TrackObservationValidator.validate(
            TrackObservationInput(1, 1_000, true, 20_000, 1, protectedOverlapPixels = 1),
            lastValidatedVisiblePixels = 20_000,
            policy = candidatePolicy
        )
        val ambiguous = TrackObservationValidator.validate(
            TrackObservationInput(1, 1_000, true, 20_000, 2),
            lastValidatedVisiblePixels = 20_000,
            policy = candidatePolicy
        )

        assertEquals(TrackObservationStatus.ABSENT_MODEL, absent.status)
        assertTrue(absent.temporallyCovered)
        assertEquals(TrackObservationStatus.REJECTED_PROTECTED_OVERLAP, protected.status)
        assertEquals(TrackObservationStatus.REJECTED_NON_UNIQUE, ambiguous.status)
        assertFalse(protected.temporallyCovered)
        assertFalse(ambiguous.temporallyCovered)
    }

    @Test
    fun scopeCertificateRequiresApprovedPolicyCompleteCoverageAndFreshState() {
        val observations = listOf(
            ValidatedTrackObservation(0, 0, TrackObservationStatus.VISIBLE_VALIDATED, 40_000, null),
            ValidatedTrackObservation(1, 1_000, TrackObservationStatus.ABSENT_MODEL, 0, 0.0)
        )
        val candidate = certificate(observations, candidatePolicy)
        val approved = certificate(
            observations,
            candidatePolicy.copy(approvedForMutation = true)
        )

        assertTrue(candidate.temporalCoverageComplete)
        assertTrue(candidate.readyForPreview("state-current"))
        assertFalse(candidate.readyForMutation("state-current"))
        assertTrue(approved.readyForMutation("state-current"))
        assertFalse(approved.readyForMutation("state-stale"))
        assertNotEquals(candidate.evidenceFingerprint, candidate.copy(uniqueTarget = false).evidenceFingerprint)
        assertFalse(
            certificate(
                observations.dropLast(1),
                candidatePolicy.copy(approvedForMutation = true)
            ).readyForMutation("state-current")
        )
    }

    @Test
    fun certificateFingerprintUsesUnambiguousLengthPrefixedFacts() {
        val observations = listOf(
            ValidatedTrackObservation(0, 0, TrackObservationStatus.VISIBLE_VALIDATED, 1, null),
            ValidatedTrackObservation(1, 1, TrackObservationStatus.ABSENT_MODEL, 0, 0.0)
        )
        val base = certificate(observations, candidatePolicy)
        val left = base.copy(certificateId = "scope|content", contentGraphId = "graph")
        val right = base.copy(certificateId = "scope", contentGraphId = "content|graph")

        assertNotEquals(left.evidenceFingerprint, right.evidenceFingerprint)
    }

    @Test
    fun completeAbsenceIsCoveredButCannotProduceAVisualPreview() {
        val absent = listOf(
            ValidatedTrackObservation(0, 0, TrackObservationStatus.ABSENT_MODEL, 0, null),
            ValidatedTrackObservation(1, 1, TrackObservationStatus.ABSENT_MODEL, 0, null)
        )
        val certificate = certificate(absent, candidatePolicy)

        assertTrue(certificate.temporalCoverageComplete)
        assertFalse(certificate.hasVisibleFrame)
        assertFalse(certificate.readyForPreview("state-current"))
    }

    private fun certificate(
        observations: List<ValidatedTrackObservation>,
        policy: TrackValidationPolicy
    ) = AgentScopeCertificate(
        certificateId = "scope-1",
        contentGraphId = "content-1",
        contentGraphVersion = 2,
        objectTrackNodeId = "object-1",
        maskTrackNodeId = "mask-1",
        stateFingerprint = "state-current",
        expectedFrameCount = 2,
        observations = observations,
        uniqueTarget = true,
        protectionChecksPassed = true,
        validationPolicy = policy
    )
}
