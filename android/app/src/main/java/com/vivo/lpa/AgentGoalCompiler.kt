package com.vivo.lpa

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Preflight before concrete actions are bound or an HTN method is expanded. */
object AgentGoalCompiler {
    fun preflight(
        goal: AgentGoalIr,
        state: AgentRuntimeState,
        content: AgentContentGraphSnapshot? = null,
        observations: AgentCompilerObservationContext = AgentCompilerObservationContext()
    ): AgentCompileResult {
        val currentFingerprint = AgentStateFingerprint.of(state)
        if (goal.stateFingerprint != currentFingerprint) {
            return AgentCompileResult.Unsupported(
                goal,
                "Goal IR was produced for stale state ${goal.stateFingerprint}; current=$currentFingerprint"
            )
        }
        val unresolved = goal.unresolvedSlots.firstOrNull()
        if (unresolved != null) {
            val alternatives = unresolved.alternatives
            val suffix = alternatives.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "（", postfix = "）", separator = " / ")
                .orEmpty()
            return AgentCompileResult.NeedUser(
                goal,
                AgentQuestionSpec(
                    questionId = "question-${unresolved.slotId}",
                    operationId = unresolved.operationId,
                    missingSlot = unresolved.name,
                    prompt = "请确认 ${unresolved.name}$suffix",
                    alternatives = alternatives,
                    continuationToken = continuationToken(goal, unresolved.slotId),
                    graphVersion = state.plan?.version ?: 0
                )
            )
        }

        val first = goal.operations.firstOrNull { operation ->
            val index = goal.operations.indexOf(operation)
            state.plan?.steps?.getOrNull(index)?.status != RuntimeStepStatus.COMPLETED
        }
            ?: return AgentCompileResult.Unsupported(goal, "No uncompleted Goal IR operations remain")
        validateGroundedReference(goal, first, state, content)?.let { return it }
        if (first.kind in setOf(
                AgentGoalOperationKind.ADD_CONTENT,
                AgentGoalOperationKind.CHANGE_CONTENT,
                AgentGoalOperationKind.REMOVE_CONTENT
            )
        ) {
            val target = first.target
            if (target is AgentGoalTarget.Semantic) {
                val requiredKind = when (first.kind) {
                    AgentGoalOperationKind.ADD_CONTENT -> AgentDataKind.REGION
                    AgentGoalOperationKind.CHANGE_CONTENT,
                    AgentGoalOperationKind.REMOVE_CONTENT -> AgentDataKind.OBJECT_TRACK
                    else -> AgentDataKind.JSON
                }
                return AgentCompileResult.NeedObservation(
                    goal,
                    listOf(AgentObservationRequirement(
                        "observe-${first.operationId}-target",
                        first.operationId,
                        requiredKind,
                        "Ground semantic target '${target.description}' against the current content graph"
                    ))
                )
            }
            if (target is AgentGoalTarget.GroundedContentNode) {
                val graph = content?.graph
                val targetNode = graph?.nodes?.get(target.nodeId)
                val requiresMask = first.kind == AgentGoalOperationKind.CHANGE_CONTENT ||
                    first.kind == AgentGoalOperationKind.REMOVE_CONTENT
                if (requiresMask && targetNode?.kind != ContentNodeKind.OBJECT_TRACK) {
                    return AgentCompileResult.NeedObservation(
                        goal,
                        listOf(AgentObservationRequirement(
                            "observe-${first.operationId}-object-track",
                            first.operationId,
                            AgentDataKind.OBJECT_TRACK,
                            "Content target ${target.nodeId} is not a validated object track"
                        ))
                    )
                }
                val maskTrack = findMaskTrack(graph, target.nodeId)
                if (requiresMask && maskTrack == null) {
                    return AgentCompileResult.NeedObservation(
                        goal,
                        listOf(AgentObservationRequirement(
                            "observe-${first.operationId}-mask-track",
                            first.operationId,
                            AgentDataKind.MASK_TRACK,
                            "A validated temporal mask track is required for ${target.nodeId}"
                        ))
                    )
                }
                val certificate = observations.scopeCertificates[first.operationId]
                val certificateMatches = certificate != null &&
                    certificate.contentGraphId == target.contentGraphId &&
                    certificate.contentGraphVersion == target.contentGraphVersion &&
                    certificate.objectTrackNodeId == target.nodeId &&
                    (!requiresMask || certificate.maskTrackNodeId == maskTrack?.nodeId) &&
                    certificate.readyForPreview(currentFingerprint)
                if (!certificateMatches) {
                    return AgentCompileResult.NeedObservation(
                        goal,
                        listOf(AgentObservationRequirement(
                            "validate-${first.operationId}-scope",
                            first.operationId,
                            AgentDataKind.SCOPE_CERTIFICATE,
                            "Preview requires a fresh ScopeCertificate with uniqueness, temporal coverage and protection checks"
                        ))
                    )
                }
                if (first.kind == AgentGoalOperationKind.CHANGE_CONTENT) {
                    val change = LocalDeterministicChange.fromOperation(first)
                        ?: return AgentCompileResult.NeedUser(
                            goal,
                            AgentQuestionSpec(
                                questionId = "question-${first.operationId}-local-effect",
                                operationId = first.operationId,
                                missingSlot = "local_effect",
                                prompt = "请确认局部修改方式和强度",
                                alternatives = listOf("局部调色", "局部模糊"),
                                continuationToken = continuationToken(goal, "local-effect"),
                                graphVersion = state.plan?.version ?: 0
                            )
                        )
                    val sourceAssetId = observations.sourceAssetIds[first.operationId]
                    val sourceExists = sourceAssetId != null && (
                        state.sourceAssets.any { it.assetId == sourceAssetId } ||
                            graph?.sourceAssetIds?.contains(sourceAssetId) == true
                        )
                    if (!sourceExists) {
                        return AgentCompileResult.NeedObservation(
                            goal,
                            listOf(AgentObservationRequirement(
                                "observe-${first.operationId}-source",
                                first.operationId,
                                AgentDataKind.ASSET_SET,
                                "Local preview requires exactly one current source asset"
                            ))
                        )
                    }
                    return AgentCompileResult.Ready(
                        LocalDeterministicChangeShadowCompiler.compilePreview(
                            goal,
                            first.operationId,
                            requireNotNull(sourceAssetId),
                            requireNotNull(certificate),
                            change
                        )
                    )
                }
                return AgentCompileResult.Unsupported(
                    goal,
                    "No registered preview method for ${first.kind}; content mutation remains disabled"
                )
            }
            return AgentCompileResult.Unsupported(
                goal,
                "No registered HTN method for ${first.kind}; content mutation remains disabled"
            )
        }

