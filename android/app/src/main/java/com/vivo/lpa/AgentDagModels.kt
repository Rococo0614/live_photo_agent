package com.vivo.lpa

import org.json.JSONArray
import org.json.JSONObject

enum class AgentDagEdgeKind {
    DATA,
    CONTROL,
    CONSTRAINT
}

enum class AgentDagNodeStatus {
    PLANNED,
    READY,
    RUNNING,
    VALIDATED,
    COMMITTED,
    FAILED,
    CANCELLED
}

sealed interface AgentDagExternalValue {
    val kind: AgentDataKind
    val stableId: String

    data class AssetSet(
        val assetIds: List<String>,
        val stateFingerprint: String
    ) : AgentDagExternalValue {
        override val kind = AgentDataKind.ASSET_SET
        override val stableId = "assets:${assetIds.joinToString(",")}:$stateFingerprint"
    }

    data class Artifact(
        val artifactId: String,
        override val kind: AgentDataKind,
        val producerStepId: String? = null
    ) : AgentDagExternalValue {
        override val stableId = artifactId
    }

    data class Layout(val version: Int) : AgentDagExternalValue {
        override val kind = AgentDataKind.LAYOUT
        override val stableId = "layout:$version"
    }

    data class ContentNode(
        val contentGraphId: String,
        val contentGraphVersion: Int,
        val contentNodeId: String,
        override val kind: AgentDataKind
    ) : AgentDagExternalValue {
        override val stableId = "$contentGraphId:$contentGraphVersion:$contentNodeId"
    }
}

data class AgentDagExternalBinding(
    val nodeId: String,
    val inputPort: String,
    val value: AgentDagExternalValue
)

data class AgentDagNode(
    val nodeId: String,
    val operationId: String,
    val actionId: String,
    val family: ToolFamily,
    val phase: AgentActionPhase,
    val contractVersion: Int,
    val inputPorts: List<AgentPortSpec>,
    val outputPorts: List<AgentPortSpec>,
    val parameterSpecs: Map<String, AgentParameterSpec> = emptyMap(),
    val allowUnknownParameters: Boolean = true,
    val parametersJson: String = "{}",
    val resources: Set<AgentResourceClaim> = emptySet(),
    val effects: Set<AgentStateEffect> = emptySet(),
    val destructive: Boolean = false,
    val idempotent: Boolean = true,
    val status: AgentDagNodeStatus = AgentDagNodeStatus.PLANNED
) {
    init {
        require(nodeId.isNotBlank()) { "DAG node id cannot be blank" }
        require(operationId.isNotBlank()) { "DAG operation id cannot be blank" }
        require(actionId.isNotBlank()) { "DAG action id cannot be blank" }
        require(contractVersion > 0) { "DAG action contract version must be positive" }
    }
}

data class AgentDagEdge(
    val edgeId: String,
    val kind: AgentDagEdgeKind,
    val fromNodeId: String,
    val toNodeId: String,
    val fromPort: String? = null,
    val toPort: String? = null
) {
    init {
        require(edgeId.isNotBlank()) { "DAG edge id cannot be blank" }
        require(fromNodeId.isNotBlank() && toNodeId.isNotBlank()) {
            "DAG edge endpoints cannot be blank"
        }
        if (kind == AgentDagEdgeKind.DATA) {
            require(!fromPort.isNullOrBlank() && !toPort.isNullOrBlank()) {
                "data edge $edgeId requires ports"
            }
        }
    }
}

data class AgentPlanGraph(
    val graphId: String,
    val schemaVersion: Int = 1,
    val graphVersion: Int,
    val parentGraphVersion: Int? = null,
    val goalId: String,
    val stateFingerprint: String,
    val toolSpecVersion: Int,
    val nodes: Map<String, AgentDagNode>,
    val edges: Map<String, AgentDagEdge>,
    val externalBindings: List<AgentDagExternalBinding> = emptyList(),
    val createdFromPlanId: String? = null,
    val revisionReason: String = "initial"
) {
    init {
        require(graphId.isNotBlank()) { "DAG graph id cannot be blank" }
        require(schemaVersion > 0) { "DAG schema version must be positive" }
        require(graphVersion > 0) { "DAG graph version must be positive" }
        require(goalId.isNotBlank()) { "DAG goal id cannot be blank" }
        require(toolSpecVersion > 0) { "ToolSpec version must be positive" }
    }
}

