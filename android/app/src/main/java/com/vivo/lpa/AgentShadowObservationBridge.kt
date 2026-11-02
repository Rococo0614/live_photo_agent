package com.vivo.lpa

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Host-only language grounding result. Boxes, points and masks never cross this boundary. */
data class ShadowTargetGroundingObservation(
    val operationId: String,
    val semanticTarget: String,
    val sourceAssetId: String,
    val candidateCount: Int,
    val observedAtMs: Long,
    val locatorEvidenceFingerprint: String
) {
    init {
        require(operationId.isNotBlank())
        require(semanticTarget.isNotBlank())
        require(sourceAssetId.isNotBlank())
        require(candidateCount >= 0)
        require(observedAtMs >= 0)
        require(Regex("[0-9a-f]{64}").matches(locatorEvidenceFingerprint)) {
            "locator evidence fingerprint must be lowercase SHA-256"
        }
    }
}

sealed interface ShadowObjectTrackResult {
    /** No physical target is selected when language is non-unique. */
    data class NeedUser(
        val operationId: String,
        val semanticTarget: String,
        val candidateCount: Int
    ) : ShadowObjectTrackResult

    data class NeedObservation(
        val operationId: String,
        val semanticTarget: String,
        val reason: String
    ) : ShadowObjectTrackResult

    data class Materialized(val track: ShadowObjectTrackMaterialization) : ShadowObjectTrackResult
}

data class ShadowObjectTrackMaterialization(
    val snapshot: AgentContentGraphSnapshot,
    val operationId: String,
    val semanticTarget: String,
    val sourceAssetId: String,
    val objectTrackNodeId: String,
    val observedAtMs: Long,
    val groundingEvidenceFingerprint: String
)

/**
 * One internal grayscale mask observation. Pixel values are defensively copied and are never put
 * in Content Graph attributes, Goal IR or model-visible values.
 */
class ShadowMaskFrame private constructor(
    val frameIndex: Int,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val modelObjectPresent: Boolean,
    val candidateCount: Int,
    val protectedOverlapPixels: Int,
    maskValues: ByteArray
) {
    private val values = maskValues.copyOf()

    val rawMaskPixels: Int = values.count { (it.toInt() and 0xff) > 0 }

    init {
        require(frameIndex >= 0)
        require(timestampMs >= 0)
        require(width > 0 && height > 0)
        require(width.toLong() * height.toLong() == values.size.toLong())
        require(candidateCount >= 0)
        require(protectedOverlapPixels in 0..rawMaskPixels)
        require(modelObjectPresent || rawMaskPixels == 0) {
            "model-absent frames cannot carry a non-empty mask"
        }
    }

    fun copyMaskValues(): ByteArray = values.copyOf()

    companion object {
        fun create(
            frameIndex: Int,
            timestampMs: Long,
            width: Int,
            height: Int,
            maskValues: ByteArray,
            modelObjectPresent: Boolean = true,
            candidateCount: Int = 1,
            protectedOverlapPixels: Int = 0
        ): ShadowMaskFrame = ShadowMaskFrame(
            frameIndex,
            timestampMs,
            width,
            height,
            modelObjectPresent,
            candidateCount,
            protectedOverlapPixels,
            maskValues
        )
    }
}

class ShadowMaskTrackMaterialization internal constructor(
    val snapshot: AgentContentGraphSnapshot,
    val objectTrack: ShadowObjectTrackMaterialization,
    val maskTrackNodeId: String,
    val expectedFrameCount: Int,
    frames: List<ShadowMaskFrame>,
    val observations: List<ValidatedTrackObservation>,
    val validationPolicy: TrackValidationPolicy
) {
    private val framesByIndex = frames.associateBy { it.frameIndex }

    val observedFrameCount: Int get() = framesByIndex.size

    fun frame(frameIndex: Int): ShadowMaskFrame? = framesByIndex[frameIndex]

    fun frames(): List<ShadowMaskFrame> = framesByIndex.values.sortedBy { it.frameIndex }
}

data class AgentShadowObservationMaterialization(
    val goal: AgentGoalIr,
    val snapshot: AgentContentGraphSnapshot,
    val maskTrack: ShadowMaskTrackMaterialization,
    val certificate: AgentScopeCertificate,
    val compilerContext: AgentCompilerObservationContext
)