        val family = first.kind.toToolFamily()
            ?: return AgentCompileResult.Unsupported(goal, "No concrete tool family for ${first.kind}")
        val catalog = MobileToolRegistry.loopCatalog(state.toolContext(family))
        if (catalog.available.isEmpty()) {
            if (first.kind == AgentGoalOperationKind.PUBLISH) {
                return AgentCompileResult.NeedObservation(
                    goal,
                    listOf(AgentObservationRequirement(
                        "observe-${first.operationId}-artifact",
                        first.operationId,
                        AgentDataKind.MEDIA_ARTIFACT,
                        "Publishing requires a validated media artifact"
                    ))
                )
            }
            return AgentCompileResult.Unsupported(
                goal,
                "${first.kind} has no executable action in the current state",
                catalog.excluded.entries.map { "${it.key}: ${it.value}" }
            )
        }
        return AgentCompileResult.NeedBinding(
            goal,
            listOf(AgentBindingRequest(
                first.operationId,
                family,
                catalog.available.map { it.id },
                currentFingerprint
            ))
        )
    }

    private fun validateGroundedReference(
        goal: AgentGoalIr,
        operation: AgentGoalOperation,
        state: AgentRuntimeState,
        content: AgentContentGraphSnapshot?
    ): AgentCompileResult? {
        val selected = operation.scope as? AgentGoalScope.SelectedAssets
        if (selected != null && selected.assetIds.any { it !in state.selectedAssetIds }) {
            return AgentCompileResult.Unsupported(
                goal,
                "${operation.operationId} references a stale UI selection"
            )
        }
        val scopeNode = operation.scope as? AgentGoalScope.ContentNode
        if (scopeNode != null && !containsContentNode(
                content,
                scopeNode.contentGraphId,
                scopeNode.contentGraphVersion,
                scopeNode.nodeId
            )
        ) {
            return missingGrounding(goal, operation, scopeNode.nodeId)
        }
        val target = operation.target as? AgentGoalTarget.GroundedContentNode
        if (target != null && !containsContentNode(
                content,
                target.contentGraphId,
                target.contentGraphVersion,
                target.nodeId
            )
        ) {
            return missingGrounding(goal, operation, target.nodeId)
        }
        return null
    }

    private fun containsContentNode(
        snapshot: AgentContentGraphSnapshot?,
        graphId: String,
        version: Int,
        nodeId: String
    ): Boolean = snapshot != null &&
        snapshot.validation.valid &&
        snapshot.graph.graphId == graphId &&
        snapshot.graph.version == version &&
        nodeId in snapshot.graph.nodes

    private fun findMaskTrack(graph: AgentContentGraph?, objectNodeId: String): ContentNode? {
        if (graph == null) return null
        val candidateIds = graph.edges.values.asSequence()
            .filter { it.kind == ContentEdgeKind.MASKS }
            .flatMap { edge ->
                when (objectNodeId) {
                    edge.fromNodeId -> sequenceOf(edge.toNodeId)
                    edge.toNodeId -> sequenceOf(edge.fromNodeId)
                    else -> emptySequence()
                }
            }
        return candidateIds.mapNotNull { graph.nodes[it] }
            .firstOrNull { it.kind == ContentNodeKind.MASK_TRACK }
    }

    private fun missingGrounding(
        goal: AgentGoalIr,
        operation: AgentGoalOperation,
        nodeId: String
    ) = AgentCompileResult.NeedObservation(
        goal,
        listOf(AgentObservationRequirement(
            "refresh-${operation.operationId}-target",
            operation.operationId,
            AgentDataKind.OBJECT_TRACK,
            "Content target $nodeId is missing or stale; refresh grounding"
        ))
    )

    private fun AgentGoalOperationKind.toToolFamily(): ToolFamily? = when (this) {
        AgentGoalOperationKind.RETRIEVE -> ToolFamily.RETRIEVE
        AgentGoalOperationKind.COMPOSE -> ToolFamily.COMPOSE
        AgentGoalOperationKind.APPLY_STYLE -> ToolFamily.STYLE
        AgentGoalOperationKind.TEMPORAL_EDIT -> ToolFamily.TEMPORAL
        AgentGoalOperationKind.UPDATE_LAYOUT -> ToolFamily.LAYOUT
        AgentGoalOperationKind.REPLACE_ASSET -> ToolFamily.REPLACE
        AgentGoalOperationKind.ANALYZE -> ToolFamily.ANALYZE
        AgentGoalOperationKind.PUBLISH -> ToolFamily.PUBLISH
        AgentGoalOperationKind.ADD_CONTENT,
        AgentGoalOperationKind.CHANGE_CONTENT,
        AgentGoalOperationKind.REMOVE_CONTENT -> null
    }

    private fun continuationToken(goal: AgentGoalIr, slotId: String): String {
        val value = "${goal.goalId}|${goal.stateFingerprint}|$slotId"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

object AgentCompileResultJson {
    fun toJson(result: AgentCompileResult): JSONObject = when (result) {
        is AgentCompileResult.Ready -> JSONObject()
            .put("status", "ready")
            .put("goal_id", result.compilation.goal.goalId)
            .put("graph_id", result.compilation.graph.graphId)
        is AgentCompileResult.NeedBinding -> JSONObject()
            .put("status", "need_binding")
            .put("goal_id", result.goal.goalId)
            .put("requests", JSONArray(result.requests.map { request ->
                JSONObject()
                    .put("operation_id", request.operationId)
                    .put("family", request.family.name)
                    .put("candidate_action_ids", JSONArray(request.candidateActionIds))
                    .put("state_fingerprint", request.stateFingerprint)
            }))
        is AgentCompileResult.NeedObservation -> JSONObject()
            .put("status", "need_observation")
            .put("goal_id", result.goal.goalId)
            .put("requirements", JSONArray(result.requirements.map { requirement ->
                JSONObject()
                    .put("requirement_id", requirement.requirementId)
                    .put("operation_id", requirement.operationId)
                    .put("kind", requirement.kind.name)
                    .put("description", requirement.description)
            }))
        is AgentCompileResult.NeedUser -> JSONObject()
            .put("status", "need_user")
            .put("goal_id", result.goal.goalId)
            .put("question", JSONObject()
                .put("question_id", result.question.questionId)
                .put("operation_id", result.question.operationId)
                .put("missing_slot", result.question.missingSlot)
                .put("prompt", result.question.prompt)
                .put("alternatives", JSONArray(result.question.alternatives))
                .put("continuation_token", result.question.continuationToken)
                .put("graph_version", result.question.graphVersion))
        is AgentCompileResult.Unsupported -> JSONObject()
            .put("status", "unsupported")
            .put("goal_id", result.goal.goalId)
            .put("reason", result.reason)
            .put("alternatives", JSONArray(result.alternatives))
    }
}
