package com.vivo.lpa

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * First v3 compiler stage: lowers an already-bound Runtime v2 plan into a typed DAG.
 *
 * It runs in shadow mode only. The existing loop remains the executor, which lets graph invariants
 * be audited before any production tool is scheduled from the DAG.
 */
object AgentDagCompiler {
    fun compileShadow(plan: RuntimePlan, state: AgentRuntimeState): AgentDagCompilation {
        val fingerprint = AgentStateFingerprint.of(state)
        val goal = AgentGoalIrAdapter.from(plan.route, state, fingerprint)
        val nodes = linkedMapOf<String, AgentDagNode>()
        val stepNodeIds = linkedMapOf<String, String>()

        plan.steps.forEachIndexed { index, step ->
            val spec = requireNotNull(MobileToolRegistry.spec(step.tool)) {
                "cannot compile unregistered tool ${step.tool}"
            }
            val nodeId = nodeId(step.stepId)
            stepNodeIds[step.stepId] = nodeId
            val knownArtifacts = state.artifacts.values.filter { it.producerStepId == step.stepId }
            val outputPorts = spec.contract.outputPorts.map { output ->
                val exactKinds = knownArtifacts.map { it.kind.toDataKind() }
                    .filter { it in output.kinds }
                    .toSet()
                if (exactKinds.isEmpty()) output else output.copy(kinds = exactKinds)
            }
            nodes[nodeId] = AgentDagNode(
                nodeId = nodeId,
                operationId = plan.route.steps.getOrNull(index)?.stepId ?: step.stepId,
                actionId = step.tool,
                family = step.family,
                phase = spec.contract.phase,
                contractVersion = spec.contract.version,
                inputPorts = spec.contract.inputPorts,
                outputPorts = outputPorts,
                parameterSpecs = spec.contract.parameters,
                allowUnknownParameters = spec.contract.allowUnknownParameters,
                parametersJson = safeJson(step.arguments),
                resources = spec.contract.resources,
                effects = spec.contract.effects,
                destructive = spec.destructive,
                idempotent = spec.idempotent,
                status = step.status.toDagStatus(spec.contract.phase)
            )
        }

        val edges = linkedMapOf<String, AgentDagEdge>()
        val externalBindings = mutableListOf<AgentDagExternalBinding>()

        plan.steps.zipWithNext().forEachIndexed { index, (from, to) ->
            val edge = AgentDagEdge(
                edgeId = "control-$index-${from.stepId}-${to.stepId}",
                kind = AgentDagEdgeKind.CONTROL,
                fromNodeId = stepNodeIds.getValue(from.stepId),
                toNodeId = stepNodeIds.getValue(to.stepId)
            )
            edges[edge.edgeId] = edge
        }

        plan.steps.forEach { step ->
            val node = nodes.getValue(stepNodeIds.getValue(step.stepId))
            val boundPorts = mutableSetOf<String>()
            step.inputs.forEach { input ->
                when (input) {
                    is AgentInputRef.AssetSet -> {
                        val port = findInputPort(node, AgentDataKind.ASSET_SET, boundPorts)
                        if (port != null) {
                            externalBindings += AgentDagExternalBinding(
                                node.nodeId,
                                port.name,
                                AgentDagExternalValue.AssetSet(input.assetIds, fingerprint)
                            )
                            boundPorts += port.name
                        }
                    }
                    is AgentInputRef.Layout -> {
                        val port = findInputPort(node, AgentDataKind.LAYOUT, boundPorts)
                        if (port != null) {
                            externalBindings += AgentDagExternalBinding(
                                node.nodeId,
                                port.name,
                                AgentDagExternalValue.Layout(input.version)
                            )
                            boundPorts += port.name
                        }
                    }
                    is AgentInputRef.Artifact -> {
                        val artifact = state.artifacts[input.artifactId]
                        val kind = artifact?.kind?.toDataKind()
                            ?: inferArtifactKind(input.artifactId, state)
                        val port = findInputPort(node, kind, boundPorts)
                        if (port != null) {
                            val producerNodeId = artifact?.producerStepId?.let(stepNodeIds::get)
                            val producer = producerNodeId?.let(nodes::get)
                            val output = producer?.outputPorts?.firstOrNull { kind in it.kinds }
                            if (producer != null && output != null) {
                                val edge = AgentDagEdge(
                                    edgeId = "data-${producer.nodeId}-${output.name}-${node.nodeId}-${port.name}",
                                    kind = AgentDagEdgeKind.DATA,
                                    fromNodeId = producer.nodeId,
                                    fromPort = output.name,
                                    toNodeId = node.nodeId,
                                    toPort = port.name
                                )
                                edges[edge.edgeId] = edge
                            } else {
                                externalBindings += AgentDagExternalBinding(
                                    node.nodeId,
                                    port.name,
                                    AgentDagExternalValue.Artifact(
                                        input.artifactId,
                                        kind,
                                        artifact?.producerStepId
                                    )
                                )
                            }
                            boundPorts += port.name
                        }
                    }
                }
            }
        }

        val graph = AgentPlanGraph(
            graphId = "graph-${plan.planId}",
            graphVersion = plan.version,
            parentGraphVersion = plan.parentVersion,
            goalId = goal.goalId,
            stateFingerprint = fingerprint,
            toolSpecVersion = MobileToolRegistry.SPEC_VERSION,
            nodes = nodes,
            edges = edges,
            externalBindings = externalBindings,
            createdFromPlanId = plan.planId,
            revisionReason = plan.revisionReason
        )
        return AgentDagCompilation(goal, graph, AgentDagValidator.validate(graph))
    }

