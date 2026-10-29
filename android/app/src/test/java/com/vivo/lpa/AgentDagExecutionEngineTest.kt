package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDagExecutionEngineTest {
    private fun port(name: String, kind: AgentDataKind) = AgentPortSpec(name, setOf(kind))

    private fun node(
        id: String,
        phase: AgentActionPhase = AgentActionPhase.TRANSFORM,
        inputs: List<AgentPortSpec> = emptyList(),
        outputs: List<AgentPortSpec> = emptyList()
    ) = AgentDagNode(
        nodeId = id,
        operationId = "op-$id",
        actionId = id,
        family = ToolFamily.ANALYZE,
        phase = phase,
        contractVersion = 1,
        inputPorts = inputs,
        outputPorts = outputs
    )

    private fun compilation(
        nodes: List<AgentDagNode>,
        edges: List<AgentDagEdge>
    ): AgentDagCompilation {
        val goal = AgentGoalIr(
            "goal-execution",
            userGoal = "test execution",
            stateFingerprint = "state",
            operations = nodes.map {
                AgentGoalOperation(it.operationId, AgentGoalOperationKind.ANALYZE, AgentGoalScope.CurrentAssets)
            }
        )
        val graph = AgentPlanGraph(
            "graph-execution",
            graphVersion = 1,
            goalId = goal.goalId,
            stateFingerprint = goal.stateFingerprint,
            toolSpecVersion = 1,
            nodes = nodes.associateBy { it.nodeId },
            edges = edges.associateBy { it.edgeId }
        )
        return AgentDagCompilation(goal, graph, AgentDagValidator.validate(graph))
    }

    @Test
    fun executesFanOutFanInAndCommitWithMaterializedValues() {
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
        val compiled = compilation(
            listOf(decode, segment, inpaint, publish),
            listOf(
                AgentDagEdge("decode-inpaint", AgentDagEdgeKind.DATA, "decode", "inpaint", "video", "video"),
                AgentDagEdge("segment-inpaint", AgentDagEdgeKind.DATA, "segment", "inpaint", "mask", "mask"),
                AgentDagEdge("inpaint-publish", AgentDagEdgeKind.DATA, "inpaint", "publish", "video", "video")
            )
        )
        val executor = AgentDagNodeExecutor { current, inputs ->
            when (current.actionId) {
                "decode" -> AgentDagNodeExecutionResult(mapOf(
                    "video" to listOf(AgentDagRuntimeValue.Artifact("decoded", AgentDataKind.VIDEO))
                ))
                "segment" -> AgentDagNodeExecutionResult(mapOf(
                    "mask" to listOf(AgentDagRuntimeValue.Artifact("mask", AgentDataKind.MASK_TRACK))
                ))
                "inpaint" -> {
                    assertEquals(AgentDataKind.VIDEO, inputs.single("video").kind)
                    assertEquals(AgentDataKind.MASK_TRACK, inputs.single("mask").kind)
                    AgentDagNodeExecutionResult(mapOf(
                        "video" to listOf(AgentDagRuntimeValue.Artifact("edited", AgentDataKind.VIDEO))
                    ))
                }
                "publish" -> {
                    assertEquals("edited", (inputs.single("video") as AgentDagRuntimeValue.Artifact).artifactId)
                    AgentDagNodeExecutionResult(mapOf(
                        "uri" to listOf(AgentDagRuntimeValue.PublishedUri("content://result/1"))
                    ))
                }
                else -> error("unexpected ${current.actionId}")
            }
        }

        val final = AgentDagExecutionEngine.runToTerminal(
            compiled,
            executor,
            AgentDagResourceBudget(maxParallelNodes = 2),
            timestampMs = 1
        )

        assertEquals(AgentDagRunStatus.FINISHED, final.runState.status)
        assertEquals(listOf("decode", "segment", "inpaint", "publish"), final.executedNodeIds)
        assertEquals(
            AgentDataKind.PUBLISHED_URI,
            final.values.getValue(AgentDagValueKey("publish", "uri")).single().kind
        )
        assertTrue(final.runState.events.any { it.kind == AgentDagEventKind.NODE_COMMITTED })
        assertTrue(final.runState.events.any { it.kind == AgentDagEventKind.GRAPH_FINISHED })
    }

    @Test
    fun wrongOutputTypeFailsNodeAndGraph() {
        val transform = node(
            "transform",
            outputs = listOf(port("video", AgentDataKind.VIDEO))
        )
        val compiled = compilation(listOf(transform), emptyList())
        val executor = AgentDagNodeExecutor { _, _ ->
            AgentDagNodeExecutionResult(mapOf(
                "video" to listOf(AgentDagRuntimeValue.Artifact("wrong", AgentDataKind.IMAGE))
            ))
        }

        val final = AgentDagExecutionEngine.runToTerminal(compiled, executor, timestampMs = 1)

        assertEquals(AgentDagRunStatus.FAILED, final.runState.status)
        assertEquals(AgentDagNodeStatus.FAILED, final.runState.nodeStatuses["transform"])
        assertTrue(final.runState.lastError.orEmpty().contains("produced IMAGE"))
    }

    @Test
    fun missingRequiredOutputFailsClosed() {
        val transform = node(
            "transform",
            outputs = listOf(port("artifact", AgentDataKind.VIDEO))
        )
        val final = AgentDagExecutionEngine.runToTerminal(
            compilation(listOf(transform), emptyList()),
            AgentDagNodeExecutor { _, _ -> AgentDagNodeExecutionResult(emptyMap()) },
            timestampMs = 1
        )

        assertEquals(AgentDagRunStatus.FAILED, final.runState.status)
        assertTrue(final.runState.lastError.orEmpty().contains("did not produce artifact"))
    }
}
