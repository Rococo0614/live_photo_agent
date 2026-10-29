package com.vivo.lpa

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDagCompilerTest {
    @Test
    fun linearRuntimePlanLowersToValidDagWithArtifactDependency() {
        val route = SemanticRoute(
            "找三张猫做三拼并发布",
            listOf(
                SemanticRouteStep("route-retrieve", ToolFamily.RETRIEVE, "猫", 3),
                SemanticRouteStep("route-compose", ToolFamily.COMPOSE, "三拼"),
                SemanticRouteStep("route-publish", ToolFamily.PUBLISH)
            )
        )
        val search = RuntimeStep(
            "search", ToolFamily.RETRIEVE, "search_assets",
            JSONObject().put("query", "猫").put("k", 3)
        )
        val compose = RuntimeStep(
            "compose", ToolFamily.COMPOSE, "template_collage", JSONObject(),
            listOf(AgentInputRef.AssetSet(listOf("cat-1", "cat-2", "cat-3"))),
            RuntimeStepStatus.COMPLETED
        )
        val publish = RuntimeStep(
            "publish", ToolFamily.PUBLISH, "publish_result", JSONObject(),
            listOf(AgentInputRef.Artifact("artifact-compose"))
        )
        val plan = RuntimePlan("plan-1", 1, route = route, steps = listOf(search, compose, publish))
        val artifact = AgentArtifact(
            "artifact-compose", AgentArtifactKind.VIDEO, "compose", validated = true
        )
        val state = AgentRuntimeState(
            sessionId = null,
            turnId = "turn-1",
            originalQuery = route.userGoal,
            artifacts = mapOf(artifact.artifactId to artifact),
            plan = plan
        )

        val compilation = AgentDagCompiler.compileShadow(plan, state)

        assertTrue(compilation.validation.diagnostics.toString(), compilation.validation.valid)
        assertTrue(compilation.complete)
        assertTrue(compilation.executable)
        assertTrue(compilation.differential?.issues.toString(), compilation.differential?.matches == true)
        assertEquals(3, compilation.graph.nodes.size)
        assertTrue(compilation.graph.edges.values.any {
            it.kind == AgentDagEdgeKind.DATA &&
                it.fromNodeId == "node-compose" && it.toNodeId == "node-publish"
        })
        assertEquals(
            listOf("node-search", "node-compose", "node-publish"),
            compilation.validation.topologicalOrder
        )
    }

    @Test
    fun semanticRouteWithoutBoundActionsIsValidButIncomplete() {
        val route = SemanticRoute(
            "找猫并发布",
            listOf(
                SemanticRouteStep("route-retrieve", ToolFamily.RETRIEVE, "猫", 1),
                SemanticRouteStep("route-publish", ToolFamily.PUBLISH)
            )
        )
        val plan = RuntimePlan("plan-partial", 1, route = route)
        val state = AgentRuntimeState(null, "turn-partial", route.userGoal, plan = plan)

        val compilation = AgentDagCompiler.compileShadow(plan, state)

        assertTrue(compilation.validation.valid)
        assertTrue(compilation.graph.nodes.isEmpty())
        assertTrue(!compilation.complete)
        assertTrue(!compilation.executable)
    }

    @Test
    fun goalIrContainsNoConcreteToolIds() {
        val route = SemanticRoute(
            "把当前视频倒放",
            listOf(
                SemanticRouteStep("route-temporal", ToolFamily.TEMPORAL, "倒放"),
                SemanticRouteStep("route-publish", ToolFamily.PUBLISH)
            )
        )
        val state = AgentRuntimeState(null, "turn-2", route.userGoal)

        val goal = AgentGoalIrAdapter.from(route, state, "fingerprint")

        assertEquals(
            listOf(AgentGoalOperationKind.TEMPORAL_EDIT, AgentGoalOperationKind.PUBLISH),
            goal.operations.map { it.kind }
        )
        assertTrue(goal.operations.none { it.instruction.contains("video_reverse") })
    }

    @Test
    fun compilerRejectsMissingRequiredToolParameter() {
        val route = SemanticRoute(
            "找猫",
            listOf(SemanticRouteStep("route-retrieve", ToolFamily.RETRIEVE, "猫", 1))
        )
        val step = RuntimeStep(
            "search", ToolFamily.RETRIEVE, "search_assets", JSONObject()
        )
        val plan = RuntimePlan("plan-invalid", 1, route = route, steps = listOf(step))
        val state = AgentRuntimeState(null, "turn-invalid", route.userGoal, plan = plan)

        val compilation = AgentDagCompiler.compileShadow(plan, state)

        assertTrue(!compilation.validation.valid)
        assertTrue(compilation.validation.diagnostics.any { it.code == "missing_parameter" })
    }
}