    private fun findInputPort(
        node: AgentDagNode,
        kind: AgentDataKind,
        alreadyBound: Set<String>
    ): AgentPortSpec? = node.inputPorts.firstOrNull {
        kind in it.kinds && (it.variadic || it.name !in alreadyBound)
    }

    private fun inferArtifactKind(artifactId: String, state: AgentRuntimeState): AgentDataKind {
        return state.artifacts[artifactId]?.kind?.toDataKind() ?: AgentDataKind.JSON
    }

    private fun nodeId(stepId: String): String = "node-$stepId"

    private fun RuntimeStepStatus.toDagStatus(phase: AgentActionPhase): AgentDagNodeStatus = when (this) {
        RuntimeStepStatus.PENDING -> AgentDagNodeStatus.PLANNED
        RuntimeStepStatus.RUNNING -> AgentDagNodeStatus.RUNNING
        RuntimeStepStatus.COMPLETED -> if (phase == AgentActionPhase.COMMIT) {
            AgentDagNodeStatus.COMMITTED
        } else {
            AgentDagNodeStatus.VALIDATED
        }
        RuntimeStepStatus.FAILED -> AgentDagNodeStatus.FAILED
        RuntimeStepStatus.CANCELLED -> AgentDagNodeStatus.CANCELLED
    }

    private fun safeJson(json: JSONObject): String = runCatching { json.toString() }
        .getOrNull()?.takeIf { it.startsWith("{") } ?: "{}"
}

