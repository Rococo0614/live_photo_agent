package com.vivo.lpa

enum class AgentDagRunStatus {
    PARTIAL,
    RUNNING,
    FINISHED,
    FAILED,
    CANCELLED
}

enum class AgentDagEventKind {
    GRAPH_ACCEPTED,
    NODE_STARTED,
    NODE_VALIDATED,
    NODE_COMMITTED,
    NODE_FAILED,
    GRAPH_FINISHED,
    GRAPH_FAILED,
    GRAPH_CANCELLED
}

data class AgentDagEvent(
    val sequence: Long,
    val kind: AgentDagEventKind,
    val graphId: String,
    val graphVersion: Int,
    val nodeId: String? = null,
    val message: String = "",
    val timestampMs: Long
)

data class AgentDagResourceBudget(
    val maxParallelNodes: Int = 1,
    val maxEstimatedMemoryMb: Int = Int.MAX_VALUE,
    val maxThermalWeight: Int = Int.MAX_VALUE
) {
    init {
        require(maxParallelNodes > 0) { "maxParallelNodes must be positive" }
        require(maxEstimatedMemoryMb > 0) { "maxEstimatedMemoryMb must be positive" }
        require(maxThermalWeight >= 0) { "maxThermalWeight cannot be negative" }
    }
}

data class AgentDagRunState(
    val compilation: AgentDagCompilation,
    val nodeStatuses: Map<String, AgentDagNodeStatus>,
    val status: AgentDagRunStatus,
    val events: List<AgentDagEvent>,
    val lastError: String? = null
) {
    val graph: AgentPlanGraph get() = compilation.graph
}

data class AgentDagSchedulePreview(
    val waves: List<List<String>>,
    val blockedNodeIds: List<String>,
    val completeGraph: Boolean,
    val validGraph: Boolean
)

/**
 * Deterministic DAG state machine. It never invokes an executor and never chooses user semantics.
 * Runtime v3 will later wrap each scheduled node around [MobileToolExecutor].
 */
object AgentDagScheduler {
    private val successful = setOf(AgentDagNodeStatus.VALIDATED, AgentDagNodeStatus.COMMITTED)

    fun initialize(
        compilation: AgentDagCompilation,
        timestampMs: Long = System.currentTimeMillis()
    ): AgentDagRunState {
        require(compilation.validation.valid) {
            "cannot schedule invalid DAG: ${compilation.validation.diagnostics}"
        }
        val status = if (compilation.complete) AgentDagRunStatus.RUNNING else AgentDagRunStatus.PARTIAL
        return AgentDagRunState(
            compilation = compilation,
            nodeStatuses = compilation.graph.nodes.values.associate { node ->
                node.nodeId to when (node.status) {
                    AgentDagNodeStatus.RUNNING -> AgentDagNodeStatus.PLANNED
                    else -> node.status
                }
            },
            status = status,
            events = listOf(AgentDagEvent(
                1,
                AgentDagEventKind.GRAPH_ACCEPTED,
                compilation.graph.graphId,
                compilation.graph.graphVersion,
                message = if (compilation.complete) "ready" else "partial",
                timestampMs = timestampMs
            ))
        )
    }

    fun readyNodeIds(state: AgentDagRunState): List<String> {
        if (state.status !in setOf(AgentDagRunStatus.RUNNING, AgentDagRunStatus.PARTIAL)) return emptyList()
        val graph = state.graph
        return state.compilation.validation.topologicalOrder.filter { nodeId ->
            val node = graph.nodes.getValue(nodeId)
            state.nodeStatuses[nodeId] == AgentDagNodeStatus.PLANNED &&
                graph.edges.values.filter { it.toNodeId == nodeId }.all { edge ->
                    state.nodeStatuses[edge.fromNodeId] in successful
                } && (node.phase != AgentActionPhase.COMMIT || graph.nodes.values
                    .filter { it.phase != AgentActionPhase.COMMIT }
                    .all { state.nodeStatuses[it.nodeId] in successful })
        }
    }

    fun scheduleWave(
        state: AgentDagRunState,
        budget: AgentDagResourceBudget = AgentDagResourceBudget()
    ): List<String> {
        val selected = mutableListOf<String>()
        val claimedExclusive = mutableSetOf<AgentResourceKind>()
        var memoryMb = 0
        var thermal = 0
        readyNodeIds(state).forEach { nodeId ->
            if (selected.size >= budget.maxParallelNodes) return@forEach
            val node = state.graph.nodes.getValue(nodeId)
            val exclusive = node.resources.filter { it.exclusive }.map { it.resource }.toSet()
            val nodeMemory = node.resources.mapNotNull { it.estimatedPeakMemoryMb }.maxOrNull() ?: 0
            val nodeThermal = node.resources.sumOf { it.thermalWeight }
            if (exclusive.any { it in claimedExclusive }) return@forEach
            if (memoryMb + nodeMemory > budget.maxEstimatedMemoryMb) return@forEach
            if (thermal + nodeThermal > budget.maxThermalWeight) return@forEach
            selected += nodeId
            claimedExclusive += exclusive
            memoryMb += nodeMemory
            thermal += nodeThermal
        }
        return selected
    }

    fun startNode(
        state: AgentDagRunState,
        nodeId: String,
        timestampMs: Long = System.currentTimeMillis()
    ): AgentDagRunState {
        require(state.status == AgentDagRunStatus.RUNNING) { "graph is not runnable: ${state.status}" }
        require(nodeId in readyNodeIds(state)) { "node $nodeId is not ready" }
        return state.transition(
            nodeId,
            AgentDagNodeStatus.RUNNING,
            AgentDagEventKind.NODE_STARTED,
            timestampMs
        )
    }

