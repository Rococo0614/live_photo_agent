package com.vivo.lpa

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

enum class AgentGoalCodecSeverity {
    ERROR,
    WARNING
}

data class AgentGoalCodecDiagnostic(
    val severity: AgentGoalCodecSeverity,
    val code: String,
    val path: String,
    val message: String
)

data class AgentGoalCodecResult(
    val goal: AgentGoalIr?,
    val diagnostics: List<AgentGoalCodecDiagnostic>
) {
    val valid: Boolean
        get() = goal != null && diagnostics.none { it.severity == AgentGoalCodecSeverity.ERROR }
}

/**
 * Strict model-facing Goal IR codec.
 *
 * The model may describe domain operations and semantic targets. Concrete tools, graph wiring,
 * files, URIs and runtime IDs are rejected so the LLM cannot bypass the compiler.
 */
object AgentGoalIrCodec {
    const val FUNCTION_NAME = "plan_live_edit"
    private const val MAX_OPERATIONS = 16

    private val forbiddenKeys = setOf(
        "tool", "tool_id", "action", "action_id", "node_id", "edge_id",
        "from_node", "to_node", "file", "file_path", "path", "uri",
        "asset_id", "asset_ids", "sdk", "backend"
    )
    private val topLevelKeys = setOf("operations", "constraints", "unresolved_slots")
    private val operationKeys = setOf(
        "op", "instruction", "count", "media", "scope", "target", "parameters", "constraints"
    )

    fun parseFunctionCall(
        call: JSONObject,
        userGoal: String,
        state: AgentRuntimeState
    ): AgentGoalCodecResult {
        val function = call.optJSONObject("function") ?: call
        val name = function.optString("name")
        if (name != FUNCTION_NAME) {
            return AgentGoalCodecResult(null, listOf(error(
                "wrong_function", "$.name", "expected $FUNCTION_NAME, got $name"
            )))
        }
        val rawArguments = function.opt("arguments")
        val arguments = when (rawArguments) {
            is JSONObject -> rawArguments
            is String -> runCatching { JSONObject(rawArguments) }.getOrNull()
            else -> null
        } ?: return AgentGoalCodecResult(null, listOf(error(
            "invalid_arguments", "$.arguments", "function arguments must be a JSON object"
        )))
        return parseArguments(arguments, userGoal, state)
    }

