package com.vivo.lpa

/** Stable value types shared by the content graph, action contracts and execution DAG. */
enum class AgentDataKind {
    ASSET_SET,
    IMAGE,
    VIDEO,
    LIVE_PHOTO,
    PALETTE,
    JSON,
    LAYOUT,
    MASK_TRACK,
    OBJECT_TRACK,
    REGION,
    MEDIA_ARTIFACT,
    PUBLISHED_URI
}

fun AgentArtifactKind.toDataKind(): AgentDataKind = when (this) {
    AgentArtifactKind.IMAGE -> AgentDataKind.IMAGE
    AgentArtifactKind.VIDEO -> AgentDataKind.VIDEO
    AgentArtifactKind.LIVE_PHOTO -> AgentDataKind.LIVE_PHOTO
    AgentArtifactKind.PALETTE -> AgentDataKind.PALETTE
    AgentArtifactKind.JSON -> AgentDataKind.JSON
    AgentArtifactKind.LAYOUT -> AgentDataKind.LAYOUT
}

enum class AgentActionPhase {
    OBSERVE,
    TRANSFORM,
    STATE_MUTATION,
    VALIDATE,
    COMMIT
}

enum class AgentResourceKind {
    CPU,
    GPU,
    NPU,
    STORAGE_IO,
    VIDEO_CODEC,
    VIDEO_EDITOR_SDK,
    IMAGE_SDK,
    LIVE_PHOTO_SDK,
    BGE,
    MEDIA_STORE
}

data class AgentResourceClaim(
    val resource: AgentResourceKind,
    val exclusive: Boolean = false,
    val estimatedPeakMemoryMb: Int? = null,
    val thermalWeight: Int = 0
)

data class AgentPortSpec(
    val name: String,
    val kinds: Set<AgentDataKind>,
    val required: Boolean = true,
    val variadic: Boolean = false,
    /** Cardinality inside a collection value such as ASSET_SET; distinct from binding count. */
    val minItems: Int = if (required) 1 else 0,
    val maxItems: Int = Int.MAX_VALUE
) {
    init {
        require(name.isNotBlank()) { "port name cannot be blank" }
        require(kinds.isNotEmpty()) { "port $name must accept at least one kind" }
        require(minItems >= 0 && maxItems >= minItems) { "port $name has invalid item cardinality" }
    }
}

enum class AgentParameterType {
    STRING,
    BOOLEAN,
    INTEGER,
    LONG,
    DECIMAL,
    STRING_LIST,
    OBJECT
}

data class AgentParameterSpec(
    val name: String,
    val type: AgentParameterType,
    val required: Boolean = false,
    val unit: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val enumValues: Set<String> = emptySet()
) {
    init {
        require(name.isNotBlank()) { "parameter name cannot be blank" }
        require(minimum == null || maximum == null || minimum <= maximum) {
            "parameter $name has an invalid range"
        }
    }
}

enum class AgentStateEffect {
    ADD_ASSETS,
    ADD_ARTIFACT,
    UPDATE_LAYOUT,
    REPLACE_ASSET,
    ADD_CONTENT,
    CHANGE_CONTENT,
    REMOVE_CONTENT,
    PUBLISH
}

/**
 * Compiler-facing contract for one concrete action.
 *
 * Runtime v2 fields such as media/count availability still protect production execution. This
 * contract is the v3 snapshot used to type-check graph wiring and later schedule resources. It is
 * deliberately declarative: an executor cannot smuggle a new state effect in through prompt text.
 */
data class AgentActionContract(
    val version: Int = 1,
    val phase: AgentActionPhase,
    val inputPorts: List<AgentPortSpec> = emptyList(),
    val outputPorts: List<AgentPortSpec> = emptyList(),
    val parameters: Map<String, AgentParameterSpec> = emptyMap(),
    val allowUnknownParameters: Boolean = true,
    val effects: Set<AgentStateEffect> = emptySet(),
    val resources: Set<AgentResourceClaim> = emptySet(),
    val postconditionIds: Set<String> = emptySet()
) {
    init {
        require(version > 0) { "action contract version must be positive" }
        require(inputPorts.map { it.name }.distinct().size == inputPorts.size) {
            "action contract has duplicate input ports"
        }
        require(outputPorts.map { it.name }.distinct().size == outputPorts.size) {
            "action contract has duplicate output ports"
        }
        require(parameters.keys == parameters.values.map { it.name }.toSet()) {
            "parameter map keys must match parameter names"
        }
    }
}

enum class AgentGoalOperationKind {
    RETRIEVE,
    COMPOSE,
    APPLY_STYLE,
    TEMPORAL_EDIT,
    UPDATE_LAYOUT,
    REPLACE_ASSET,
    ANALYZE,
    PUBLISH,
    ADD_CONTENT,
    CHANGE_CONTENT,
    REMOVE_CONTENT
}

sealed interface AgentGoalScope {
    data object Library : AgentGoalScope
    data object CurrentAssets : AgentGoalScope
    data class SelectedAssets(
        val assetIds: List<String>,
        val stateFingerprint: String
    ) : AgentGoalScope
    data class PreviousOutput(val operationId: String) : AgentGoalScope
    data class ContentNode(
        val contentGraphId: String,
        val contentGraphVersion: Int,
        val nodeId: String
    ) : AgentGoalScope
}

