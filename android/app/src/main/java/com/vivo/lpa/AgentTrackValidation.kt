package com.vivo.lpa

/**
 * Internal EdgeTAM attention topology selected from deterministic runtime facts.
 *
 * This is deliberately not part of Goal IR. Qwen chooses the semantic target; the host selects an
 * equivalent validated graph from observed state cardinality.
 */
data class EdgeTamAttentionTopology(
    val spatialMemories: Int,
    val objectPointers: Int
) {
    init {
        require(spatialMemories in 1..MAX_SPATIAL_MEMORIES)
        require(objectPointers in 1..MAX_OBJECT_POINTERS)
    }

    companion object {
        const val MAX_SPATIAL_MEMORIES = 7
        const val MAX_OBJECT_POINTERS = 16
    }
}

/** No fallback is returned for a topology that has not passed device validation. */
object EdgeTamAttentionTopologySelector {
    fun select(spatialMemories: Int, objectPointers: Int): EdgeTamAttentionTopology? {
        val startupTopology = spatialMemories in 1..7 && objectPointers == spatialMemories
        val longSequenceTopology = spatialMemories == 7 && objectPointers in 8..16
        return if (startupTopology || longSequenceTopology) {
            EdgeTamAttentionTopology(spatialMemories, objectPointers)
        } else {
            null
        }
    }
}

enum class TrackObservationStatus {
    VISIBLE_VALIDATED,
    ABSENT_MODEL,
    UNCERTAIN_TEMPORAL_COVERAGE,
    REJECTED_NON_UNIQUE,
    REJECTED_PROTECTED_OVERLAP
}

/** Candidate policies cannot authorize mutation until separately approved. */
data class TrackValidationPolicy(
    val policyId: String,
    val minimumVisibleAreaRatio: Double,
    val approvedForMutation: Boolean = false
) {
    init {
        require(policyId.isNotBlank())
        require(minimumVisibleAreaRatio in 0.0..1.0)
    }
}

data class TrackObservationInput(
    val frameIndex: Int,
    val timestampMs: Long,
    val modelObjectPresent: Boolean,
    val rawMaskPixels: Int,
    val candidateCount: Int,
    val protectedOverlapPixels: Int = 0
) {
    init {
        require(frameIndex >= 0)
        require(timestampMs >= 0)
        require(rawMaskPixels >= 0)
        require(candidateCount >= 0)
        require(protectedOverlapPixels >= 0)
    }
}

data class ValidatedTrackObservation(
    val frameIndex: Int,
    val timestampMs: Long,
    val status: TrackObservationStatus,
    val rawMaskPixels: Int,
    val visibleAreaRatio: Double?
) {
    val temporallyCovered: Boolean
        get() = status == TrackObservationStatus.VISIBLE_VALIDATED ||
            status == TrackObservationStatus.ABSENT_MODEL
}

object TrackObservationValidator {
    fun validate(
        input: TrackObservationInput,
        lastValidatedVisiblePixels: Int?,
        policy: TrackValidationPolicy
    ): ValidatedTrackObservation {
        require(lastValidatedVisiblePixels == null || lastValidatedVisiblePixels > 0)
        val areaRatio = lastValidatedVisiblePixels?.let { input.rawMaskPixels.toDouble() / it }
        val status = when {
            input.candidateCount != 1 -> TrackObservationStatus.REJECTED_NON_UNIQUE
            input.protectedOverlapPixels > 0 -> TrackObservationStatus.REJECTED_PROTECTED_OVERLAP
            !input.modelObjectPresent -> TrackObservationStatus.ABSENT_MODEL
            input.rawMaskPixels == 0 -> TrackObservationStatus.UNCERTAIN_TEMPORAL_COVERAGE
            areaRatio != null && areaRatio < policy.minimumVisibleAreaRatio ->
                TrackObservationStatus.UNCERTAIN_TEMPORAL_COVERAGE
            else -> TrackObservationStatus.VISIBLE_VALIDATED
        }
        return ValidatedTrackObservation(
            frameIndex = input.frameIndex,
            timestampMs = input.timestampMs,
            status = status,
            rawMaskPixels = input.rawMaskPixels,
            visibleAreaRatio = areaRatio
        )
    }
}

/**
 * Host-issued proof that a grounded target is safe to hand to a mutation node.
 *
 * Explicit model absence is temporal coverage. Uncertainty, non-uniqueness, protected overlap, a
 * stale fingerprint, or an unapproved validation policy keeps [readyForMutation] false.
 */
data class AgentScopeCertificate(
    val certificateId: String,
    val contentGraphId: String,
    val contentGraphVersion: Int,
    val objectTrackNodeId: String,
    val maskTrackNodeId: String,
    val stateFingerprint: String,
    val expectedFrameCount: Int,
    val observations: List<ValidatedTrackObservation>,
    val uniqueTarget: Boolean,
    val protectionChecksPassed: Boolean,
    val validationPolicy: TrackValidationPolicy
) {
    init {
        require(certificateId.isNotBlank())
        require(contentGraphId.isNotBlank())
        require(contentGraphVersion >= 0)
        require(objectTrackNodeId.isNotBlank())
        require(maskTrackNodeId.isNotBlank())
        require(stateFingerprint.isNotBlank())
        require(expectedFrameCount > 0)
        require(observations.map { it.frameIndex }.distinct().size == observations.size)
        require(observations.all { it.frameIndex in 0 until expectedFrameCount })
    }

    val temporalCoverageComplete: Boolean
        get() = observations.size == expectedFrameCount && observations.all { it.temporallyCovered }

    fun readyForMutation(currentStateFingerprint: String): Boolean =
        stateFingerprint == currentStateFingerprint &&
            uniqueTarget &&
            protectionChecksPassed &&
            validationPolicy.approvedForMutation &&
            temporalCoverageComplete
}