    fun parseArguments(
        arguments: JSONObject,
        userGoal: String,
        state: AgentRuntimeState
    ): AgentGoalCodecResult {
        val diagnostics = mutableListOf<AgentGoalCodecDiagnostic>()
        rejectForbiddenFields(arguments, "$", diagnostics)
        unknownKeys(arguments, topLevelKeys, "$", diagnostics)

        val rows = arguments.optJSONArray("operations")
        if (rows == null || rows.length() == 0) {
            diagnostics += error("missing_operations", "$.operations", "at least one operation is required")
            return AgentGoalCodecResult(null, diagnostics)
        }
        if (rows.length() > MAX_OPERATIONS) {
            diagnostics += error(
                "operation_limit", "$.operations", "${rows.length()} operations exceed $MAX_OPERATIONS"
            )
        }

        val fingerprint = AgentStateFingerprint.of(state)
        val explicitCount = RequestFactExtractor.explicitCount(userGoal)
        val explicitMedia = RequestFactExtractor.explicitMedia(userGoal)
        val operations = mutableListOf<AgentGoalOperation>()
        val operationIds = mutableListOf<String>()
        for (index in 0 until minOf(rows.length(), MAX_OPERATIONS)) {
            val path = "$.operations[$index]"
            val row = rows.optJSONObject(index)
            if (row == null) {
                diagnostics += error("operation_type", path, "operation must be an object")
                continue
            }
            unknownKeys(row, operationKeys, path, diagnostics)
            val kind = parseOperationKind(row.optString("op"))
            if (kind == null) {
                diagnostics += error("unknown_operation", "$path.op", "unknown operation ${row.optString("op")}")
                continue
            }
            val operationId = "goal-op-${index + 1}-${kind.name.lowercase()}"
            operationIds += operationId
            val scope = parseScope(
                row.optString("scope"),
                kind,
                index,
                operationIds,
                state,
                fingerprint,
                "$path.scope",
                diagnostics
            )
            val modelCount = row.optInt("count", 0).takeIf { it > 0 }
            val mediaText = row.optString("media")
            val modelMedia = parseMedia(mediaText)
            if (mediaText.isNotBlank() && !mediaText.equals("any", true) && modelMedia == null) {
                diagnostics += error("unknown_media", "$path.media", "unknown media $mediaText")
            }
            val target = parseTarget(row.opt("target"), "$path.target", diagnostics)
            val parameters = parseParameters(
                row.optJSONObject("parameters"), "$path.parameters", userGoal, diagnostics
            )
            val constraints = parseConstraints(
                row.optJSONArray("constraints"), "$path.constraints", userGoal, diagnostics
            )
            operations += AgentGoalOperation(
                operationId = operationId,
                kind = kind,
                scope = scope,
                instruction = row.optString("instruction"),
                requestedCount = if (kind == AgentGoalOperationKind.RETRIEVE) {
                    explicitCount ?: modelCount
                } else modelCount,
                requiredMedia = if (kind == AgentGoalOperationKind.RETRIEVE) {
                    explicitMedia ?: modelMedia
                } else modelMedia,
                target = target,
                parameters = parameters,
                constraints = constraints
            )
        }

        val globalConstraints = parseConstraints(
            arguments.optJSONArray("constraints"), "$.constraints", userGoal, diagnostics
        )
        val unresolved = parseUnresolved(
            arguments.optJSONArray("unresolved_slots"), operations, diagnostics
        ).toMutableList()
        operations.filter { operation ->
            operation.kind in setOf(
                AgentGoalOperationKind.ADD_CONTENT,
                AgentGoalOperationKind.CHANGE_CONTENT,
                AgentGoalOperationKind.REMOVE_CONTENT
            ) && operation.target == null
        }.forEach { operation ->
            if (unresolved.none { it.operationId == operation.operationId && it.name == "target" }) {
                unresolved += AgentUnresolvedSlot(
                    "slot-${operation.operationId}-target",
                    operation.operationId,
                    "target"
                )
            }
        }

        if (diagnostics.any { it.severity == AgentGoalCodecSeverity.ERROR }) {
            return AgentGoalCodecResult(null, diagnostics)
        }
        val goal = AgentGoalIr(
            goalId = stableGoalId(userGoal, fingerprint, operations),
            userGoal = userGoal,
            stateFingerprint = fingerprint,
            operations = operations,
            constraints = globalConstraints,
            unresolvedSlots = unresolved
        )
        return AgentGoalCodecResult(goal, diagnostics)
    }

    private fun parseOperationKind(value: String): AgentGoalOperationKind? = when (value.lowercase()) {
        "retrieve" -> AgentGoalOperationKind.RETRIEVE
        "compose" -> AgentGoalOperationKind.COMPOSE
        "apply_style", "style" -> AgentGoalOperationKind.APPLY_STYLE
        "temporal_edit", "temporal" -> AgentGoalOperationKind.TEMPORAL_EDIT
        "update_layout", "layout" -> AgentGoalOperationKind.UPDATE_LAYOUT
        "replace_asset", "replace" -> AgentGoalOperationKind.REPLACE_ASSET
        "analyze" -> AgentGoalOperationKind.ANALYZE
        "publish" -> AgentGoalOperationKind.PUBLISH
        "add_content" -> AgentGoalOperationKind.ADD_CONTENT
        "change_content" -> AgentGoalOperationKind.CHANGE_CONTENT
        "remove_content" -> AgentGoalOperationKind.REMOVE_CONTENT
        else -> null
    }

    private fun parseScope(
        value: String,
        kind: AgentGoalOperationKind,
        index: Int,
        operationIds: List<String>,
        state: AgentRuntimeState,
        fingerprint: String,
        path: String,
        diagnostics: MutableList<AgentGoalCodecDiagnostic>
    ): AgentGoalScope {
        val normalized = value.lowercase().ifBlank {
            when {
                kind == AgentGoalOperationKind.RETRIEVE -> "library"
                state.selectedAssetIds.isNotEmpty() -> "selected"
                index > 0 -> "previous"
                else -> "current"
            }
        }
        return when (normalized) {
            "library" -> AgentGoalScope.Library
            "current", "current_assets" -> AgentGoalScope.CurrentAssets
            "selected", "selected_assets" -> {
                if (state.selectedAssetIds.isEmpty()) {
                    diagnostics += error("empty_selection", path, "selected scope requires UI selection")
                }
                AgentGoalScope.SelectedAssets(state.selectedAssetIds, fingerprint)
            }
            "previous", "previous_output" -> {
                val previous = operationIds.dropLast(1).lastOrNull()
                if (previous == null) {
                    diagnostics += error("missing_previous", path, "first operation cannot use previous scope")
                    AgentGoalScope.CurrentAssets
                } else AgentGoalScope.PreviousOutput(previous)
            }
            else -> {
                diagnostics += error("unknown_scope", path, "unknown scope $value")
                AgentGoalScope.CurrentAssets
            }
        }
    }

