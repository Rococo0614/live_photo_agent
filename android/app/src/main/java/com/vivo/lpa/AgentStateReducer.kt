package com.vivo.lpa

import org.json.JSONArray
import org.json.JSONObject

/** The only component allowed to apply executor-produced state changes. */
object AgentStateReducer {
    fun apply(state: AgentRuntimeState, delta: StateDelta): AgentRuntimeState = when (delta) {
        is StateDelta.Batch -> delta.deltas.fold(state, ::apply)
        is StateDelta.AddAssets -> {
            val byId = linkedMapOf<String, PreparedAsset>()
            state.sourceAssets.forEach { byId[it.assetId] = it }
            delta.assets.forEach { byId[it.assetId] = it }
            state.copy(sourceAssets = byId.values.toList())
        }
        is StateDelta.ReplaceAsset -> replaceAsset(state, delta.oldAssetId, delta.asset)
        is StateDelta.RemapSelection -> state.copy(
            selectedAssetIds = state.selectedAssetIds.map { delta.replacements[it] ?: it }.distinct()
        )
        is StateDelta.AddArtifact -> state.copy(
            artifacts = state.artifacts + (delta.artifact.artifactId to delta.artifact)
        )
        is StateDelta.ValidateArtifact -> {
            val artifact = state.artifacts[delta.artifactId] ?: return state
            state.copy(artifacts = state.artifacts + (
                delta.artifactId to artifact.copy(validated = true)
            ))
        }
        is StateDelta.UpdateLayout -> state.copy(
            layout = JSONObject(delta.layout.toString()),
            layoutVersion = state.layoutVersion + 1
        )
        is StateDelta.MarkStepCompleted -> state.copy(
            plan = updateStep(state.plan, delta.stepId) { it.copy(status = RuntimeStepStatus.COMPLETED) },
            executedCount = state.executedCount + 1,
            lastError = null
        )
        is StateDelta.MarkStepFailed -> state.copy(
            plan = updateStep(state.plan, delta.stepId) { it.copy(status = RuntimeStepStatus.FAILED) },
            executedCount = state.executedCount + 1,
            lastError = delta.message
        )
        is StateDelta.PublishArtifact -> {
            val artifact = state.artifacts[delta.artifactId]
            state.copy(
                artifacts = if (artifact == null) state.artifacts else state.artifacts + (
                    delta.artifactId to artifact.copy(
                        contentUri = delta.uri,
                        published = true,
                        temporary = false
                    )
                ),
                publishedUris = (state.publishedUris + delta.uri).distinct()
            )
        }
        is StateDelta.SetStatus -> state.copy(status = delta.status, lastError = delta.message)
    }

    private fun replaceAsset(
        state: AgentRuntimeState,
        oldAssetId: String,
        replacement: PreparedAsset
    ): AgentRuntimeState {
        val index = state.sourceAssets.indexOfFirst { it.assetId == oldAssetId }
        if (index < 0) return state
        val assets = state.sourceAssets.toMutableList().apply { set(index, replacement) }
        val mapping = mapOf(oldAssetId to replacement.assetId)
        return state.copy(
            sourceAssets = assets,
            selectedAssetIds = state.selectedAssetIds.map { mapping[it] ?: it }.distinct(),
            layout = state.layout?.let { remapLayout(it, mapping) },
            layoutVersion = if (state.layout != null) state.layoutVersion + 1 else state.layoutVersion,
            plan = remapPlan(state.plan, mapping)
        )
    }

    private fun remapLayout(layout: JSONObject, replacements: Map<String, String>): JSONObject {
        val copy = JSONObject(layout.toString())
        val items = copy.optJSONArray("items") ?: return copy
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            replacements[item.optString("asset_id")]?.let { item.put("asset_id", it) }
        }
        return copy
    }

    private fun remapPlan(plan: RuntimePlan?, replacements: Map<String, String>): RuntimePlan? {
        if (plan == null) return null
        return plan.copy(steps = plan.steps.map { step ->
            val refs = step.inputs.map { ref ->
                if (ref is AgentInputRef.AssetSet) {
                    ref.copy(assetIds = ref.assetIds.map { replacements[it] ?: it })
                } else ref
            }
            val arguments = JSONObject(step.arguments.toString())
            arguments.optJSONArray("asset_ids")?.let { ids ->
                val remapped = JSONArray()
                for (index in 0 until ids.length()) {
                    val id = ids.optString(index)
                    remapped.put(replacements[id] ?: id)
                }
                arguments.put("asset_ids", remapped)
            }
            step.copy(inputs = refs, arguments = arguments)
        })
    }

    private fun updateStep(
        plan: RuntimePlan?,
        stepId: String,
        transform: (RuntimeStep) -> RuntimeStep
    ): RuntimePlan? = plan?.copy(steps = plan.steps.map { step ->
        if (step.stepId == stepId) transform(step) else step
    })
}
