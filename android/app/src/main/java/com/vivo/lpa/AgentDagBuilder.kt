package com.vivo.lpa

/** Mutable construction helper used inside deterministic compilers, never exposed to the model. */
class AgentDagBuilder(
    private val graphId: String,
    private val graphVersion: Int,
    private val goalId: String,
    private val stateFingerprint: String,
    private val parentGraphVersion: Int? = null,
    private val createdFromPlanId: String? = null,
    private val revisionReason: String = "initial"
) {
    private val nodes = linkedMapOf<String, AgentDagNode>()
    private val edges = linkedMapOf<String, AgentDagEdge>()
    private val externalBindings = mutableListOf<AgentDagExternalBinding>()
    private var generatedEdgeIndex = 0

    fun addNode(node: AgentDagNode): AgentDagBuilder = apply {
        require(node.nodeId !in nodes) { "duplicate DAG node ${node.nodeId}" }
        nodes[node.nodeId] = node
    }

    fun addAction(
        nodeId: String,
        operationId: String,
        spec: MobileToolSpec,
        parametersJson: String = "{}",
        outputPorts: List<AgentPortSpec> = spec.contract.outputPorts,
        status: AgentDagNodeStatus = AgentDagNodeStatus.PLANNED
    ): AgentDagBuilder = addNode(AgentDagNode(
        nodeId = nodeId,
        operationId = operationId,
        actionId = spec.id,
        family = spec.family,
        phase = spec.contract.phase,
        contractVersion = spec.contract.version,
        inputPorts = spec.contract.inputPorts,
        outputPorts = outputPorts,
        parameterSpecs = spec.contract.parameters,
        allowUnknownParameters = spec.contract.allowUnknownParameters,
        parametersJson = parametersJson,
        resources = spec.contract.resources,
        effects = spec.contract.effects,
        destructive = spec.destructive,
        idempotent = spec.idempotent,
        status = status
    ))

    fun bindExternal(
        nodeId: String,
        inputPort: String,
        value: AgentDagExternalValue
    ): AgentDagBuilder = apply {
        externalBindings += AgentDagExternalBinding(nodeId, inputPort, value)
    }

    fun connectData(
        fromNodeId: String,
        fromPort: String,
        toNodeId: String,
        toPort: String,
        edgeId: String = nextEdgeId("data")
    ): AgentDagBuilder = addEdge(AgentDagEdge(
        edgeId,
        AgentDagEdgeKind.DATA,
        fromNodeId,
        toNodeId,
        fromPort,
        toPort
    ))

    fun connectControl(
        fromNodeId: String,
        toNodeId: String,
        edgeId: String = nextEdgeId("control")
    ): AgentDagBuilder = addEdge(AgentDagEdge(
        edgeId,
        AgentDagEdgeKind.CONTROL,
        fromNodeId,
        toNodeId
    ))

    fun connectConstraint(
        fromNodeId: String,
        toNodeId: String,
        edgeId: String = nextEdgeId("constraint")
    ): AgentDagBuilder = addEdge(AgentDagEdge(
        edgeId,
        AgentDagEdgeKind.CONSTRAINT,
        fromNodeId,
        toNodeId
    ))

    fun build(): AgentPlanGraph = AgentPlanGraph(
        graphId = graphId,
        graphVersion = graphVersion,
        parentGraphVersion = parentGraphVersion,
        goalId = goalId,
        stateFingerprint = stateFingerprint,
        toolSpecVersion = MobileToolRegistry.SPEC_VERSION,
        nodes = nodes.toMap(),
        edges = edges.toMap(),
        externalBindings = externalBindings.toList(),
        createdFromPlanId = createdFromPlanId,
        revisionReason = revisionReason
    )

    fun compile(goal: AgentGoalIr): AgentDagCompilation {
        require(goal.goalId == goalId) { "goal ${goal.goalId} does not match graph goal $goalId" }
        val graph = build()
        return AgentDagCompilation(goal, graph, AgentDagValidator.validate(graph))
    }

    private fun addEdge(edge: AgentDagEdge): AgentDagBuilder = apply {
        require(edge.edgeId !in edges) { "duplicate DAG edge ${edge.edgeId}" }
        edges[edge.edgeId] = edge
    }

    private fun nextEdgeId(prefix: String): String = "$prefix-${generatedEdgeIndex++}"
}
