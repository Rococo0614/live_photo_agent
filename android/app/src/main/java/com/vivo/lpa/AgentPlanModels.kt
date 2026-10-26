package com.vivo.lpa

import org.json.JSONObject

enum class RuntimeStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/** A model-authored semantic operation. It deliberately does not carry an SDK implementation. */
data class SemanticRouteStep(
    val stepId: String,
    val family: ToolFamily,
    val instruction: String = "",
    val requestedCount: Int? = null,
    val requiredMedia: RuntimeMediaKind? = null
)

data class SemanticRoute(
    val userGoal: String,
    val steps: List<SemanticRouteStep>,
    val reply: String? = null,
    val clarification: String? = null
)

data class RoutePlanningResult(
    val route: SemanticRoute,
    val trace: BrainTrace
)

data class BoundToolResult(
    val call: MobileToolCall,
    val trace: BrainTrace
)

data class RetrievalSpec(
    val query: String,
    val requestedCount: Int,
    val requiredMedia: RuntimeMediaKind? = null
)

data class RetrievalPlanningResult(
    val spec: RetrievalSpec,
    val trace: BrainTrace
)

/** A concrete call bound against one snapshot of runtime facts and the visible tool catalogue. */
data class RuntimeStep(
    val stepId: String,
    val family: ToolFamily,
    val tool: String,
    val arguments: JSONObject = JSONObject(),
    val inputs: List<AgentInputRef> = emptyList(),
    val status: RuntimeStepStatus = RuntimeStepStatus.PENDING,
    val idempotencyKey: String = ""
)

data class RuntimePlan(
    val planId: String,
    val version: Int,
    val parentVersion: Int? = null,
    val revisionReason: String = "initial",
    val route: SemanticRoute,
    val steps: List<RuntimeStep> = emptyList()
) {
    val completedPrefix: List<RuntimeStep>
        get() = steps.takeWhile { it.status == RuntimeStepStatus.COMPLETED }

    val pendingSuffix: List<RuntimeStep>
        get() = steps.drop(completedPrefix.size)
}

sealed interface AgentDecision {
    data class Call(val step: RuntimeStep) : AgentDecision
    data class Revise(val routeSteps: List<SemanticRouteStep>, val reason: String) : AgentDecision
    data class Ask(val message: String) : AgentDecision
    data class Finish(val message: String = "") : AgentDecision
    data class Fail(val message: String) : AgentDecision
}
