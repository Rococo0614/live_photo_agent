package com.vivo.lpa

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGoalCompilerTest {
    private fun parse(query: String, arguments: JSONObject, state: AgentRuntimeState): AgentGoalIr =
        requireNotNull(AgentGoalIrCodec.parseArguments(arguments, query, state).goal)

    @Test
    fun retrieveGoalRequestsBindingFromStateFilteredCatalog() {
        val query = "找一段猫的视频"
        val state = AgentRuntimeState(null, "turn-binding", query)
        val goal = parse(query, JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "retrieve")
            .put("instruction", "猫"))), state)

        val result = AgentGoalCompiler.preflight(goal, state)

        assertTrue(result is AgentCompileResult.NeedBinding)
        val request = (result as AgentCompileResult.NeedBinding).requests.single()
        assertEquals(ToolFamily.RETRIEVE, request.family)
        assertEquals(listOf("search_assets"), request.candidateActionIds)
        assertTrue(request.candidateActionIds.size <= MobileToolRegistry.MAX_VISIBLE_TOOLS)
    }

    @Test
    fun missingTargetSuspendsForUser() {
        val query = "移除一个东西"
        val state = AgentRuntimeState(null, "turn-question", query)
        val goal = parse(query, JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "remove_content"))), state)

        val result = AgentGoalCompiler.preflight(goal, state)

        assertTrue(result is AgentCompileResult.NeedUser)
        val question = (result as AgentCompileResult.NeedUser).question
        assertEquals("target", question.missingSlot)
        assertTrue(question.continuationToken.isNotBlank())
    }

    @Test
    fun semanticContentTargetRequestsGroundingObservation() {
        val query = "移除右后方的人"
        val state = AgentRuntimeState(null, "turn-observe", query)
        val goal = parse(query, JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "remove_content")
            .put("target", "右后方的人"))), state)

        val result = AgentGoalCompiler.preflight(goal, state)

        assertTrue(result is AgentCompileResult.NeedObservation)
        val requirement = (result as AgentCompileResult.NeedObservation).requirements.single()
        assertEquals(AgentDataKind.OBJECT_TRACK, requirement.kind)
    }

    @Test
    fun retrievalBindsBeforeLaterContentGrounding() {
        val query = "找一段街景视频并移除右后方的人"
        val state = AgentRuntimeState(null, "turn-retrieve-before-grounding", query)
        val goal = parse(query, JSONObject().put("operations", JSONArray()
            .put(JSONObject().put("op", "retrieve").put("instruction", "街景"))
            .put(JSONObject().put("op", "remove_content").put("target", "右后方的人"))), state)

        val result = AgentGoalCompiler.preflight(goal, state)

        assertTrue(result is AgentCompileResult.NeedBinding)
        assertEquals(
            ToolFamily.RETRIEVE,
            (result as AgentCompileResult.NeedBinding).requests.single().family
        )
    }

    @Test
    fun publishWithoutArtifactRequestsObservationRatherThanInventingInput() {
        val query = "发布结果"
        val state = AgentRuntimeState(null, "turn-publish", query)
        val goal = parse(query, JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "publish"))), state)

        val result = AgentGoalCompiler.preflight(goal, state)

        assertTrue(result is AgentCompileResult.NeedObservation)
        assertEquals(
            AgentDataKind.MEDIA_ARTIFACT,
            (result as AgentCompileResult.NeedObservation).requirements.single().kind
        )
    }

    @Test
    fun staleGoalIsRejectedBeforeBinding() {
        val query = "分析当前素材"
        val state = AgentRuntimeState(null, "turn-stale", query)
        val goal = parse(query, JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "analyze"))), state).copy(stateFingerprint = "stale")

        val result = AgentGoalCompiler.preflight(goal, state)

        assertTrue(result is AgentCompileResult.Unsupported)
        assertTrue((result as AgentCompileResult.Unsupported).reason.contains("stale state"))
    }

    @Test
    fun groundedObjectRequiresMaskTrackBeforeScopeCertificate() {
        val query = "移除右后方的人"
        val state = AgentRuntimeState(null, "turn-grounded-mask", query)
        val graph = AgentContentGraph(
            graphId = "content-grounded",
            version = 3,
            nodes = mapOf("object-1" to ContentNode("object-1", ContentNodeKind.OBJECT_TRACK))
        )
        val content = AgentContentGraphSnapshot(
            graph,
            AgentContentGraphValidator.validate(graph),
            AgentStateFingerprint.of(state)
        )
        val goal = groundedRemoveGoal(query, state, graph)

        val result = AgentGoalCompiler.preflight(goal, state, content)

        assertTrue(result is AgentCompileResult.NeedObservation)
        assertEquals(
            AgentDataKind.MASK_TRACK,
            (result as AgentCompileResult.NeedObservation).requirements.single().kind
        )
    }

    @Test
    fun groundedObjectAndMaskStillRequireScopeCertificate() {
        val query = "移除右后方的人"
        val state = AgentRuntimeState(null, "turn-grounded-scope", query)
        val graph = AgentContentGraph(
            graphId = "content-grounded",
            version = 4,
            nodes = mapOf(
                "object-1" to ContentNode("object-1", ContentNodeKind.OBJECT_TRACK),
                "mask-1" to ContentNode("mask-1", ContentNodeKind.MASK_TRACK)
            ),
            edges = mapOf(
                "masks-1" to ContentEdge(
                    "masks-1", "mask-1", "object-1", ContentEdgeKind.MASKS
                )
            )
        )
        val content = AgentContentGraphSnapshot(
            graph,
            AgentContentGraphValidator.validate(graph),
            AgentStateFingerprint.of(state)
        )
        val goal = groundedRemoveGoal(query, state, graph)

        val result = AgentGoalCompiler.preflight(goal, state, content)

        assertTrue(result is AgentCompileResult.NeedObservation)
        assertEquals(
            AgentDataKind.SCOPE_CERTIFICATE,
            (result as AgentCompileResult.NeedObservation).requirements.single().kind
        )
    }

    @Test
    fun groundedLocalChangeWithCertificateCompilesPreviewOnlyDag() {
        val query = "把右后方的人调成绿色"
        val state = AgentRuntimeState(null, "turn-local-preview", query)
        val graph = AgentContentGraph(
            graphId = "content-grounded",
            version = 5,
            sourceAssetIds = listOf("asset-1"),
            nodes = mapOf(
                "object-1" to ContentNode("object-1", ContentNodeKind.OBJECT_TRACK),
                "mask-1" to ContentNode("mask-1", ContentNodeKind.MASK_TRACK)
            ),
            edges = mapOf(
                "masks-1" to ContentEdge(
                    "masks-1", "mask-1", "object-1", ContentEdgeKind.MASKS
                )
            )
        )
        val content = AgentContentGraphSnapshot(
            graph,
            AgentContentGraphValidator.validate(graph),
            AgentStateFingerprint.of(state)
        )
        val semantic = parse(query, JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "change_content")
            .put("target", "右后方的人")
            .put("parameters", JSONObject()
                .put("effect_kind", "color_tint")
                .put("color_hex", "#22aa66")
                .put("intensity", 0.65)))), state)
        val operation = semantic.operations.single()
        val goal = semantic.copy(operations = listOf(operation.copy(
            target = AgentGoalTarget.GroundedContentNode(graph.graphId, graph.version, "object-1")
        )))
        val certificate = AgentScopeCertificate(
            certificateId = "scope-change",
            contentGraphId = graph.graphId,
            contentGraphVersion = graph.version,
            objectTrackNodeId = "object-1",
            maskTrackNodeId = "mask-1",
            stateFingerprint = AgentStateFingerprint.of(state),
            expectedFrameCount = 1,
            observations = listOf(ValidatedTrackObservation(
                0, 0, TrackObservationStatus.VISIBLE_VALIDATED, 10_000, null
            )),
            uniqueTarget = true,
            protectionChecksPassed = true,
            validationPolicy = TrackValidationPolicy("candidate-v1", 0.10)
        )

        val result = AgentGoalCompiler.preflight(
            goal,
            state,
            content,
            AgentCompilerObservationContext(
                scopeCertificates = mapOf(operation.operationId to certificate),
                sourceAssetIds = mapOf(operation.operationId to "asset-1")
            )
        )

        assertTrue(result is AgentCompileResult.Ready)
        val node = (result as AgentCompileResult.Ready).compilation.graph.nodes.values.single()
        assertEquals(LocalDeterministicChangePreviewAction.ACTION_ID, node.actionId)
        assertEquals(setOf(AgentStateEffect.ADD_ARTIFACT), node.effects)
        assertTrue(!node.destructive)
    }

    @Test
    fun groundedMaskedGenerationCompilesSeparateSingleFramePreviewAction() {
        val query = "把草地上的橘猫变成蓝色机械猫"
        val state = AgentRuntimeState(null, "turn-generation-preview", query)
        val graph = AgentContentGraph(
            graphId = "content-generation",
            version = 1,
            sourceAssetIds = listOf("asset-1"),
            nodes = mapOf(
                "object-1" to ContentNode("object-1", ContentNodeKind.OBJECT_TRACK),
                "mask-1" to ContentNode("mask-1", ContentNodeKind.MASK_TRACK)
            ),
            edges = mapOf(
                "masks-1" to ContentEdge(
                    "masks-1", "mask-1", "object-1", ContentEdgeKind.MASKS
                )
            )
        )
        val content = AgentContentGraphSnapshot(
            graph,
            AgentContentGraphValidator.validate(graph),
            AgentStateFingerprint.of(state)
        )
        val semantic = parse(query, JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "change_content")
            .put("target", JSONObject()
                .put("description", "orange cat on green grass")
                .put("source_text", "草地上的橘猫"))
            .put("parameters", JSONObject()
                .put("effect_kind", LocalGenerativeChange.EFFECT_KIND)
                .put("generation_prompt", JSONObject()
                    .put("value", "blue robotic cat")
                    .put("source_text", "蓝色机械猫"))))), state)
        val operation = semantic.operations.single()
        val goal = semantic.copy(operations = listOf(operation.copy(
            target = AgentGoalTarget.GroundedContentNode(graph.graphId, graph.version, "object-1")
        )))
        val certificate = AgentScopeCertificate(
            "scope-generate",
            graph.graphId,
            graph.version,
            "object-1",
            "mask-1",
            AgentStateFingerprint.of(state),
            1,
            listOf(ValidatedTrackObservation(
                0, 0, TrackObservationStatus.VISIBLE_VALIDATED, 10_000, null
            )),
            uniqueTarget = true,
            protectionChecksPassed = true,
            validationPolicy = TrackValidationPolicy("generation-preview-v1", 0.10)
        )

        val result = AgentGoalCompiler.preflight(
            goal,
            state,
            content,
            AgentCompilerObservationContext(
                scopeCertificates = mapOf(operation.operationId to certificate),
                sourceAssetIds = mapOf(operation.operationId to "asset-1")
            )
        ) as AgentCompileResult.Ready

        val node = result.compilation.graph.nodes.values.single()
        assertEquals(LocalMaskedGenerationPreviewAction.ACTION_ID, node.actionId)
        val parameters = JSONObject(node.parametersJson)
        assertTrue(parameters.getBoolean("preview_only"))
        assertTrue(parameters.getBoolean("single_frame_only"))
        assertTrue(!parameters.getBoolean("writeback"))
        assertEquals(
            LocalMaskedGenerationShadowCompiler.GENERATION_MASK_PADDING_PX,
            parameters.getInt("generation_mask_padding_px")
        )
        assertEquals(setOf(AgentStateEffect.ADD_ARTIFACT), node.effects)
    }

    private fun groundedRemoveGoal(
        query: String,
        state: AgentRuntimeState,
        graph: AgentContentGraph
    ): AgentGoalIr {
        val semantic = parse(query, JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "remove_content")
            .put("target", "右后方的人"))), state)
        return semantic.copy(operations = listOf(semantic.operations.single().copy(
            target = AgentGoalTarget.GroundedContentNode(
                graph.graphId,
                graph.version,
                "object-1"
            )
        )))
    }
}