enum class AgentDagDiagnosticSeverity {
    ERROR,
    WARNING
}

data class AgentDagDiagnostic(
    val severity: AgentDagDiagnosticSeverity,
    val code: String,
    val message: String,
    val nodeId: String? = null,
    val edgeId: String? = null,
    val port: String? = null
)

data class AgentDagValidation(
    val diagnostics: List<AgentDagDiagnostic>,
    val topologicalOrder: List<String>
) {
    val valid: Boolean
        get() = diagnostics.none { it.severity == AgentDagDiagnosticSeverity.ERROR }
}

data class AgentDagCompilation(
    val goal: AgentGoalIr,
    val graph: AgentPlanGraph,
    val validation: AgentDagValidation
) {
    val complete: Boolean
        get() = goal.operations.all { operation ->
            graph.nodes.values.any { it.operationId == operation.operationId }
        }

    val executable: Boolean
        get() = complete && validation.valid
}

data class AgentObservationRequirement(
    val requirementId: String,
    val operationId: String,
    val kind: AgentDataKind,
    val description: String
)

data class AgentQuestionSpec(
    val questionId: String,
    val operationId: String,
    val missingSlot: String,
    val prompt: String,
    val alternatives: List<String>,
    val continuationToken: String,
    val graphVersion: Int
)

data class AgentBindingRequest(
    val operationId: String,
    val family: ToolFamily,
    val candidateActionIds: List<String>,
    val stateFingerprint: String
)

/** Terminal result of future Goal IR compilation. Shadow lowering currently returns [Ready]. */
sealed interface AgentCompileResult {
    data class Ready(val compilation: AgentDagCompilation) : AgentCompileResult
    data class NeedBinding(
        val goal: AgentGoalIr,
        val requests: List<AgentBindingRequest>
    ) : AgentCompileResult
    data class NeedObservation(
        val goal: AgentGoalIr,
        val requirements: List<AgentObservationRequirement>
    ) : AgentCompileResult
    data class NeedUser(
        val goal: AgentGoalIr,
        val question: AgentQuestionSpec
    ) : AgentCompileResult
    data class Unsupported(
        val goal: AgentGoalIr,
        val reason: String,
        val alternatives: List<String> = emptyList()
    ) : AgentCompileResult
}

/** Static type and structure checks. It does not execute tools or infer user intent. */
object AgentDagValidator {
    const val MAX_NODES = 64