    private fun parseTarget(
        raw: Any?,
        path: String,
        diagnostics: MutableList<AgentGoalCodecDiagnostic>
    ): AgentGoalTarget? = when (raw) {
        null, JSONObject.NULL -> null
        is String -> raw.trim().takeIf { it.isNotEmpty() }?.let { AgentGoalTarget.Semantic(it) }
        is JSONObject -> {
            unknownKeys(raw, setOf("description", "source_text"), path, diagnostics)
            raw.optString("description").trim().takeIf { it.isNotEmpty() }?.let {
                AgentGoalTarget.Semantic(it, raw.optString("source_text").takeIf(String::isNotBlank))
            } ?: run {
                diagnostics += error("missing_target", path, "semantic target requires description")
                null
            }
        }
        else -> {
            diagnostics += error("target_type", path, "target must be text or object")
            null
        }
    }

    private fun parseParameters(
        objectValue: JSONObject?,
        path: String,
        userGoal: String,
        diagnostics: MutableList<AgentGoalCodecDiagnostic>
    ): Map<String, AgentGoalArgument> {
        if (objectValue == null) return emptyMap()
        return objectValue.keys().asSequence().mapNotNull { name ->
            val raw = objectValue.opt(name)
            val sourceText = (raw as? JSONObject)?.optString("source_text")?.takeIf(String::isNotBlank)
            val valueRaw = (raw as? JSONObject)?.takeIf { it.has("value") }?.opt("value") ?: raw
            val unit = (raw as? JSONObject)?.optString("unit")?.takeIf(String::isNotBlank)
            val value = parseGoalValue(valueRaw, unit, "$path.$name", diagnostics) ?: return@mapNotNull null
            name to AgentGoalArgument(
                value,
                provenance(sourceText, userGoal),
                sourceText
            )
        }.toMap()
    }

    private fun parseGoalValue(
        raw: Any?,
        unit: String?,
        path: String,
        diagnostics: MutableList<AgentGoalCodecDiagnostic>
    ): AgentGoalValue? = when (raw) {
        is String -> {
            if (raw.startsWith("/") || raw.startsWith("file:") || raw.startsWith("content:")) {
                diagnostics += error("runtime_reference", path, "paths and URIs are forbidden")
                null
            } else AgentGoalValue.Text(raw)
        }
        is Boolean -> AgentGoalValue.Flag(raw)
        is Number -> AgentGoalValue.Number(raw.toDouble(), unit)
        is JSONArray -> {
            val values = (0 until raw.length()).mapNotNull { index ->
                (raw.opt(index) as? String)?.takeIf(String::isNotBlank)
            }
            if (values.size != raw.length()) {
                diagnostics += error("parameter_list", path, "only text lists are supported")
                null
            } else AgentGoalValue.TextList(values)
        }
        is JSONObject -> {
            if (raw.has("start_ms") && raw.has("end_ms")) {
                runCatching {
                    AgentGoalValue.TimeRangeMs(raw.getLong("start_ms"), raw.getLong("end_ms"))
                }.getOrElse {
                    diagnostics += error("time_range", path, it.message ?: "invalid time range")
                    null
                }
            } else {
                diagnostics += error("parameter_object", path, "unsupported parameter object")
                null
            }
        }
        else -> {
            diagnostics += error("parameter_type", path, "unsupported parameter value")
            null
        }
    }

