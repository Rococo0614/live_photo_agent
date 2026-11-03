package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentShadowObservationBridgeTest {
    @Test
    fun uniqueLanguageTargetMaterializesTypedTracksAndPreviewCertificate() {
        val fixture = fixture()
        val objectResult = AgentShadowObservationBridge.materializeObjectTrack(
            fixture.snapshot,
            ShadowTargetGroundingObservation(
                operationId = "change-1",
                semanticTarget = "右后方的猫",
                sourceAssetId = "asset-1",
                candidateCount = 1,
                observedAtMs = 100,
                locatorEvidenceFingerprint = locatorFingerprint
            )
        )
        assertTrue(objectResult is ShadowObjectTrackResult.Materialized)
        val objectTrack = (objectResult as ShadowObjectTrackResult.Materialized).track
        val sourceMask = byteArrayOf(0, 0xff.toByte(), 128.toByte(), 0, 0, 0)
        val visible = ShadowMaskFrame.create(0, 0, 3, 2, sourceMask)
        sourceMask[1] = 0
        val absent = ShadowMaskFrame.create(
            frameIndex = 1,
            timestampMs = 33,
            width = 3,
            height = 2,
            maskValues = ByteArray(6),
            modelObjectPresent = false
        )
        val maskTrack = AgentShadowObservationBridge.materializeMaskTrack(
            objectTrack,
            expectedFrameCount = 2,
            frames = listOf(visible, absent),
            validationPolicy = TrackValidationPolicy("candidate-v1", 0.10)
        )
        val materialized = AgentShadowObservationBridge.finalizeForCompiler(
            fixture.goal,
            maskTrack
        )

        assertEquals(2, visible.rawMaskPixels)
        assertEquals(0xff, visible.copyMaskValues()[1].toInt() and 0xff)
        assertEquals(ContentNodeKind.OBJECT_TRACK,
            materialized.snapshot.graph.nodes[objectTrack.objectTrackNodeId]?.kind)
        assertEquals(
            objectTrack.groundingEvidenceFingerprint,
            materialized.snapshot.graph.nodes[objectTrack.objectTrackNodeId]
                ?.attributes?.get("grounding_evidence_fingerprint")
        )
        assertEquals(
            locatorFingerprint,
            materialized.snapshot.graph.nodes[objectTrack.objectTrackNodeId]
                ?.attributes?.get("locator_evidence_fingerprint")
        )
        assertEquals(ContentNodeKind.MASK_TRACK,
            materialized.snapshot.graph.nodes[maskTrack.maskTrackNodeId]?.kind)
        assertTrue(materialized.snapshot.graph.edges.values.any {
            it.kind == ContentEdgeKind.ANCHORED_TO &&
                it.fromNodeId == objectTrack.objectTrackNodeId
        })
        assertTrue(materialized.snapshot.graph.edges.values.any {
            it.kind == ContentEdgeKind.MASKS && it.fromNodeId == maskTrack.maskTrackNodeId
        })
        val exposedAttributeNames = materialized.snapshot.graph.nodes.values
            .flatMap { it.attributes.keys }
            .map(String::lowercase)
        assertTrue(exposedAttributeNames.none {
            it.contains("path") || it.contains("box") || it.contains("point") || it == "mask"
        })
        assertTrue(materialized.certificate.readyForPreview(fixture.fingerprint))
        assertFalse(materialized.certificate.readyForMutation(fixture.fingerprint))
        assertEquals(
            listOf(TrackObservationStatus.VISIBLE_VALIDATED, TrackObservationStatus.ABSENT_MODEL),
            materialized.certificate.observations.map { it.status }
        )
        val grounded = materialized.goal.operations.single().target as
            AgentGoalTarget.GroundedContentNode
        assertEquals(materialized.snapshot.graph.version, grounded.contentGraphVersion)
        assertEquals(objectTrack.objectTrackNodeId, grounded.nodeId)

        val preflight = AgentGoalCompiler.preflight(
            materialized.goal,
            fixture.state,
            materialized.snapshot,
            materialized.compilerContext
        )
        assertTrue(preflight is AgentCompileResult.Ready)
        val compilation = (preflight as AgentCompileResult.Ready).compilation
        assertEquals(LocalDeterministicChangePreviewAction.ACTION_ID,
            compilation.graph.nodes.values.single().actionId)
        val certificateBinding = compilation.graph.externalBindings
            .map { it.value }
            .filterIsInstance<AgentDagExternalValue.ScopeCertificate>()
            .single()
        assertEquals(materialized.certificate.evidenceFingerprint,
            certificateBinding.certificateFingerprint)
    }

    @Test
    fun nonUniqueLanguageTargetReturnsNeedUserWithoutSelectingPhysicalCandidate() {
        val fixture = fixture()

        val result = AgentShadowObservationBridge.materializeObjectTrack(
            fixture.snapshot,
            ShadowTargetGroundingObservation(
                "change-1", "猫", "asset-1", candidateCount = 3, observedAtMs = 100,
                locatorEvidenceFingerprint = locatorFingerprint
            )
        )

        assertTrue(result is ShadowObjectTrackResult.NeedUser)
        result as ShadowObjectTrackResult.NeedUser
        assertEquals("猫", result.semanticTarget)
        assertEquals(3, result.candidateCount)
        assertTrue(fixture.snapshot.graph.nodes.values.none {
            it.kind == ContentNodeKind.OBJECT_TRACK
        })
    }

    @Test
    fun missingLanguageTargetRequestsMoreObservation() {
        val fixture = fixture()

        val result = AgentShadowObservationBridge.materializeObjectTrack(
            fixture.snapshot,
            ShadowTargetGroundingObservation(
                "change-1", "右后方的猫", "asset-1", candidateCount = 0,
                observedAtMs = 100, locatorEvidenceFingerprint = locatorFingerprint
            )
        )

        assertTrue(result is ShadowObjectTrackResult.NeedObservation)
    }

    @Test
    fun changedLocatorEvidenceInvalidatesPhysicalObjectBinding() {
        val fixture = fixture()
        val first = uniqueObject(fixture)
        val changed = (AgentShadowObservationBridge.materializeObjectTrack(
            fixture.snapshot,
            ShadowTargetGroundingObservation(
                "change-1", "右后方的猫", "asset-1", 1, 100, "22".repeat(32)
            )
        ) as ShadowObjectTrackResult.Materialized).track

        assertNotEquals(first.objectTrackNodeId, changed.objectTrackNodeId)
        assertNotEquals(
            first.groundingEvidenceFingerprint,
            changed.groundingEvidenceFingerprint
        )
    }

    @Test
    fun incompleteTemporalCoverageCannotMakePreviewReady() {
        val fixture = fixture()
        val objectTrack = uniqueObject(fixture)
        val maskTrack = AgentShadowObservationBridge.materializeMaskTrack(
            objectTrack,
            expectedFrameCount = 2,
            frames = listOf(ShadowMaskFrame.create(
                0, 0, 2, 2, byteArrayOf(0, 0xff.toByte(), 0, 0)
            )),
            validationPolicy = TrackValidationPolicy("candidate-v1", 0.10)
        )
        val materialized = AgentShadowObservationBridge.finalizeForCompiler(
            fixture.goal,
            maskTrack
        )

        assertFalse(materialized.certificate.temporalCoverageComplete)
        assertFalse(materialized.certificate.readyForPreview(fixture.fingerprint))
        val preflight = AgentGoalCompiler.preflight(
            materialized.goal,
            fixture.state,
            materialized.snapshot,
            materialized.compilerContext
        )
        assertTrue(preflight is AgentCompileResult.NeedObservation)
        assertEquals(
            AgentDataKind.SCOPE_CERTIFICATE,
            (preflight as AgentCompileResult.NeedObservation).requirements.single().kind
        )
    }

    @Test
    fun suddenAreaDropAndProtectedOverlapRemainFailClosed() {
        val fixture = fixture()
        val objectTrack = uniqueObject(fixture)
        val frames = listOf(
            ShadowMaskFrame.create(0, 0, 10, 10, ByteArray(100) { 0xff.toByte() }),
            ShadowMaskFrame.create(
                1,
                33,
                10,
                10,
                ByteArray(100) { if (it < 9) 0xff.toByte() else 0 }
            ),
            ShadowMaskFrame.create(
                2,
                66,
                10,
                10,
                ByteArray(100) { if (it < 20) 0xff.toByte() else 0 },
                protectedOverlapPixels = 1
            )
        )
        val maskTrack = AgentShadowObservationBridge.materializeMaskTrack(
            objectTrack,
            3,
            frames,
            TrackValidationPolicy("candidate-v1", 0.10)
        )
        val materialized = AgentShadowObservationBridge.finalizeForCompiler(
            fixture.goal,
            maskTrack
        )

        assertEquals(
            listOf(
                TrackObservationStatus.VISIBLE_VALIDATED,
                TrackObservationStatus.UNCERTAIN_TEMPORAL_COVERAGE,
                TrackObservationStatus.REJECTED_PROTECTED_OVERLAP
            ),
            materialized.certificate.observations.map { it.status }
        )
        assertFalse(materialized.certificate.protectionChecksPassed)
        assertFalse(materialized.certificate.readyForPreview(fixture.fingerprint))
    }

    @Test
    fun shadowBridgeRejectsMutationApprovedPolicyAndTargetSubstitution() {
        val fixture = fixture()
        val objectTrack = uniqueObject(fixture)
        assertThrows(IllegalArgumentException::class.java) {
            AgentShadowObservationBridge.materializeMaskTrack(
                objectTrack,
                1,
                listOf(ShadowMaskFrame.create(
                    0, 0, 1, 1, byteArrayOf(0xff.toByte())
                )),
                TrackValidationPolicy("mutation-policy", 0.10, approvedForMutation = true)
            )
        }

        val maskTrack = AgentShadowObservationBridge.materializeMaskTrack(
            objectTrack,
            1,
            listOf(ShadowMaskFrame.create(0, 0, 1, 1, byteArrayOf(0xff.toByte()))),
            TrackValidationPolicy("candidate-v1", 0.10)
        )
        val substitutedGoal = fixture.goal.copy(operations = listOf(
            fixture.goal.operations.single().copy(
                target = AgentGoalTarget.Semantic("另一只猫")
            )
        ))
        assertThrows(IllegalArgumentException::class.java) {
            AgentShadowObservationBridge.finalizeForCompiler(substitutedGoal, maskTrack)
        }
    }

    @Test
    fun binaryPackedFramePreservesPixelsWithoutFullByteStorageContractChange() {
        val source = byteArrayOf(0, 0xff.toByte(), 0xff.toByte(), 0, 0, 0xff.toByte(), 0, 0)

        val frame = ShadowMaskFrame.createBinaryPacked(0, 0, 4, 2, source)

        assertEquals(3, frame.rawMaskPixels)
        assertTrue(source.contentEquals(frame.copyMaskValues()))
        source.fill(0)
        assertEquals(3, frame.rawMaskPixels)
    }

    @Test
    fun equalAreaDifferentShapeMasksCannotReuseScopeCertificate() {
        val fixture = fixture()
        val objectTrack = uniqueObject(fixture)
        fun materialize(mask: ByteArray): AgentShadowObservationMaterialization {
            val track = AgentShadowObservationBridge.materializeMaskTrack(
                objectTrack,
                1,
                listOf(ShadowMaskFrame.createBinaryPacked(0, 0, 2, 1, mask)),
                TrackValidationPolicy("shape-bound-v1", 0.10)
            )
            return AgentShadowObservationBridge.finalizeForCompiler(fixture.goal, track)
        }

        val left = materialize(byteArrayOf(0xff.toByte(), 0))
        val right = materialize(byteArrayOf(0, 0xff.toByte()))

        assertNotEquals(left.maskTrack.maskTrackNodeId, right.maskTrack.maskTrackNodeId)
        assertNotEquals(left.certificate.certificateId, right.certificate.certificateId)
        assertNotEquals(left.certificate.evidenceFingerprint, right.certificate.evidenceFingerprint)
    }

    @Test
    fun hostObservedCoverFrameIsPreservedAsPreferredPreview() {
        val fixture = fixture()
        val objectTrack = uniqueObject(fixture)
        val track = AgentShadowObservationBridge.materializeMaskTrack(
            objectTrack,
            expectedFrameCount = 2,
            frames = listOf(
                ShadowMaskFrame.createBinaryPacked(0, 0, 1, 1, byteArrayOf(0xff.toByte())),
                ShadowMaskFrame.createBinaryPacked(1, 33, 1, 1, byteArrayOf(0xff.toByte()))
            ),
            validationPolicy = TrackValidationPolicy("preferred-preview-v1", 0.10),
            preferredPreviewFrameIndex = 1
        )

        assertEquals(1, track.preferredPreviewFrameIndex)
        assertEquals(
            "1",
            track.snapshot.graph.nodes[track.maskTrackNodeId]
                ?.attributes?.get("preferred_preview_frame_index")
        )
    }

    private data class Fixture(
        val state: AgentRuntimeState,
        val fingerprint: String,
        val snapshot: AgentContentGraphSnapshot,
        val goal: AgentGoalIr
    )

    private fun fixture(): Fixture {
        val state = AgentRuntimeState(null, "turn-shadow-bridge", "把右后方的猫调成绿色")
        val fingerprint = AgentStateFingerprint.of(state)
        val graph = AgentContentGraphFactory.fromDescriptors(
            graphId = "content-shadow-bridge",
            version = 2,
            descriptors = listOf(ContentAssetDescriptor(
                assetId = "asset-1",
                mediaFormat = "livephoto",
                hasImageTrack = true,
                hasVideoTrack = true
            ))
        )
        val snapshot = AgentContentGraphSnapshot(
            graph,
            AgentContentGraphValidator.validate(graph),
            fingerprint
        )
        val operation = AgentGoalOperation(
            operationId = "change-1",
            kind = AgentGoalOperationKind.CHANGE_CONTENT,
            scope = AgentGoalScope.CurrentAssets,
            target = AgentGoalTarget.Semantic("右后方的猫"),
            parameters = mapOf(
                "effect_kind" to AgentGoalArgument(
                    AgentGoalValue.Text("color_tint"), AgentValueProvenance.MODEL_INFERRED
                ),
                "color_hex" to AgentGoalArgument(
                    AgentGoalValue.Text("#22AA66"), AgentValueProvenance.MODEL_INFERRED
                ),
                "intensity" to AgentGoalArgument(
                    AgentGoalValue.Number(0.65), AgentValueProvenance.MODEL_INFERRED
                )
            )
        )
        return Fixture(
            state,
            fingerprint,
            snapshot,
            AgentGoalIr(
                goalId = "goal-shadow-bridge",
                userGoal = state.originalQuery,
                stateFingerprint = fingerprint,
                operations = listOf(operation)
            )
        )
    }

    private fun uniqueObject(fixture: Fixture): ShadowObjectTrackMaterialization =
        (AgentShadowObservationBridge.materializeObjectTrack(
            fixture.snapshot,
            ShadowTargetGroundingObservation(
                "change-1", "右后方的猫", "asset-1", 1, 100, locatorFingerprint
            )
        ) as ShadowObjectTrackResult.Materialized).track

    private val locatorFingerprint = "11".repeat(32)
}