    fun validate(graph: AgentPlanGraph): AgentDagValidation {
        val diagnostics = mutableListOf<AgentDagDiagnostic>()
        if (graph.nodes.size > MAX_NODES) {
            diagnostics += error(
                "node_limit", "graph has ${graph.nodes.size} nodes; maximum is $MAX_NODES"
            )
        }

        graph.nodes.forEach { (key, node) ->
            if (key != node.nodeId) {
                diagnostics += error(
                    "node_key_mismatch", "node key $key does not match ${node.nodeId}", node.nodeId
                )
            }
        }
        graph.edges.forEach { (key, edge) ->
            if (key != edge.edgeId) {
                diagnostics += error(
                    "edge_key_mismatch", "edge key $key does not match ${edge.edgeId}", edgeId = edge.edgeId
                )
            }
            val source = graph.nodes[edge.fromNodeId]
            val target = graph.nodes[edge.toNodeId]
            if (source == null) {
                diagnostics += error(
                    "missing_edge_source", "edge ${edge.edgeId} source ${edge.fromNodeId} is missing",
                    edgeId = edge.edgeId
                )
            }
            if (target == null) {
                diagnostics += error(
                    "missing_edge_target", "edge ${edge.edgeId} target ${edge.toNodeId} is missing",
                    edgeId = edge.edgeId
                )
            }
            if (edge.kind == AgentDagEdgeKind.DATA && source != null && target != null) {
                val output = source.outputPorts.firstOrNull { it.name == edge.fromPort }
                val input = target.inputPorts.firstOrNull { it.name == edge.toPort }
                if (output == null) {
                    diagnostics += error(
                        "missing_output_port", "${source.nodeId} has no output ${edge.fromPort}",
                        nodeId = source.nodeId, edgeId = edge.edgeId, port = edge.fromPort
                    )
                }
                if (input == null) {
                    diagnostics += error(
                        "missing_input_port", "${target.nodeId} has no input ${edge.toPort}",
                        nodeId = target.nodeId, edgeId = edge.edgeId, port = edge.toPort
                    )
                }
                if (output != null && input != null && output.kinds.intersect(input.kinds).isEmpty()) {
                    diagnostics += error(
                        "incompatible_data_edge",
                        "${source.nodeId}.${output.name} ${output.kinds} cannot feed " +
                            "${target.nodeId}.${input.name} ${input.kinds}",
                        nodeId = target.nodeId, edgeId = edge.edgeId, port = input.name
                    )
                }
            }
        }

        graph.externalBindings.forEach { binding ->
            val node = graph.nodes[binding.nodeId]
            if (node == null) {
                diagnostics += error(
                    "missing_binding_node", "external binding target ${binding.nodeId} is missing"
                )
            } else {
                val input = node.inputPorts.firstOrNull { it.name == binding.inputPort }
                if (input == null) {
                    diagnostics += error(
                        "missing_binding_port", "${node.nodeId} has no input ${binding.inputPort}",
                        nodeId = node.nodeId, port = binding.inputPort
                    )
                } else if (binding.value.kind !in input.kinds) {
                    diagnostics += error(
                        "incompatible_external_binding",
                        "${binding.value.kind} cannot feed ${node.nodeId}.${input.name} ${input.kinds}",
                        nodeId = node.nodeId, port = input.name
                    )
                }
                if (binding.value is AgentDagExternalValue.AssetSet &&
                    binding.value.stateFingerprint != graph.stateFingerprint
                ) {
                    diagnostics += error(
                        "stale_scope",
                        "${node.nodeId}.${binding.inputPort} was bound against a different state snapshot",
                        nodeId = node.nodeId,
                        port = binding.inputPort
                    )
                }
                if (input != null && binding.value is AgentDagExternalValue.AssetSet &&
                    binding.value.assetIds.size !in input.minItems..input.maxItems
                ) {
                    diagnostics += error(
                        "item_cardinality",
                        "${node.nodeId}.${binding.inputPort} contains ${binding.value.assetIds.size} " +
                            "items; expected ${input.minItems}..${input.maxItems}",
                        nodeId = node.nodeId,
                        port = binding.inputPort
                    )
                }
            }
        }

        graph.nodes.values.forEach { node ->
            validateParameters(node, diagnostics)
            node.inputPorts.forEach { input ->
                val edgeCount = graph.edges.values.count {
                    it.kind == AgentDagEdgeKind.DATA && it.toNodeId == node.nodeId && it.toPort == input.name
                }
                val externalCount = graph.externalBindings.count {
                    it.nodeId == node.nodeId && it.inputPort == input.name
                }
                val count = edgeCount + externalCount
                if (input.required && count == 0) {
                    diagnostics += error(
                        "missing_required_input", "${node.nodeId}.${input.name} is required",
                        nodeId = node.nodeId, port = input.name
                    )
                }
                if (!input.variadic && count > 1) {
                    diagnostics += error(
                        "multiple_input_bindings", "${node.nodeId}.${input.name} has $count bindings",
                        nodeId = node.nodeId, port = input.name
                    )
                }
            }
            if (node.phase == AgentActionPhase.COMMIT && graph.edges.values.any {
                    it.fromNodeId == node.nodeId
                }
            ) {
                diagnostics += warning(
                    "commit_not_sink", "commit node ${node.nodeId} has outgoing edges", node.nodeId
                )
            }
        }

        val topologicalOrder = topologicalOrder(graph)
        if (topologicalOrder.size != graph.nodes.size) {
            diagnostics += error("cycle", "graph contains a cycle")
        }
        return AgentDagValidation(diagnostics, topologicalOrder)
    }