    private fun parseConstraints(
        array: JSONArray?,
        path: String,
        userGoal: String,
        diagnostics: MutableList<AgentGoalCodecDiagnostic>
    ): List<AgentGoalConstraint> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val rowPath = "$path[$index]"
            val row = array.optJSONObject(index)
            if (row == null) {
                diagnostics += error("constraint_type", rowPath, "constraint must be an object")
                return@mapNotNull null
            }
            unknownKeys(row, setOf("kind", "key", "value", "source_text"), rowPath, diagnostics)
            val kind = runCatching {
                AgentConstraintKind.valueOf(row.optString("kind").uppercase())
            }.getOrNull()
            val key = row.optString("key").trim()
            val value = row.optString("value").trim()
            if (kind == null || key.isEmpty() || value.isEmpty()) {
                diagnostics += error("constraint_fields", rowPath, "constraint requires kind, key and value")
                return@mapNotNull null
            }
            val sourceText = row.optString("source_text").takeIf(String::isNotBlank)
            AgentGoalConstraint(kind, key, value, sourceText, provenance(sourceText, userGoal))
        }
    }

    private fun parseUnresolved(
        array: JSONArray?,
        operations: List<AgentGoalOperation>,
        diagnostics: MutableList<AgentGoalCodecDiagnostic>
    ): List<AgentUnresolvedSlot> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val path = "$.unresolved_slots[$index]"
            val row = array.optJSONObject(index)
            if (row == null) {
                diagnostics += error("slot_type", path, "unresolved slot must be an object")
                return@mapNotNull null
            }
            unknownKeys(
                row,
                setOf("operation_index", "name", "alternatives", "user_visible_impact"),
                path,
                diagnostics
            )
            val operationIndex = row.optInt("operation_index", -1)
            val operation = operations.getOrNull(operationIndex)
            val name = row.optString("name").trim()
            if (operation == null || name.isEmpty()) {
                diagnostics += error("slot_fields", path, "slot requires valid operation_index and name")
                return@mapNotNull null
            }
            val alternativesArray = row.optJSONArray("alternatives")
            val alternatives = if (alternativesArray == null) emptyList() else
                (0 until alternativesArray.length()).mapNotNull { alternativeIndex ->
                    (alternativesArray.opt(alternativeIndex) as? String)?.takeIf(String::isNotBlank)
                }
            AgentUnresolvedSlot(
                "slot-${operation.operationId}-$name",
                operation.operationId,
                name,
                alternatives,
                row.optBoolean("user_visible_impact", true)
            )
        }
    }

    private fun provenance(sourceText: String?, userGoal: String): AgentValueProvenance =
        if (!sourceText.isNullOrBlank() && userGoal.contains(sourceText)) {
            AgentValueProvenance.USER_EXPLICIT
        } else {
            AgentValueProvenance.MODEL_INFERRED
        }

    private fun parseMedia(value: String): RuntimeMediaKind? = when (value.lowercase()) {
        "image", "still_image" -> RuntimeMediaKind.IMAGE
        "video" -> RuntimeMediaKind.VIDEO
        "live_photo", "livephoto" -> RuntimeMediaKind.LIVE_PHOTO
        "mixed" -> RuntimeMediaKind.MIXED
        "", "any" -> null
        else -> null
    }

    private fun rejectForbiddenFields(
        value: Any?,
        path: String,
        diagnostics: MutableList<AgentGoalCodecDiagnostic>
    ) {
        when (value) {
            is JSONObject -> value.keys().asSequence().forEach { key ->
                if (key.lowercase() in forbiddenKeys) {
                    diagnostics += error("physical_field", "$path.$key", "$key is compiler-owned")
                }
                rejectForbiddenFields(value.opt(key), "$path.$key", diagnostics)
            }
            is JSONArray -> (0 until value.length()).forEach { index ->
                rejectForbiddenFields(value.opt(index), "$path[$index]", diagnostics)
            }
        }
    }

    private fun unknownKeys(
        value: JSONObject,
        allowed: Set<String>,
        path: String,
        diagnostics: MutableList<AgentGoalCodecDiagnostic>
    ) {
        value.keys().asSequence().filterNot { it in allowed }.forEach { key ->
            diagnostics += error("unknown_field", "$path.$key", "unknown field $key")
        }
    }

    private fun stableGoalId(
        userGoal: String,
        fingerprint: String,
        operations: List<AgentGoalOperation>
    ): String {
        val canonical = userGoal + "|" + fingerprint + "|" + operations.joinToString(",") {
            "${it.kind}:${it.instruction}:${it.requestedCount}:${it.requiredMedia}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return "goal-" + digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun error(code: String, path: String, message: String) = AgentGoalCodecDiagnostic(
        AgentGoalCodecSeverity.ERROR,
        code,
        path,
        message
    )
}
