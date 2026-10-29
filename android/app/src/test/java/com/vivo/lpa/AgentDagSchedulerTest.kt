package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDagSchedulerTest {
    private fun port(name: String, kind: AgentDataKind) = AgentPortSpec(name, setOf(kind))

    private fun node(
        id: String,
        phase: AgentActionPhase = AgentActionPhase.TRANSFORM,
        inputs: List<AgentPortSpec> = emptyList(),
        outputs: List<AgentPortSpec> = emptyList(),
        resources: Set<AgentResourceClaim> = emptySet()
    ) = AgentDagNode(
        nodeId = id,
        operationId = "op-$id",
        actionId = "action-$id",
        family = ToolFamily.ANALYZE,
        phase = phase,
        contractVersion = 1,
        inputPorts = inputs,
        outputPorts = outputs,
        resources = resources
    )

    private fun compilation(
        nodes: List<AgentDagNode>,
        edges: List<AgentDagEdge> = emptyList()
    ): AgentDagCompilation {
        val goal = AgentGoalIr(
            goalId = "goal-scheduler",
            userGoal = "test",
            stateFingerprint = "state",
            operations = nodes.map { node ->
                AgentGoalOperation(
                    node.operationId,
                    AgentGoalOperationKind.ANALYZE,
                    AgentGoalScope.CurrentAssets
                )
            }
        )
        val graph = AgentPlanGraph(
            graphId = "graph-scheduler",
            graphVersion = 1,
            goalId = goal.goalId,
            stateFingerprint = "state",
            toolSpecVersion = 1,
            nodes = nodes.associateBy { it.nodeId },
            edges = edges.associateBy { it.edgeId }
        )
        return AgentDagCompilation(goal, graph, AgentDagValidator.validate(graph))
    }

    @Test
    fun fanOutAndFanInScheduleInDependencyWaves() {
        val decode = node("decode", outputs = listOf(port("video", AgentDataKind.VIDEO)))
        val segment = node("segment", outputs = listOf(port("mask", AgentDataKind.MASK_TRACK)))
        val inpaint = node(
            "inpaint",
            inputs = listOf(port("video", AgentDataKind.VIDEO), port("mask", AgentDataKind.MASK_TRACK)),
            outputs = listOf(port("video", AgentDataKind.VIDEO))
        )
        val publish = node(
            "publish",
            AgentActionPhase.COMMIT,
            inputs = listOf(port("video", AgentDataKind.VIDEO)),
            outputs = listOf(port("uri", AgentDataKind.PUBLISHED_URI))
        )
        val edges = listOf(
            AgentDagEdge("decode-inpaint", AgentDagEdgeKind.DATA, "decode", "inpaint", "video", "video"),
            AgentDagEdge("segment-inpaint", AgentDagEdgeKind.DATA, "segment", "inpaint", "mask", "mask"),
            AgentDagEdge("inpaint-publish", AgentDagEdgeKind.DATA, "inpaint", "publish", "video", "video")
        )
        val compiled = compilation(listOf(decode, segment, inpaint, publish), edges)

        val preview = AgentDagScheduler.preview(
            compiled,
            AgentDagResourceBudget(maxParallelNodes = 2)
        )

        assertTrue(compiled.validation.diagnostics.toString(), compiled.executable)
        assertEquals(listOf("decode", "segment"), preview.waves[0])
        assertEquals(listOf("inpaint"), preview.waves[1])
        assertEquals(listOf("publish"), preview.waves[2])
        assertTrue(preview.blockedNodeIds.isEmpty())
    }

    @Test
    fun exclusiveResourcesDoNotShareWave() {
        val gpu = setOf(AgentResourceClaim(AgentResourceKind.GPU, exclusive = true))
        val first = node("first", resources = gpu)
        val second = node("second", resources = gpu)
        val state = AgentDagScheduler.initialize(compilation(listOf(first, second)), 0)

        val wave = AgentDagScheduler.scheduleWave(
            state,
            AgentDagResourceBudget(maxParallelNodes = 2)
        )

        assertEquals(1, wave.size)
    }

    @Test
    fun commitWaitsForEveryNonCommitNodeEvenWithoutExplicitEdge() {
        val transform = node("transform")
        val commit = node("commit", AgentActionPhase.COMMIT)
        var state = AgentDagScheduler.initialize(compilation(listOf(transform, commit)), 0)

        assertEquals(listOf("transform"), AgentDagScheduler.readyNodeIds(state))
        state = AgentDagScheduler.startNode(state, "transform", 1)
        state = AgentDagScheduler.completeNode(state, "transform", 2)
        assertEquals(listOf("commit"), AgentDagScheduler.readyNodeIds(state))
    }

    @Test
    fun failureBlocksDescendantsAndTerminatesRun() {
        val first = node("first")
        val second = node("second")
        val edge = AgentDagEdge("order", AgentDagEdgeKind.CONTROL, "first", "second")
        var state = AgentDagScheduler.initialize(compilation(listOf(first, second), listOf(edge)), 0)
        state = AgentDagScheduler.startNode(state, "first", 1)
        state = AgentDagScheduler.failNode(state, "first", "boom", 2)

        assertEquals(AgentDagRunStatus.FAILED, state.status)
        assertEquals(AgentDagNodeStatus.FAILED, state.nodeStatuses["first"])
        assertEquals(AgentDagNodeStatus.PLANNED, state.nodeStatuses["second"])
        assertTrue(AgentDagScheduler.readyNodeIds(state).isEmpty())
    }

    @Test
    fun partialGraphCanBePreviewedButNotStarted() {
        val node = node("bound")
        val compiled = compilation(listOf(node)).copy(
            goal = AgentGoalIr(
                "goal-scheduler",
                userGoal = "test",
                stateFingerprint = "state",
                operations = listOf(
                    AgentGoalOperation("op-bound", AgentGoalOperationKind.ANALYZE, AgentGoalScope.CurrentAssets),
                    AgentGoalOperation("op-unbound", AgentGoalOperationKind.PUBLISH, AgentGoalScope.CurrentAssets)
                )
            )
        )
        val state = AgentDagScheduler.initialize(compiled, 0)

        assertFalse(compiled.complete)
        assertEquals(AgentDagRunStatus.PARTIAL, state.status)
        assertThrows(IllegalArgumentException::class.java) {
            AgentDagScheduler.startNode(state, "bound", 1)
        }
        assertEquals(listOf(listOf("bound")), AgentDagScheduler.preview(compiled).waves)
    }
}