    private fun validateParameters(
        node: AgentDagNode,
        diagnostics: MutableList<AgentDagDiagnostic>
    ) {
        val values = runCatching { JSONObject(node.parametersJson) }.getOrElse { error ->
            diagnostics += error(
                "invalid_parameters_json",
                "${node.nodeId} parameters are not valid JSON: ${error.message}",
                nodeId = node.nodeId
            )
            return
        }
        val keys = values.keys().asSequence().toSet()
        if (!node.allowUnknownParameters) {
            (keys - node.parameterSpecs.keys).forEach { key ->
                diagnostics += error(
                    "unknown_parameter", "${node.nodeId} does not declare parameter $key",
                    nodeId = node.nodeId, port = key
                )
            }
        }
        node.parameterSpecs.values.forEach { spec ->
            if (!values.has(spec.name) || values.isNull(spec.name)) {
                if (spec.required) {
                    diagnostics += error(
                        "missing_parameter", "${node.nodeId} requires parameter ${spec.name}",
                        nodeId = node.nodeId, port = spec.name
                    )
                }
                return@forEach
            }
            val value = values.opt(spec.name)
            val typeMatches = when (spec.type) {
                AgentParameterType.STRING -> value is String
                AgentParameterType.BOOLEAN -> value is Boolean
                AgentParameterType.INTEGER -> value is Number && value.toDouble() % 1.0 == 0.0
                AgentParameterType.LONG -> value is Number && value.toDouble() % 1.0 == 0.0
                AgentParameterType.DECIMAL -> value is Number
                AgentParameterType.STRING_LIST -> value is JSONArray && (0 until value.length()).all {
                    value.opt(it) is String
                }
                AgentParameterType.OBJECT -> value is JSONObject
            }
            if (!typeMatches) {
                diagnostics += error(
                    "parameter_type", "${node.nodeId}.${spec.name} must be ${spec.type}",
                    nodeId = node.nodeId, port = spec.name
                )
                return@forEach
            }
            if (value is Number) {
                val numeric = value.toDouble()
                if (spec.minimum != null && numeric < spec.minimum) {
                    diagnostics += error(
                        "parameter_minimum",
                        "${node.nodeId}.${spec.name}=$numeric is below ${spec.minimum}",
                        nodeId = node.nodeId,
                        port = spec.name
                    )
                }
                if (spec.maximum != null && numeric > spec.maximum) {
                    diagnostics += error(
                        "parameter_maximum",
                        "${node.nodeId}.${spec.name}=$numeric is above ${spec.maximum}",
                        nodeId = node.nodeId,
                        port = spec.name
                    )
                }
            }
            if (value is String && spec.enumValues.isNotEmpty() && value !in spec.enumValues) {
                diagnostics += error(
                    "parameter_enum",
                    "${node.nodeId}.${spec.name}=$value is not in ${spec.enumValues}",
                    nodeId = node.nodeId,
                    port = spec.name
                )
            }
        }
    }

    private fun topologicalOrder(graph: AgentPlanGraph): List<String> {
        val indegree = graph.nodes.keys.associateWith { 0 }.toMutableMap()
        val outgoing = graph.nodes.keys.associateWith { mutableListOf<String>() }.toMutableMap()
        graph.edges.values.forEach { edge ->
            if (edge.fromNodeId in graph.nodes && edge.toNodeId in graph.nodes) {
                indegree[edge.toNodeId] = indegree.getValue(edge.toNodeId) + 1
                outgoing.getValue(edge.fromNodeId) += edge.toNodeId
            }
        }
        val ready = java.util.PriorityQueue<String>()
        indegree.filterValues { it == 0 }.keys.forEach(ready::add)
        val order = mutableListOf<String>()
        while (ready.isNotEmpty()) {
            val node = ready.remove()
            order += node
            outgoing.getValue(node).forEach { target ->
                val next = indegree.getValue(target) - 1
                indegree[target] = next
                if (next == 0) ready += target
            }
        }
        return order
    }

    private fun error(
        code: String,
        message: String,
        nodeId: String? = null,
        edgeId: String? = null,
        port: String? = null
    ) = AgentDagDiagnostic(
        AgentDagDiagnosticSeverity.ERROR, code, message, nodeId, edgeId, port
    )

    private fun warning(
        code: String,
        message: String,
        nodeId: String? = null,
        edgeId: String? = null,
        port: String? = null
    ) = AgentDagDiagnostic(
        AgentDagDiagnosticSeverity.WARNING, code, message, nodeId, edgeId, port
    )
}