object AgentStateFingerprint {
    fun of(state: AgentRuntimeState): String {
        val canonical = buildString {
            append("assets=")
            append(state.sourceAssets.map { it.assetId }.sorted().joinToString(","))
            append("|selected=")
            append(state.selectedAssetIds.sorted().joinToString(","))
            append("|layout=")
            append(state.layoutVersion)
            append("|artifacts=")
            append(state.artifacts.values.sortedBy { it.artifactId }.joinToString(",") {
                "${it.artifactId}:${it.kind}:${it.validated}:${it.published}:${it.producerStepId}"
            })
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Audit serializer kept outside the graph model so the compiler core remains JSON-independent. */
object AgentDagJson {
    fun toJson(compilation: AgentDagCompilation): JSONObject = JSONObject()
        .put("goal", goalJson(compilation.goal))
        .put("graph", graphJson(compilation.graph))
        .put("validation", JSONObject()
            .put("valid", compilation.validation.valid)
            .put("complete", compilation.complete)
            .put("executable", compilation.executable)
            .put("topological_order", JSONArray(compilation.validation.topologicalOrder))
            .put("diagnostics", JSONArray(compilation.validation.diagnostics.map { diagnostic ->
                JSONObject()
                    .put("severity", diagnostic.severity.name.lowercase())
                    .put("code", diagnostic.code)
                    .put("message", diagnostic.message)
                    .put("node_id", diagnostic.nodeId ?: JSONObject.NULL)
                    .put("edge_id", diagnostic.edgeId ?: JSONObject.NULL)
                    .put("port", diagnostic.port ?: JSONObject.NULL)
            })))

    private fun goalJson(goal: AgentGoalIr): JSONObject = JSONObject()
        .put("goal_id", goal.goalId)
        .put("schema_version", goal.schemaVersion)
        .put("user_goal", goal.userGoal)
        .put("state_fingerprint", goal.stateFingerprint)
        .put("operations", JSONArray(goal.operations.map { operation ->
            JSONObject()
                .put("operation_id", operation.operationId)
                .put("kind", operation.kind.name)
                .put("scope", scopeJson(operation.scope))
                .put("instruction", operation.instruction)
                .put("requested_count", operation.requestedCount ?: JSONObject.NULL)
                .put("required_media", operation.requiredMedia?.name ?: JSONObject.NULL)
        }))
        .put("unresolved_slots", JSONArray(goal.unresolvedSlots.map { slot ->
            JSONObject()
                .put("slot_id", slot.slotId)
                .put("operation_id", slot.operationId)
                .put("name", slot.name)
                .put("alternatives", JSONArray(slot.alternatives))
                .put("user_visible_impact", slot.userVisibleImpact)
        }))

    private fun scopeJson(scope: AgentGoalScope): JSONObject = when (scope) {
        AgentGoalScope.Library -> JSONObject().put("type", "library")
        AgentGoalScope.CurrentAssets -> JSONObject().put("type", "current_assets")
        is AgentGoalScope.SelectedAssets -> JSONObject()
            .put("type", "selected_assets")
            .put("asset_ids", JSONArray(scope.assetIds))
            .put("state_fingerprint", scope.stateFingerprint)
        is AgentGoalScope.PreviousOutput -> JSONObject()
            .put("type", "previous_output")
            .put("operation_id", scope.operationId)
        is AgentGoalScope.ContentNode -> JSONObject()
            .put("type", "content_node")
            .put("content_graph_id", scope.contentGraphId)
            .put("content_graph_version", scope.contentGraphVersion)
            .put("node_id", scope.nodeId)
    }

    private fun graphJson(graph: AgentPlanGraph): JSONObject = JSONObject()
        .put("graph_id", graph.graphId)
        .put("schema_version", graph.schemaVersion)
        .put("graph_version", graph.graphVersion)
        .put("parent_graph_version", graph.parentGraphVersion ?: JSONObject.NULL)
        .put("goal_id", graph.goalId)
        .put("state_fingerprint", graph.stateFingerprint)
        .put("tool_spec_version", graph.toolSpecVersion)
        .put("created_from_plan_id", graph.createdFromPlanId ?: JSONObject.NULL)
        .put("revision_reason", graph.revisionReason)
        .put("nodes", JSONArray(graph.nodes.values.map { node ->
            JSONObject()
                .put("node_id", node.nodeId)
                .put("operation_id", node.operationId)
                .put("action_id", node.actionId)
                .put("family", node.family.name)
                .put("phase", node.phase.name)
                .put("contract_version", node.contractVersion)
                .put("parameters", runCatching { JSONObject(node.parametersJson) }.getOrElse { JSONObject() })
                .put("parameter_schema", JSONArray(node.parameterSpecs.values.sortedBy { it.name }.map {
                    parameterJson(it)
                }))
                .put("allow_unknown_parameters", node.allowUnknownParameters)
                .put("input_ports", JSONArray(node.inputPorts.map(::portJson)))
                .put("output_ports", JSONArray(node.outputPorts.map(::portJson)))
                .put("resources", JSONArray(node.resources.map { it.resource.name }.sorted()))
                .put("effects", JSONArray(node.effects.map { it.name }.sorted()))
                .put("destructive", node.destructive)
                .put("idempotent", node.idempotent)
                .put("status", node.status.name.lowercase())
        }))
        .put("edges", JSONArray(graph.edges.values.map { edge ->
            JSONObject()
                .put("edge_id", edge.edgeId)
                .put("kind", edge.kind.name.lowercase())
                .put("from_node_id", edge.fromNodeId)
                .put("from_port", edge.fromPort ?: JSONObject.NULL)
                .put("to_node_id", edge.toNodeId)
                .put("to_port", edge.toPort ?: JSONObject.NULL)
        }))
        .put("external_bindings", JSONArray(graph.externalBindings.map { binding ->
            JSONObject()
                .put("node_id", binding.nodeId)
                .put("input_port", binding.inputPort)
                .put("kind", binding.value.kind.name)
                .put("stable_id", binding.value.stableId)
        }))

    private fun portJson(port: AgentPortSpec): JSONObject = JSONObject()
        .put("name", port.name)
        .put("kinds", JSONArray(port.kinds.map { it.name }))
        .put("required", port.required)
        .put("variadic", port.variadic)
        .put("min_items", port.minItems)
        .put("max_items", if (port.maxItems == Int.MAX_VALUE) JSONObject.NULL else port.maxItems)

    private fun parameterJson(parameter: AgentParameterSpec): JSONObject = JSONObject()
        .put("name", parameter.name)
        .put("type", parameter.type.name)
        .put("required", parameter.required)
        .put("unit", parameter.unit ?: JSONObject.NULL)
        .put("minimum", parameter.minimum ?: JSONObject.NULL)
        .put("maximum", parameter.maximum ?: JSONObject.NULL)
        .put("enum_values", JSONArray(parameter.enumValues.sorted()))
}
