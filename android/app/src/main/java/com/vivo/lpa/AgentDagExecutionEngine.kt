package com.vivo.lpa

/** Values crossing DAG data edges. File paths remain encapsulated behind artifact IDs. */
sealed interface AgentDagRuntimeValue {
    val kind: AgentDataKind
    val itemCount: Int get() = 1

    data class AssetSet(
        val assetIds: List<String>,
        val stateFingerprint: String
    ) : AgentDagRuntimeValue {
        override val kind = AgentDataKind.ASSET_SET
        override val itemCount = assetIds.size
    }

    data class Artifact(
        val artifactId: String,
        override val kind: AgentDataKind
    ) : AgentDagRuntimeValue

    data class Layout(val version: Int) : AgentDagRuntimeValue {
        override val kind = AgentDataKind.LAYOUT
    }

    data class ContentNode(
        val graphId: String,
        val graphVersion: Int,
        val nodeId: String,
        override val kind: AgentDataKind
    ) : AgentDagRuntimeValue

    data class ScopeCertificate(
        val certificateId: String,
        val stateFingerprint: String,
        val certificateFingerprint: String
    ) : AgentDagRuntimeValue {
        override val kind = AgentDataKind.SCOPE_CERTIFICATE
    }

    data class Json(val value: String) : AgentDagRuntimeValue {
        override val kind = AgentDataKind.JSON
    }

    data class PublishedUri(val uri: String) : AgentDagRuntimeValue {
        override val kind = AgentDataKind.PUBLISHED_URI
    }
}

data class AgentDagValueKey(val nodeId: String, val outputPort: String)

data class AgentDagNodeInputs(val values: Map<String, List<AgentDagRuntimeValue>>) {
    fun single(port: String): AgentDagRuntimeValue = requireNotNull(values[port]?.singleOrNull()) {
        "input $port does not contain exactly one value"
    }
}

data class AgentDagNodeExecutionResult(
    val outputs: Map<String, List<AgentDagRuntimeValue>>,
    val message: String = ""
)

fun interface AgentDagNodeExecutor {
    fun execute(node: AgentDagNode, inputs: AgentDagNodeInputs): AgentDagNodeExecutionResult
}

data class AgentDagExecutionState(
    val runState: AgentDagRunState,
    val values: Map<AgentDagValueKey, List<AgentDagRuntimeValue>> = emptyMap(),
    val executedNodeIds: List<String> = emptyList()
)

/**
 * Backend-independent execution core. Tests use a fake executor; the future Android bridge will
 * adapt one node at a time to MobileToolExecutor only after differential gates pass.
 */
object AgentDagExecutionEngine {
    fun initialize(
        compilation: AgentDagCompilation,
        resumeValues: Map<AgentDagValueKey, List<AgentDagRuntimeValue>> = emptyMap(),
        timestampMs: Long = System.currentTimeMillis()
    ): AgentDagExecutionState {
        require(compilation.executable) { "DAG must be valid and complete before execution" }
        return AgentDagExecutionState(
            AgentDagScheduler.initialize(compilation, timestampMs),
            resumeValues
        )
    }

    fun executeWave(
        state: AgentDagExecutionState,
        executor: AgentDagNodeExecutor,
        budget: AgentDagResourceBudget = AgentDagResourceBudget(),
        timestampMs: Long = System.currentTimeMillis()
    ): AgentDagExecutionState {
        if (state.runState.status != AgentDagRunStatus.RUNNING) return state
        val wave = AgentDagScheduler.scheduleWave(state.runState, budget)
        if (wave.isEmpty()) {
            return state.copy(runState = AgentDagScheduler.failGraph(
                state.runState,
                "DAG has no schedulable node but is not complete",
                timestampMs
            ))
        }
        var current = state
        wave.forEach { nodeId ->
            if (current.runState.status != AgentDagRunStatus.RUNNING) return@forEach
            val node = current.runState.graph.nodes.getValue(nodeId)
            current = current.copy(
                runState = AgentDagScheduler.startNode(current.runState, nodeId, timestampMs)
            )
            try {
                val inputs = resolveInputs(current, node)
                val result = executor.execute(node, inputs)
                validateOutputs(node, result.outputs)
                val values = current.values.toMutableMap()
                result.outputs.forEach { (port, outputValues) ->
                    values[AgentDagValueKey(nodeId, port)] = outputValues
                }
                current = current.copy(
                    runState = AgentDagScheduler.completeNode(current.runState, nodeId, timestampMs),
                    values = values,
                    executedNodeIds = current.executedNodeIds + nodeId
                )
            } catch (error: Throwable) {
                current = current.copy(
                    runState = AgentDagScheduler.failNode(
                        current.runState,
                        nodeId,
                        error.message ?: error::class.java.simpleName,
                        timestampMs
                    )
                )
            }
        }
        return current
    }