enum class AgentConstraintKind {
    REQUIRE,
    FORBID,
    PRESERVE
}

data class AgentGoalConstraint(
    val kind: AgentConstraintKind,
    val key: String,
    val value: String,
    val sourceText: String? = null,
    val provenance: AgentValueProvenance = AgentValueProvenance.MODEL_INFERRED
)

enum class AgentValueProvenance {
    USER_EXPLICIT,
    MODEL_INFERRED,
    PRESET,
    RUNTIME_FACT
}

sealed interface AgentGoalValue {
    data class Text(val value: String) : AgentGoalValue
    data class Number(val value: Double, val unit: String? = null) : AgentGoalValue
    data class Flag(val value: Boolean) : AgentGoalValue
    data class TextList(val values: List<String>) : AgentGoalValue
    data class TimeRangeMs(val startMs: Long, val endMs: Long) : AgentGoalValue {
        init {
            require(startMs >= 0 && endMs > startMs) { "invalid time range $startMs..$endMs" }
        }
    }
}

data class AgentGoalArgument(
    val value: AgentGoalValue,
    val provenance: AgentValueProvenance,
    val sourceText: String? = null
)

sealed interface AgentGoalTarget {
    data class Semantic(
        val description: String,
        val sourceText: String? = null
    ) : AgentGoalTarget
    data class GroundedContentNode(
        val contentGraphId: String,
        val contentGraphVersion: Int,
        val nodeId: String
    ) : AgentGoalTarget
}

data class AgentUnresolvedSlot(
    val slotId: String,
    val operationId: String,
    val name: String,
    val alternatives: List<String> = emptyList(),
    val userVisibleImpact: Boolean = true
)

data class AgentGoalOperation(
    val operationId: String,
    val kind: AgentGoalOperationKind,
    val scope: AgentGoalScope,
    val instruction: String = "",
    val requestedCount: Int? = null,
    val requiredMedia: RuntimeMediaKind? = null,
    val target: AgentGoalTarget? = null,
    val parameters: Map<String, AgentGoalArgument> = emptyMap(),
    val constraints: List<AgentGoalConstraint> = emptyList()
)

/** Model-authored logical program. It contains no SDK identifiers, files or graph wiring. */
data class AgentGoalIr(
    val goalId: String,
    val schemaVersion: Int = 1,
    val userGoal: String,
    val stateFingerprint: String,
    val operations: List<AgentGoalOperation>,
    val constraints: List<AgentGoalConstraint> = emptyList(),
    val unresolvedSlots: List<AgentUnresolvedSlot> = emptyList()
) {
    init {
        require(goalId.isNotBlank()) { "goal id cannot be blank" }
        require(schemaVersion > 0) { "goal schema version must be positive" }
        require(operations.map { it.operationId }.distinct().size == operations.size) {
            "goal operation ids must be unique"
        }
    }
}

object AgentGoalIrAdapter {
    fun from(route: SemanticRoute, state: AgentRuntimeState, stateFingerprint: String): AgentGoalIr {
        val operations = route.steps.mapIndexed { index, step ->
            val scope = when {
                step.family == ToolFamily.RETRIEVE -> AgentGoalScope.Library
                state.selectedAssetIds.isNotEmpty() -> AgentGoalScope.SelectedAssets(
                    state.selectedAssetIds,
                    stateFingerprint
                )
                index > 0 && step.family in setOf(
                    ToolFamily.STYLE,
                    ToolFamily.TEMPORAL,
                    ToolFamily.PUBLISH,
                    ToolFamily.ANALYZE
                ) -> AgentGoalScope.PreviousOutput(route.steps[index - 1].stepId)
                else -> AgentGoalScope.CurrentAssets
            }
            AgentGoalOperation(
                operationId = step.stepId,
                kind = step.family.toGoalOperationKind(),
                scope = scope,
                instruction = step.instruction,
                requestedCount = step.requestedCount,
                requiredMedia = step.requiredMedia
            )
        }
        return AgentGoalIr(
            goalId = "goal-${route.userGoal.hashCode().toUInt().toString(16)}",
            userGoal = route.userGoal,
            stateFingerprint = stateFingerprint,
            operations = operations
        )
    }

    private fun ToolFamily.toGoalOperationKind(): AgentGoalOperationKind = when (this) {
        ToolFamily.RETRIEVE -> AgentGoalOperationKind.RETRIEVE
        ToolFamily.COMPOSE -> AgentGoalOperationKind.COMPOSE
        ToolFamily.STYLE -> AgentGoalOperationKind.APPLY_STYLE
        ToolFamily.TEMPORAL -> AgentGoalOperationKind.TEMPORAL_EDIT
        ToolFamily.LAYOUT -> AgentGoalOperationKind.UPDATE_LAYOUT
        ToolFamily.REPLACE -> AgentGoalOperationKind.REPLACE_ASSET
        ToolFamily.ANALYZE -> AgentGoalOperationKind.ANALYZE
        ToolFamily.PUBLISH -> AgentGoalOperationKind.PUBLISH
    }
}