/**
 * Staged Android shadow bridge for OBJECT_TRACK -> MASK_TRACK -> SCOPE_CERTIFICATE.
 *
 * It only materializes host observations. It does not invoke a model, choose between candidates,
 * render media or register a mutation executor.
 */
object AgentShadowObservationBridge {
    private const val AUTHOR = "android-shadow-observation-bridge"

    fun materializeObjectTrack(
        snapshot: AgentContentGraphSnapshot,
        observation: ShadowTargetGroundingObservation
    ): ShadowObjectTrackResult {
        require(snapshot.validation.valid) { "content graph must be valid" }
        if (observation.sourceAssetId !in snapshot.graph.sourceAssetIds) {
            return ShadowObjectTrackResult.NeedObservation(
                observation.operationId,
                observation.semanticTarget,
                "source asset is not present in the current content graph"
            )
        }
        if (observation.candidateCount == 0) {
            return ShadowObjectTrackResult.NeedObservation(
                observation.operationId,
                observation.semanticTarget,
                "language target was not observed"
            )
        }
        if (observation.candidateCount > 1) {
            return ShadowObjectTrackResult.NeedUser(
                observation.operationId,
                observation.semanticTarget,
                observation.candidateCount
            )
        }

        val anchor = findSourceTrack(snapshot.graph, observation.sourceAssetId)
            ?: return ShadowObjectTrackResult.NeedObservation(
                observation.operationId,
                observation.semanticTarget,
                "source asset has no image or video track"
            )
        val groundingEvidenceFingerprint = fingerprint(
            snapshot.graph.graphId,
            snapshot.graph.version.toString(),
            snapshot.stateFingerprint,
            observation.operationId,
            observation.semanticTarget,
            observation.sourceAssetId,
            observation.candidateCount.toString(),
            observation.observedAtMs.toString(),
            observation.locatorEvidenceFingerprint
        )
        val objectNodeId = stableId(
            "object-track",
            snapshot.graph.graphId,
            observation.operationId,
            observation.sourceAssetId,
            observation.semanticTarget,
            observation.observedAtMs.toString(),
            observation.locatorEvidenceFingerprint,
            snapshot.stateFingerprint
        )
        val anchorEdgeId = stableId("object-anchor", objectNodeId, anchor.nodeId)
        val patch = ContentGraphPatch(
            patchId = stableId("patch-object", objectNodeId, snapshot.graph.version.toString()),
            baseVersion = snapshot.graph.version,
            mutations = listOf(
                ContentGraphMutation.AddNode(ContentNode(
                    nodeId = objectNodeId,
                    kind = ContentNodeKind.OBJECT_TRACK,
                    attributes = mapOf(
                        "semantic_target" to observation.semanticTarget,
                        "source_asset_id" to observation.sourceAssetId,
                        "candidate_count" to observation.candidateCount.toString(),
                        "observed_at_ms" to observation.observedAtMs.toString(),
                        "locator_evidence_fingerprint" to observation.locatorEvidenceFingerprint,
                        "grounding_evidence_fingerprint" to groundingEvidenceFingerprint
                    ),
                    provenance = AUTHOR
                )),
                ContentGraphMutation.AddEdge(ContentEdge(
                    edgeId = anchorEdgeId,
                    fromNodeId = objectNodeId,
                    toNodeId = anchor.nodeId,
                    kind = ContentEdgeKind.ANCHORED_TO
                ))
            ),
            author = AUTHOR,
            reason = "unique language target materialized as an object track"
        )
        val graph = AgentContentGraphReducer.apply(snapshot.graph, patch)
        val updated = AgentContentGraphSnapshot(
            graph,
            AgentContentGraphValidator.validate(graph),
            snapshot.stateFingerprint
        )
        return ShadowObjectTrackResult.Materialized(ShadowObjectTrackMaterialization(
            snapshot = updated,
            operationId = observation.operationId,
            semanticTarget = observation.semanticTarget,
            sourceAssetId = observation.sourceAssetId,
            objectTrackNodeId = objectNodeId,
            observedAtMs = observation.observedAtMs,
            groundingEvidenceFingerprint = groundingEvidenceFingerprint
        ))
    }