    fun runToTerminal(
        compilation: AgentDagCompilation,
        executor: AgentDagNodeExecutor,
        budget: AgentDagResourceBudget = AgentDagResourceBudget(),
        maxWaves: Int = AgentDagValidator.MAX_NODES,
        timestampMs: Long = System.currentTimeMillis()
    ): AgentDagExecutionState {
        require(maxWaves > 0) { "maxWaves must be positive" }
        var state = initialize(compilation, timestampMs = timestampMs)
        var waves = 0
        while (state.runState.status == AgentDagRunStatus.RUNNING && waves < maxWaves) {
            state = executeWave(state, executor, budget, timestampMs)
            waves += 1
        }
        if (state.runState.status == AgentDagRunStatus.RUNNING) {
            state = state.copy(runState = AgentDagScheduler.failGraph(
                state.runState,
                "DAG exceeded wave limit $maxWaves",
                timestampMs
            ))
        }
        return state
    }

    private fun resolveInputs(
        state: AgentDagExecutionState,
        node: AgentDagNode
    ): AgentDagNodeInputs {
        val graph = state.runState.graph
        val result = linkedMapOf<String, MutableList<AgentDagRuntimeValue>>()
        graph.externalBindings.filter { it.nodeId == node.nodeId }.forEach { binding ->
            result.getOrPut(binding.inputPort) { mutableListOf() } += binding.value.materialize()
        }
        graph.edges.values.filter {
            it.kind == AgentDagEdgeKind.DATA && it.toNodeId == node.nodeId
        }.forEach { edge ->
            val source = state.values[AgentDagValueKey(edge.fromNodeId, requireNotNull(edge.fromPort))]
                ?: throw IllegalStateException("missing materialized value for ${edge.fromNodeId}.${edge.fromPort}")
            result.getOrPut(requireNotNull(edge.toPort)) { mutableListOf() } += source
        }
        node.inputPorts.forEach { port ->
            val values = result[port.name].orEmpty()
            if (port.required && values.isEmpty()) {
                throw IllegalStateException("missing runtime input ${node.nodeId}.${port.name}")
            }
            if (!port.variadic && values.size > 1) {
                throw IllegalStateException("multiple runtime values for ${node.nodeId}.${port.name}")
            }
            values.forEach { value ->
                require(value.kind in port.kinds) {
                    "runtime value ${value.kind} cannot feed ${node.nodeId}.${port.name}"
                }
                require(value.itemCount in port.minItems..port.maxItems) {
                    "runtime value count ${value.itemCount} violates ${node.nodeId}.${port.name} " +
                        "${port.minItems}..${port.maxItems}"
                }
            }
        }
        return AgentDagNodeInputs(result.mapValues { it.value.toList() })
    }

    private fun validateOutputs(
        node: AgentDagNode,
        outputs: Map<String, List<AgentDagRuntimeValue>>
    ) {
        (outputs.keys - node.outputPorts.map { it.name }.toSet()).forEach { name ->
            throw IllegalStateException("${node.nodeId} produced undeclared output $name")
        }
        node.outputPorts.forEach { port ->
            val values = outputs[port.name].orEmpty()
            if (port.required && values.isEmpty()) {
                throw IllegalStateException("${node.nodeId} did not produce ${port.name}")
            }
            if (!port.variadic && values.size > 1) {
                throw IllegalStateException("${node.nodeId}.${port.name} produced multiple values")
            }
            values.forEach { value ->
                require(value.kind in port.kinds) {
                    "${node.nodeId}.${port.name} produced ${value.kind}; expected ${port.kinds}"
                }
                require(value.itemCount in port.minItems..port.maxItems) {
                    "${node.nodeId}.${port.name} produced ${value.itemCount} items; expected " +
                        "${port.minItems}..${port.maxItems}"
                }
            }
        }
    }

    private fun AgentDagExternalValue.materialize(): AgentDagRuntimeValue = when (this) {
        is AgentDagExternalValue.AssetSet -> AgentDagRuntimeValue.AssetSet(assetIds, stateFingerprint)
        is AgentDagExternalValue.Artifact -> AgentDagRuntimeValue.Artifact(artifactId, kind)
        is AgentDagExternalValue.Layout -> AgentDagRuntimeValue.Layout(version)
        is AgentDagExternalValue.ContentNode -> AgentDagRuntimeValue.ContentNode(
            contentGraphId,
            contentGraphVersion,
            contentNodeId,
            kind
        )
        is AgentDagExternalValue.ScopeCertificate -> AgentDagRuntimeValue.ScopeCertificate(
            certificateId,
            stateFingerprint,
            certificateFingerprint
        )
    }
}
