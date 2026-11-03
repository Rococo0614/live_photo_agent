package com.vivo.lpa

import android.net.Uri
import org.json.JSONObject

enum class AgentRunStatus {
    RUNNING,
    WAITING_USER,
    WAITING_OBSERVATION,
    FINISHED,
    FAILED,
    CANCELLED
}

/** Immutable runtime snapshot. Mutable SDK work is converted into a [StateDelta] first. */
data class AgentRuntimeState(
    val sessionId: String?,
    val turnId: String,
    val originalQuery: String,
    val sourceAssets: List<PreparedAsset> = emptyList(),
    val selectedAssetIds: List<String> = emptyList(),
    val layout: JSONObject? = null,
    val layoutVersion: Int = 0,
    val artifacts: Map<String, AgentArtifact> = emptyMap(),
    val route: SemanticRoute? = null,
    val plan: RuntimePlan? = null,
    val availableToolIds: List<String> = emptyList(),
    val executedCount: Int = 0,
    val replanCount: Int = 0,
    val status: AgentRunStatus = AgentRunStatus.RUNNING,
    val publishedUris: List<Uri> = emptyList(),
    val lastError: String? = null
) {
    fun scopedAssets(): List<PreparedAsset> {
        if (selectedAssetIds.isEmpty()) return sourceAssets
        val selected = selectedAssetIds.toSet()
        return sourceAssets.filter { it.assetId in selected }
    }

    fun latestValidatedArtifact(): AgentArtifact? = artifacts.values
        .filter { it.validated }
        .maxByOrNull { artifact ->
            plan?.steps?.indexOfFirst { it.stepId == artifact.producerStepId } ?: -1
        }

    fun toolContext(family: ToolFamily? = null): ToolRuntimeContext {
        // Composition acts on the cast by design; a tap-selection scopes edits, not collage slots.
        val target = if (family == ToolFamily.COMPOSE) sourceAssets else scopedAssets()
        val latest = latestValidatedArtifact()
        val consumeLatestArtifact = latest != null && family in setOf(
            ToolFamily.STYLE, ToolFamily.TEMPORAL, ToolFamily.PUBLISH, ToolFamily.ANALYZE
        )
        return ToolRuntimeContext.from(
            assets = target,
            selectedCount = selectedAssetIds.size,
            hasLayout = layout != null,
            artifactKind = latest?.kind.takeIf { consumeLatestArtifact },
            artifactValidated = consumeLatestArtifact && latest?.validated == true,
            family = family
        )
    }

    /** Execution-time context follows the step's explicit input reference, not the whole cast. */
    fun toolContext(step: RuntimeStep): ToolRuntimeContext {
        step.inputs.filterIsInstance<AgentInputRef.Artifact>().firstOrNull()?.let { ref ->
            val artifact = artifacts[ref.artifactId]
                ?: return toolContext(step.family)
            return ToolRuntimeContext.from(
                assets = emptyList(),
                selectedCount = selectedAssetIds.size,
                hasLayout = layout != null,
                artifactKind = artifact.kind,
                artifactValidated = artifact.validated,
                family = step.family
            )
        }
        step.inputs.filterIsInstance<AgentInputRef.AssetSet>().firstOrNull()?.let { ref ->
            val ids = ref.assetIds.toSet()
            return ToolRuntimeContext.from(
                assets = sourceAssets.filter { it.assetId in ids },
                selectedCount = selectedAssetIds.size,
                hasLayout = layout != null,
                family = step.family
            )
        }
        return toolContext(step.family)
    }
}

/** Every state change emitted by a tool is explicit and reducer-owned. */
sealed interface StateDelta {
    data class AddAssets(val assets: List<PreparedAsset>) : StateDelta
    data class ReplaceAsset(val oldAssetId: String, val asset: PreparedAsset) : StateDelta
    data class RemapSelection(val replacements: Map<String, String>) : StateDelta
    data class AddArtifact(val artifact: AgentArtifact) : StateDelta
    data class ValidateArtifact(val artifactId: String) : StateDelta
    data class UpdateLayout(val layout: JSONObject) : StateDelta
    data class MarkStepCompleted(val stepId: String) : StateDelta
    data class MarkStepFailed(val stepId: String, val message: String) : StateDelta
    data class PublishArtifact(val artifactId: String, val uri: Uri) : StateDelta
    data class SetStatus(val status: AgentRunStatus, val message: String? = null) : StateDelta
    data class Batch(val deltas: List<StateDelta>) : StateDelta
}

data class ToolExecutionResult(
    val result: MobileToolResult,
    val delta: StateDelta,
    val invalidatesPendingPlan: Boolean = false
)