    fun materializeMaskTrack(
        objectTrack: ShadowObjectTrackMaterialization,
        expectedFrameCount: Int,
        frames: List<ShadowMaskFrame>,
        validationPolicy: TrackValidationPolicy
    ): ShadowMaskTrackMaterialization {
        require(expectedFrameCount > 0)
        require(!validationPolicy.approvedForMutation) {
            "shadow observation bridge cannot use a mutation-approved policy"
        }
        require(frames.map { it.frameIndex }.distinct().size == frames.size) {
            "mask frame indices must be unique"
        }
        require(frames.all { it.frameIndex in 0 until expectedFrameCount })
        val sorted = frames.sortedBy { it.frameIndex }
        require(sorted.zipWithNext().all { (left, right) -> left.timestampMs <= right.timestampMs }) {
            "mask timestamps must be monotonic"
        }
        sorted.firstOrNull()?.let { first ->
            require(sorted.all { it.width == first.width && it.height == first.height }) {
                "all mask frames must have the same dimensions"
            }
        }

        var lastVisiblePixels: Int? = null
        val observations = sorted.map { frame ->
            TrackObservationValidator.validate(
                TrackObservationInput(
                    frameIndex = frame.frameIndex,
                    timestampMs = frame.timestampMs,
                    modelObjectPresent = frame.modelObjectPresent,
                    rawMaskPixels = frame.rawMaskPixels,
                    candidateCount = frame.candidateCount,
                    protectedOverlapPixels = frame.protectedOverlapPixels
                ),
                lastVisiblePixels,
                validationPolicy
            ).also { validated ->
                if (validated.status == TrackObservationStatus.VISIBLE_VALIDATED) {
                    lastVisiblePixels = validated.rawMaskPixels
                }
            }
        }

        val graph = objectTrack.snapshot.graph
        require(graph.nodes[objectTrack.objectTrackNodeId]?.kind == ContentNodeKind.OBJECT_TRACK)
        val maskNodeId = stableId(
            "mask-track",
            graph.graphId,
            objectTrack.operationId,
            objectTrack.objectTrackNodeId
        )
        val maskEdgeId = stableId("mask-edge", maskNodeId, objectTrack.objectTrackNodeId)
        val dimensions = sorted.firstOrNull()?.let { "${it.width}x${it.height}" } ?: "unobserved"
        val patch = ContentGraphPatch(
            patchId = stableId("patch-mask", maskNodeId, graph.version.toString()),
            baseVersion = graph.version,
            mutations = listOf(
                ContentGraphMutation.AddNode(ContentNode(
                    nodeId = maskNodeId,
                    kind = ContentNodeKind.MASK_TRACK,
                    attributes = mapOf(
                        "object_track_node_id" to objectTrack.objectTrackNodeId,
                        "expected_frame_count" to expectedFrameCount.toString(),
                        "observed_frame_count" to sorted.size.toString(),
                        "dimensions" to dimensions,
                        "validation_policy_id" to validationPolicy.policyId
                    ),
                    provenance = AUTHOR
                )),
                ContentGraphMutation.AddEdge(ContentEdge(
                    edgeId = maskEdgeId,
                    fromNodeId = maskNodeId,
                    toNodeId = objectTrack.objectTrackNodeId,
                    kind = ContentEdgeKind.MASKS
                ))
            ),
            author = AUTHOR,
            reason = "internal mask observations materialized as a typed track"
        )
        val updatedGraph = AgentContentGraphReducer.apply(graph, patch)
        val updated = AgentContentGraphSnapshot(
            updatedGraph,
            AgentContentGraphValidator.validate(updatedGraph),
            objectTrack.snapshot.stateFingerprint
        )
        return ShadowMaskTrackMaterialization(
            snapshot = updated,
            objectTrack = objectTrack,
            maskTrackNodeId = maskNodeId,
            expectedFrameCount = expectedFrameCount,
            frames = sorted,
            observations = observations,
            validationPolicy = validationPolicy
        )
    }