    fun completeNode(
        state: AgentDagRunState,
        nodeId: String,
        timestampMs: Long = System.currentTimeMillis()
    ): AgentDagRunState {
        require(state.nodeStatuses[nodeId] == AgentDagNodeStatus.RUNNING) {
            "node $nodeId is not running"
        }
        val node = state.graph.nodes.getValue(nodeId)
        val committed = node.phase == AgentActionPhase.COMMIT
        var updated = state.transition(
            nodeId,
            if (committed) AgentDagNodeStatus.COMMITTED else AgentDagNodeStatus.VALIDATED,
            if (committed) AgentDagEventKind.NODE_COMMITTED else AgentDagEventKind.NODE_VALIDATED,
            timestampMs
        )
        if (updated.nodeStatuses.values.all { it in successful }) {
            updated = updated.copy(
                status = AgentDagRunStatus.FINISHED,
                events = updated.events + updated.event(
                    AgentDagEventKind.GRAPH_FINISHED,
                    null,
                    "all nodes completed",
                    timestampMs
                )
            )
        }
        return updated
    }

    fun failNode(
        state: AgentDagRunState,
        nodeId: String,
        message: String,
        timestampMs: Long = System.currentTimeMillis()
    ): AgentDagRunState {
        require(state.nodeStatuses[nodeId] in setOf(
            AgentDagNodeStatus.PLANNED,
            AgentDagNodeStatus.RUNNING
        )) { "node $nodeId cannot fail from ${state.nodeStatuses[nodeId]}" }
        return state.transition(
            nodeId,
            AgentDagNodeStatus.FAILED,
            AgentDagEventKind.NODE_FAILED,
            timestampMs,
            message
        ).copy(status = AgentDagRunStatus.FAILED, lastError = message)
    }

    fun cancel(
        state: AgentDagRunState,
        message: String = "cancelled",
        timestampMs: Long = System.currentTimeMillis()
    ): AgentDagRunState {
        if (state.status in setOf(
                AgentDagRunStatus.FINISHED,
                AgentDagRunStatus.FAILED,
                AgentDagRunStatus.CANCELLED
            )
        ) return state
        val statuses = state.nodeStatuses.mapValues { (_, status) ->
            if (status in setOf(AgentDagNodeStatus.PLANNED, AgentDagNodeStatus.RUNNING)) {
                AgentDagNodeStatus.CANCELLED
            } else status
        }
        return state.copy(
            nodeStatuses = statuses,
            status = AgentDagRunStatus.CANCELLED,
            events = state.events + state.event(
                AgentDagEventKind.GRAPH_CANCELLED,
                null,
                message,
                timestampMs
            )
        )
    }

    fun failGraph(
        state: AgentDagRunState,
        message: String,
        timestampMs: Long = System.currentTimeMillis()
    ): AgentDagRunState {
        if (state.status in setOf(
                AgentDagRunStatus.FINISHED,
                AgentDagRunStatus.FAILED,
                AgentDagRunStatus.CANCELLED
            )
        ) return state
        return state.copy(
            status = AgentDagRunStatus.FAILED,
            lastError = message,
            events = state.events + state.event(
                AgentDagEventKind.GRAPH_FAILED,
                null,
                message,
                timestampMs
            )
        )
    }

    fun preview(
        compilation: AgentDagCompilation,
        budget: AgentDagResourceBudget = AgentDagResourceBudget(maxParallelNodes = 4)
    ): AgentDagSchedulePreview {
        if (!compilation.validation.valid) {
            return AgentDagSchedulePreview(emptyList(), compilation.graph.nodes.keys.toList(), compilation.complete, false)
        }
        var state = initialize(compilation, 0)
        if (state.status == AgentDagRunStatus.PARTIAL) {
            // Preview only: bound nodes may be visualized, but startNode still refuses PARTIAL in
            // the real state machine and the result remains completeGraph=false.
            state = state.copy(status = AgentDagRunStatus.RUNNING)
        }
        val waves = mutableListOf<List<String>>()
        while (state.status == AgentDagRunStatus.RUNNING) {
            val wave = scheduleWave(state, budget)
            if (wave.isEmpty()) break
            waves += wave
            wave.forEach { nodeId ->
                state = startNode(state, nodeId, 0)
            }
            wave.forEach { nodeId ->
                state = completeNode(state, nodeId, 0)
            }
        }
        val blocked = state.nodeStatuses.filterValues { it == AgentDagNodeStatus.PLANNED }.keys.toList()
        return AgentDagSchedulePreview(waves, blocked, compilation.complete, true)
    }

    private fun AgentDagRunState.transition(
        nodeId: String,
        nodeStatus: AgentDagNodeStatus,
        eventKind: AgentDagEventKind,
        timestampMs: Long,
        message: String = ""
    ): AgentDagRunState = copy(
        nodeStatuses = nodeStatuses + (nodeId to nodeStatus),
        events = events + event(eventKind, nodeId, message, timestampMs)
    )

    private fun AgentDagRunState.event(
        kind: AgentDagEventKind,
        nodeId: String?,
        message: String,
        timestampMs: Long
    ) = AgentDagEvent(
        sequence = (events.lastOrNull()?.sequence ?: 0) + 1,
        kind = kind,
        graphId = graph.graphId,
        graphVersion = graph.graphVersion,
        nodeId = nodeId,
        message = message,
        timestampMs = timestampMs
    )
}
