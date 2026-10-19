package com.vivo.lpa

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android-side representation of the Python project's ExecutionPlan contract.
 * Keep tool names aligned with live_photo_agent.models.ToolName.
 */
data class MobileToolCall(
    val tool: String,
    val arguments: JSONObject = JSONObject(),
    val reason: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tool", tool)
        .put("reason", reason)
        .put("arguments", arguments)
}

data class MobilePlan(
    val userGoal: String,
    val intent: String,
    val selectedAssetIds: List<String>,
    val toolCalls: List<MobileToolCall>,
    val clarification: String? = null,
    /**
     * Verbatim text the on-device LLM produced for this plan, plus how long it took.
     *
     * Audit-only: it is deliberately kept out of [toJson] so the (already large) session state
     * never carries raw model output. Surfacing it is what makes a wrong plan diagnosable
     * instead of guessable -- previously the brain's reasoning could only be inferred from the
     * step list.
     */
    val brainTrace: BrainTrace? = null
) {
    fun toJson(): JSONObject {
        val calls = JSONArray()
        toolCalls.forEach { calls.put(it.toJson()) }
        return JSONObject()
            .put("user_goal", userGoal)
            .put("intent", intent)
            .put("selected_asset_ids", JSONArray(selectedAssetIds))
            .put("tool_calls", calls)
            .put("need_clarification", clarification != null)
            .put("clarification_questions", JSONArray(clarification?.let { listOf(it) } ?: emptyList<String>()))
            .put("plan_source", if (brainTrace?.parsed == true) brainTrace.source else "fallback")
    }
}

/** Provenance of a plan: which planner produced it, and the model's raw words. */
data class BrainTrace(
    val source: String,
    val rawOutput: String = "",
    val promptChars: Int = 0,
    val elapsedMs: Long = 0,
    /**
     * Whether this run actually produced the plan.
     *
     * A trace can also travel with a fallback plan: the brain ran and failed to parse, and the
     * keyword tables took over. Keeping the failed words is the whole point of the audit, but the
     * plan must still be attributed to the fallback, hence the flag.
     */
    val parsed: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("source", source)
        .put("prompt_chars", promptChars)
        .put("elapsed_ms", elapsedMs)
        .put("parsed", parsed)
        .put("raw_output", rawOutput)
}

data class MobileToolResult(
    val tool: String,
    val success: Boolean,
    val payload: JSONObject = JSONObject()
)

data class PreparedAsset(
    val assetId: String,
    val sourceUri: Uri,
    val sourceFile: java.io.File,
    val imageFile: java.io.File?,
    val videoFile: java.io.File?,
    val metadata: JSONObject,
    val semantic: JSONObject
) {
    fun promptDescription(): String = JSONObject()
        .put("asset_id", assetId)
        .put("media_format", metadata.optString("media_format", "unknown"))
        .put("metadata", metadata)
        .put("semantic", semantic)
        .toString()
}