    fun finalizeForCompiler(
        goal: AgentGoalIr,
        maskTrack: ShadowMaskTrackMaterialization
    ): AgentShadowObservationMaterialization {
        val objectTrack = maskTrack.objectTrack
        require(goal.stateFingerprint == maskTrack.snapshot.stateFingerprint) {
            "goal and observation state fingerprints differ"
        }
        val operation = requireNotNull(goal.operations.firstOrNull {
            it.operationId == objectTrack.operationId
        }) { "goal is missing operation ${objectTrack.operationId}" }
        require(operation.kind in setOf(
            AgentGoalOperationKind.CHANGE_CONTENT,
            AgentGoalOperationKind.REMOVE_CONTENT
        )) { "shadow scope certificate requires a local content operation" }
        val semantic = operation.target as? AgentGoalTarget.Semantic
            ?: throw IllegalArgumentException("shadow bridge only grounds semantic targets")
        require(semantic.description == objectTrack.semanticTarget) {
            "grounding observation does not match the model-authored semantic target"
        }

        val uniqueTarget = maskTrack.frames().all { it.candidateCount == 1 }
        val protectionChecksPassed = maskTrack.frames().all { it.protectedOverlapPixels == 0 }
        val certificateId = stableId(
            "scope",
            maskTrack.snapshot.graph.graphId,
            maskTrack.snapshot.graph.version.toString(),
            objectTrack.objectTrackNodeId,
            maskTrack.maskTrackNodeId,
            objectTrack.groundingEvidenceFingerprint,
            goal.stateFingerprint
        )
        val certificate = AgentScopeCertificate(
            certificateId = certificateId,
            contentGraphId = maskTrack.snapshot.graph.graphId,
            contentGraphVersion = maskTrack.snapshot.graph.version,
            objectTrackNodeId = objectTrack.objectTrackNodeId,
            maskTrackNodeId = maskTrack.maskTrackNodeId,
            stateFingerprint = goal.stateFingerprint,
            expectedFrameCount = maskTrack.expectedFrameCount,
            observations = maskTrack.observations,
            uniqueTarget = uniqueTarget,
            protectionChecksPassed = protectionChecksPassed,
            validationPolicy = maskTrack.validationPolicy
        )
        val grounded = goal.copy(operations = goal.operations.map { candidate ->
            if (candidate.operationId == operation.operationId) {
                candidate.copy(target = AgentGoalTarget.GroundedContentNode(
                    maskTrack.snapshot.graph.graphId,
                    maskTrack.snapshot.graph.version,
                    objectTrack.objectTrackNodeId
                ))
            } else {
                candidate
            }
        })
        return AgentShadowObservationMaterialization(
            goal = grounded,
            snapshot = maskTrack.snapshot,
            maskTrack = maskTrack,
            certificate = certificate,
            compilerContext = AgentCompilerObservationContext(
                scopeCertificates = mapOf(operation.operationId to certificate),
                sourceAssetIds = mapOf(operation.operationId to objectTrack.sourceAssetId)
            )
        )
    }

    private fun findSourceTrack(graph: AgentContentGraph, sourceAssetId: String): ContentNode? {
        val sourceNode = graph.nodes.values.firstOrNull {
            it.attributes["asset_id"] == sourceAssetId &&
                it.kind in setOf(ContentNodeKind.MEDIA_ASSET, ContentNodeKind.LIVE_ASSET)
        } ?: return null
        val childIds = graph.edges.values.asSequence()
            .filter { edge ->
                edge.kind == ContentEdgeKind.CONTAINS && edge.fromNodeId == sourceNode.nodeId
            }
            .map { it.toNodeId }
            .toSet()
        return graph.nodes.values
            .filter { it.nodeId in childIds }
            .sortedBy { if (it.kind == ContentNodeKind.VIDEO_TRACK) 0 else 1 }
            .firstOrNull { it.kind in setOf(ContentNodeKind.VIDEO_TRACK, ContentNodeKind.IMAGE_TRACK) }
    }

    private fun stableId(prefix: String, vararg facts: String): String {
        val digest = fingerprint(*facts)
            .take(24)
        return "$prefix:$digest"
    }

    private fun fingerprint(vararg facts: String): String {
        val canonical = facts.joinToString(separator = "") { fact ->
            val bytes = fact.toByteArray(StandardCharsets.UTF_8)
            "${bytes.size}:$fact;"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
