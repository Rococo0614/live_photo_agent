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
    val clarification: String? = null
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
    }
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
