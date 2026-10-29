package com.vivo.lpa

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Optional bridge to the target project's Qualcomm Genie text runner. */
class GenieTextPlanner(private val context: Context, private val sessionId: String? = null) {
    private enum class Experiment(val id: String) {
        BASELINE("baseline"),
        P1("p1"),
        SCRATCHPAD("scratchpad"),
        TWO_TURN("two_turn");

        companion object {
            fun current(): Experiment = entries.firstOrNull {
                it.id.equals(BuildConfig.PLANNER_EXPERIMENT, ignoreCase = true)
            } ?: BASELINE
        }
    }

    private enum class RuntimeStage {
        ROUTE,
        RETRIEVAL,
        BIND,
        REVISE
    }

    private data class TimedText(val text: String, val elapsedMs: Long)

    /** One logical planner result, whether it took one generation or two. */
    private data class GeneratedText(
        val finalText: String,
        val reasoningText: String = "",
        val promptChars: Int,
        val elapsedMs: Long,
        val reasoningElapsedMs: Long = 0,
        val formatElapsedMs: Long = elapsedMs
    )

    private val experiment = Experiment.current()

    /**
     * The most recent brain attempt, kept even when it failed to parse or the process errored.
     *
     * A failed brain run is exactly the case an audit must capture: without the raw words there is
     * no way to tell a bad prompt from a bad model output. [MobilePlanner] attaches this to the
     * fallback plan so the trail survives the failure.
     */
    var lastTrace: BrainTrace? = null
        private set

    /** Web-agent style plan: search_assets/template_collage is planned before media preparation. */
    fun tryIntent(query: String, selectedAssetIds: List<String> = emptyList()): MobilePlan? {
        if (!ModelPack.qwenReady(context)) return null
        val generated = generateIntent(query, selectedAssetIds)
        val raw = generated.finalText
        lastTrace = BrainTrace(
            source = "qnn_genie_intent",
            rawOutput = raw,
            promptChars = generated.promptChars,
            elapsedMs = generated.elapsedMs,
            strategy = experiment.id,
            reasoningOutput = generated.reasoningText,
            reasoningElapsedMs = generated.reasoningElapsedMs,
            formatElapsedMs = generated.formatElapsedMs
        )
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val json = JSONObject(raw.substring(start, end + 1))
            val calls = mutableListOf<MobileToolCall>()
            val callArray = json.optJSONArray("tool_calls") ?: JSONArray()
            for (index in 0 until callArray.length()) {
                val call = callArray.optJSONObject(index) ?: continue
                val tool = call.optString("tool")
                if (!MobileToolRegistry.supports(tool)) continue
                calls += MobileToolCall(tool, call.optJSONObject("arguments") ?: JSONObject(), call.optString("reason"))
            }
            // A chat answer is not a clarification question: it is the reply itself. Carrying it in
            // the clarification slot lets the runner answer directly without inventing a plan.
            val reply = if (json.optString("intent") == "chat") {
                json.optString("reply", "").takeIf { it.isNotBlank() }
            } else null
            val plan = MobilePlan(
                userGoal = json.optString("user_goal", query),
                intent = json.optString("intent", "plan"),
                selectedAssetIds = selectedAssetIds,
                toolCalls = calls,
                clarification = reply ?: json.optString("clarification", "").takeIf { it.isNotBlank() },
                brainTrace = lastTrace
            ).takeIf { it.toolCalls.isNotEmpty() || it.clarification != null } ?: return@runCatching null
            // This plan is the brain's, not the keyword tables'. Without parsed=true the audit
            // logs PLAN_FINAL.source="fallback" for every brain chat reply, which is how the
            // harness came to attribute brain-authored turns to the fallback (see
            // planner_reliability_plan.md §3.4). tryPlan sets the same flag at its return.
            lastTrace = lastTrace?.copy(parsed = true)
            plan.copy(brainTrace = lastTrace)
        }.getOrNull()
    }

    fun tryPlan(
        query: String,
        assets: List<PreparedAsset>,
        selectedAssetIds: List<String> = emptyList()
    ): MobilePlan? {
        if (!ModelPack.qwenReady(context)) return null
        val generated = generatePlan(query, assets, selectedAssetIds)
        val raw = generated.finalText
        lastTrace = BrainTrace(
            source = "qnn_genie",
            rawOutput = raw,
            promptChars = generated.promptChars,
            elapsedMs = generated.elapsedMs,
            strategy = experiment.id,
            reasoningOutput = generated.reasoningText,
            reasoningElapsedMs = generated.reasoningElapsedMs,
            formatElapsedMs = generated.formatElapsedMs
        )
        val parsed = parse(raw, query, assets, selectedAssetIds) ?: return null
        lastTrace = lastTrace?.copy(parsed = true)
        return parsed.copy(brainTrace = lastTrace)
    }

    /**
     * Runtime-v2 route planning. The model sees eight semantic families rather than the concrete
     * thirty-tool registry, so it can state the whole goal before retrieval without guessing an
     * SDK implementation or an asset id.
     */
    fun tryRoute(
        query: String,
        state: AgentRuntimeState,
        correction: String = ""
    ): RoutePlanningResult? {
        if (!ModelPack.qwenReady(context)) return null
        val prompt = runtimeRoutePrompt(query, state, correction)
        val generated = generateRuntimeText(prompt, 384, RuntimeStage.ROUTE)
        val trace = BrainTrace(
            source = "qnn_genie_route",
            rawOutput = generated.finalText,
            promptChars = generated.promptChars,
            elapsedMs = generated.elapsedMs,
            strategy = runtimeStrategy(),
            reasoningOutput = generated.reasoningText,
            reasoningElapsedMs = generated.reasoningElapsedMs,
            formatElapsedMs = generated.formatElapsedMs
        )
        lastTrace = trace
        val route = parseRuntimeRoute(generated.finalText, query, state) ?: return null
        val parsedTrace = trace.copy(parsed = true)
        lastTrace = parsedTrace
        return RoutePlanningResult(route, parsedTrace)
    }

    /** Repairs an unusable RETRIEVE instruction without falling back to keyword-selected media. */
    fun tryRetrieveSpec(
        query: String,
        requestedCount: Int,
        requiredMedia: RuntimeMediaKind?
    ): RetrievalPlanningResult? {
        if (!ModelPack.qwenReady(context)) return null
        val prompt = runtimeRetrievalPrompt(query, requestedCount, requiredMedia)
        val generated = generateRuntimeText(prompt, 192, RuntimeStage.RETRIEVAL)
        val trace = BrainTrace(
            source = "qnn_genie_retrieval",
            rawOutput = generated.finalText,
            promptChars = generated.promptChars,
            elapsedMs = generated.elapsedMs,
            strategy = runtimeStrategy(),
            reasoningOutput = generated.reasoningText,
            reasoningElapsedMs = generated.reasoningElapsedMs,
            formatElapsedMs = generated.formatElapsedMs
        )
        lastTrace = trace
        val row = Regex("(?im)^\\s*(?:SEARCH|RETRIEVE_QUERY)\\s*=\\s*(.+)$")
            .find(generated.finalText)?.groupValues?.get(1) ?: return null
        val parts = row.split('|')
        if (parts.size !in 1..3) return null // legacy multi-field output remains readable
        val searchQuery = parts[0].trim().takeIf { it.isNotBlank() } ?: return null
        val parsedTrace = trace.copy(parsed = true)
        lastTrace = parsedTrace
        return RetrievalPlanningResult(
            RetrievalSpec(
                searchQuery,
                requestedCount.takeIf { it > 0 } ?: 3,
                requiredMedia
            ),
            parsedTrace
        )
    }

    /** Bind one semantic route step against the <=10 concrete tools available in this state. */
    fun tryBindStep(
        query: String,
        routeStep: SemanticRouteStep,
        state: AgentRuntimeState,
        catalog: ToolCatalogDecision,
        observation: String = ""
    ): BoundToolResult? {
        if (!ModelPack.qwenReady(context) || catalog.available.isEmpty()) return null
        require(catalog.available.size <= MobileToolRegistry.MAX_VISIBLE_TOOLS)
        val prompt = runtimeBindPrompt(query, routeStep, state, catalog, observation)
        val generated = generateRuntimeText(prompt, 256, RuntimeStage.BIND)
        val trace = BrainTrace(
            source = "qnn_genie_bind",
            rawOutput = generated.finalText,
            promptChars = generated.promptChars,
            elapsedMs = generated.elapsedMs,
            strategy = runtimeStrategy(),
            reasoningOutput = generated.reasoningText,
            reasoningElapsedMs = generated.reasoningElapsedMs,
            formatElapsedMs = generated.formatElapsedMs
        )
        lastTrace = trace
        val call = parseBoundCall(generated.finalText, state, catalog.available.map { it.id }.toSet())
            ?: return null
        val parsedTrace = trace.copy(parsed = true)
        lastTrace = parsedTrace
        return BoundToolResult(call, parsedTrace)
    }

    /** Replaces only the unexecuted semantic suffix after an observation or tool failure. */
    fun tryReviseRoute(
        query: String,
        state: AgentRuntimeState,
        completed: List<SemanticRouteStep>,
        pending: List<SemanticRouteStep>,
        observation: String
    ): RoutePlanningResult? {
        if (!ModelPack.qwenReady(context)) return null
        val prompt = runtimeRevisePrompt(query, state, completed, pending, observation)
        val generated = generateRuntimeText(prompt, 384, RuntimeStage.REVISE)
        val trace = BrainTrace(
            source = "qnn_genie_revise",
            rawOutput = generated.finalText,
            promptChars = generated.promptChars,
            elapsedMs = generated.elapsedMs,
            strategy = runtimeStrategy(),
            reasoningOutput = generated.reasoningText,
            reasoningElapsedMs = generated.reasoningElapsedMs,
            formatElapsedMs = generated.formatElapsedMs
        )
        lastTrace = trace
        val route = parseRuntimeRoute(generated.finalText, query, state) ?: return null
        val parsedTrace = trace.copy(parsed = true)
        lastTrace = parsedTrace
        return RoutePlanningResult(route, parsedTrace)
    }

    private fun parseRuntimeRoute(
        text: String,
        query: String,
        state: AgentRuntimeState
    ): SemanticRoute? {
        val explicitCount = RequestFactExtractor.explicitCount(query)
        val explicitMedia = explicitMediaRequirement(query)
        val routePattern = Regex(
            "(?im)^\\s*(ROUTE|RETRY|RETRIEVE|COMPOSE|STYLE|TEMPORAL|LAYOUT|REPLACE|ANALYZE|PUBLISH)" +
                "(?:\\s*=\\s*(.*))?\\s*$"
        )
        val allowedFamilies = runtimeRouteFamilies(state)
        val steps = routePattern.findAll(text).mapNotNull { match ->
                val key = match.groupValues[1].uppercase()
                val parts = match.groupValues[2].split('|')
                val family: ToolFamily
                val argumentOffset: Int
                if (key == "ROUTE" || key == "RETRY") {
                    family = runCatching {
                        ToolFamily.valueOf(parts.getOrNull(0)?.trim()?.uppercase().orEmpty())
                    }.getOrNull() ?: return@mapNotNull null
                    argumentOffset = 1
                } else {
                    family = runCatching { ToolFamily.valueOf(key) }.getOrNull()
                        ?: return@mapNotNull null
                    argumentOffset = 0
                }
                if (family !in allowedFamilies) return@mapNotNull null
                if (parts.size - argumentOffset !in 0..3) return@mapNotNull null
                val instruction = parts.getOrNull(argumentOffset)?.trim().orEmpty()
                if (family == ToolFamily.RETRIEVE && state.sourceAssets.isEmpty() && instruction.isBlank()) {
                    return@mapNotNull null
                }
                val modelCount = parts.getOrNull(argumentOffset + 1)
                    ?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                SemanticRouteStep(
                    stepId = "route-${family.name.lowercase()}-${match.range.first}",
                    family = family,
                    instruction = instruction,
                    requestedCount = if (family == ToolFamily.RETRIEVE) {
                        explicitCount ?: modelCount
                    } else modelCount,
                    // A model must not narrow retrieval to IMAGE merely because it imagined a
                    // representation. Media is a hard search constraint only when the user named it.
                    requiredMedia = explicitMedia
                )
            }
            // Negative-only safety guard: never add a capability from keywords, but prevent the
            // model from inventing destructive temporal edits (the nine-collage route once added
            // "裁剪与倒放" although the user requested neither). Open-ended semantic selection
            // remains the model's job; this only removes a known, auditable hallucinated family.
            .filter { step -> routeStepExplicitlyRequested(step.family, query, state) }
            .take(8)
            .fold(mutableListOf<SemanticRouteStep>()) { acc, step ->
                if (acc.lastOrNull()?.family == step.family &&
                    acc.lastOrNull()?.instruction == step.instruction
                ) acc else acc.apply { add(step) }
            }
        if (steps.isNotEmpty()) {
            if (state.sourceAssets.isEmpty() && steps.first().family != ToolFamily.RETRIEVE) return null
            val hasActualOperation = steps.any {
                it.family !in setOf(ToolFamily.RETRIEVE, ToolFamily.PUBLISH)
            }
            // Retrieval plus publish is not a media edit. Reject this incomplete route rather than
            // letting a structurally valid plan silently omit the user's requested operation.
            if (MobilePlanner.isExplicitEditQuery(query) && !hasActualOperation) return null
            return SemanticRoute(query, steps)
        }

        val question = field(text, "QUESTION").ifBlank { field(text, "ASK") }
        if (question.isNotBlank()) return SemanticRoute(query, emptyList(), clarification = question)
        val reply = field(text, "REPLY")
        if (reply.isNotBlank()) return SemanticRoute(query, emptyList(), reply = reply)
        return null
    }

    private fun explicitMediaRequirement(query: String): RuntimeMediaKind? {
        return RequestFactExtractor.explicitMedia(query)
    }

    private fun hasExplicitTemporalAction(query: String): Boolean {
        val lower = query.lowercase()
        return listOf(
            "裁剪", "截取", "剪成", "trim", "倒放", "reverse", "变速", "加速", "慢放",
            "speed", "镜像", "翻转", "反转", "mirror", "flip", "转场", "transition",
            "缩放效果", "时间线", "换封面", "设置封面", "display frame"
        ).any(lower::contains)
    }

    /**
     * Negative-only guard: a model may discover an open-ended synonym, but it may not append a
     * whole operation family that has no evidence at all in the literal request. This function
     * never adds a family or selects a concrete tool.
     */
    private fun routeStepExplicitlyRequested(
        family: ToolFamily,
        query: String,
        state: AgentRuntimeState
    ): Boolean {
        val lower = query.lowercase()
        fun any(vararg terms: String) = terms.any(lower::contains)
        return when (family) {
            ToolFamily.RETRIEVE -> state.sourceAssets.isEmpty() || any(
                "找", "搜索", "相册", "选择", "我的", "find", "search", "from album"
            )
            ToolFamily.COMPOSE -> any(
                "拼", "拼接", "拼图", "拼贴", "模板", "模版", "版式", "堆叠", "竖排", "上下排列",
                "collage", "compose", "stack", "transition", "转场"
            )
            ToolFamily.STYLE -> any(
                "滤镜", "调色", "颜色", "色调", "黑白", "灰度", "暖", "冷", "复古",
                "鲜艳", "提亮", "锐化", "降噪", "美颜", "虚化", "模糊", "动感",
                "光效", "贴纸", "边框", "水印", "filter", "style", "color", "tone",
                "bright", "sharpen", "denoise", "beauty", "blur", "sticker", "watermark"
            )
            ToolFamily.TEMPORAL -> hasExplicitTemporalAction(query)
            ToolFamily.LAYOUT -> any(
                "交换", "换位置", "布局", "版式", "换模板", "换模版", "swap", "layout"
            )
            ToolFamily.REPLACE -> any(
                "替换素材", "换成", "换掉", "更换素材", "replace"
            )
            ToolFamily.ANALYZE -> any(
                "摘要", "识别", "内容", "主色", "配色", "色板", "取色", "xdr",
                "summary", "analyze", "palette", "dominant"
            )
            ToolFamily.PUBLISH -> true
        }
    }

    private fun parseBoundCall(
        text: String,
        state: AgentRuntimeState,
        allowedTools: Set<String>
    ): MobileToolCall? {
        val row = Regex("(?im)^\\s*STEP\\s*=\\s*(.+)$").find(text)?.groupValues?.get(1)
            ?: return null
        val parts = row.split('|')
        if (parts.size !in 1..2) return null
        val tool = parts[0].trim()
        if (tool !in allowedTools || !MobileToolRegistry.supports(tool)) return null
        val effectField = parts.getOrNull(1)?.trim().orEmpty()
        val timeMatches = Regex("@\\s*(\\d+)\\s*-\\s*(\\d+)").findAll(effectField).toList()
        val ordinalMatches = Regex("#\\s*(\\d+)").findAll(effectField).toList()
        if (timeMatches.size > 1 || ordinalMatches.size > 1) return null
        if (timeMatches.isNotEmpty() && tool != "clip_trim") return null
        if (ordinalMatches.isNotEmpty() && tool == "template_collage") return null
        val timeMatch = timeMatches.singleOrNull()
        val ordinalMatch = ordinalMatches.singleOrNull()
        val effect = effectField
            .replace(Regex("@\\s*\\d+\\s*-\\s*\\d+"), "")
            .replace(Regex("#\\s*\\d+"), "")
            .trim()
            .takeUnless { it.equals("effect", ignoreCase = true) }
            .orEmpty()
        val args = JSONObject()
        if (effect.isNotEmpty()) args.put("effect", effect)
        timeMatch?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { args.put("start_ms", it) }
        timeMatch?.groupValues?.getOrNull(2)?.toLongOrNull()?.let { args.put("end_ms", it) }

        val assets = state.sourceAssets
        val selected = state.selectedAssetIds.filter { id -> assets.any { it.assetId == id } }
        val ordinal = ordinalMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (ordinalMatch != null && (ordinal == null || ordinal !in 1..assets.size)) return null
        val ordinalId = ordinal?.takeIf { it in 1..assets.size }?.let { assets[it - 1].assetId }
        val scope = when {
            tool == "layout_swap" -> selected.takeIf { it.size == 2 } ?: return null
            tool == "asset_replace" -> selected.takeIf { it.size == 1 } ?: return null
            tool == "template_collage" -> assets.map { it.assetId }
            ordinalId != null && (selected.isEmpty() || ordinalId in selected) -> listOf(ordinalId)
            selected.isNotEmpty() -> selected
            else -> assets.map { it.assetId }
        }
        if (scope.isNotEmpty()) args.put("asset_ids", JSONArray(scope))
        if (tool == "asset_replace" && effect.isNotEmpty()) args.put("query", effect)
        if (tool == "template_collage") {
            args.put("layout", "vertical")
            args.put("canvas", "1080x1440")
        }
        return MobileToolCall(tool, args, effect)
    }

    private fun parse(
        text: String,
        query: String,
        assets: List<PreparedAsset>,
        selectedAssetIds: List<String> = emptyList()
    ): MobilePlan? {
        // There is no ACTION header: the field name itself states the turn's class, which is the
        // only thing the header ever carried. Across 124 plan-stage turns the model kept
        // ACTION= just 17% of the time while emitting coherent STEP lines without it 59% of the
        // time, so the header was a gate the brain almost always failed -- and one that cost 23
        // turns outright, because the prompt never named QUESTION= while parse() required it, so
        // every ACTION=CLARIFY fell through to the keyword tables
        // (planner_reliability_plan.md §7.7, defect A).
        //
        // A question or a reply outranks the steps: when the brain is unsure it must ask rather
        // than edit the user's media on a coin flip. CLARIFICATION= is accepted as an alias
        // because that is the name the model reached for unprompted on device.
        val question = field(text, "QUESTION").ifBlank { field(text, "CLARIFICATION") }
        question.takeIf { it.isNotBlank() }?.let {
            return MobilePlan(query, "clarify", assets.map { it.assetId }, emptyList(), it)
        }
        field(text, "REPLY").takeIf { it.isNotBlank() }?.let { reply ->
            return MobilePlan(query, "chat", assets.map { it.assetId }, emptyList(), reply)
        }
        if (!Regex("(?im)^\\s*STEP\\s*=").containsMatchIn(text)) return null
        val selected = selectedAssetIds.filter { id -> assets.any { it.assetId == id } }
        val selectedSet = selected.toSet()
        val calls = Regex("(?im)^\\s*STEP\\s*=\\s*(.+)$").findAll(text).mapNotNull { match ->
            val parts = match.groupValues[1].split('|')
            // P2a defines exactly one or two fields. Silently truncating extra pipes turned
            // `video_vertical_stack|cat#1|cat#2|cat#3` into a one-input video operation; rejecting
            // the malformed row lets the deterministic collage fallback recover safely instead.
            if (parts.size !in 1..2) return@mapNotNull null
            val tool = parts.getOrNull(0)?.trim().orEmpty()
            // search_assets is a retrieval pre-step the runner already consumed while resolving
            // URIs; it has no execution mapping, so a genie STEP for it must never become a
            // runnable call (it would fall through to the native-mapping crash).
            if (tool.isBlank() || tool == "search_assets" || !MobileToolRegistry.supports(tool)) return@mapNotNull null
            val args = JSONObject()
            // Two mandatory slots, not six. Time and ordinal ride as optional suffixes on the
            // effect field (@start-end ms, #ordinal) because a mandatory positional slot is
            // indistinguishable from a literal value to a 4B model: start_ms/end_ms/ordinal/
            // evidence all echoed their own placeholder names 50% of the time, and the shift
            // pushed real content into the wrong column (STEP=video_blur|start_ms|end_ms|1|视频背景虚化).
            // Only clip_trim reads start_ms/end_ms at all (MobileAgentRunner.kt:276), so forcing
            // them on 13 video tools was manufacturing the echo. PhantomFill 2607.20492.
            val effectField = parts.getOrNull(1)?.trim().orEmpty()
            val timeMatch = Regex("@\\s*(\\d+)\\s*-\\s*(\\d+)").find(effectField)
            val ordinalMatch = Regex("#\\s*(\\d+)").find(effectField)
            val effect = effectField
                .replace(Regex("@\\s*\\d+\\s*-\\s*\\d+"), "")
                .replace(Regex("#\\s*\\d+"), "")
                .trim()
                // The placeholder name is not a style; a STEP that echoed it carries no effect.
                .takeUnless { it.equals("effect", ignoreCase = true) }
                .orEmpty()
            val start = timeMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val end = timeMatch?.groupValues?.get(2)?.toLongOrNull() ?: 0L
            val input = ordinalMatch?.groupValues?.get(1)?.toIntOrNull()
            if (effect.isNotEmpty()) args.put("effect", effect)
            if (start > 0) args.put("start_ms", start)
            if (end > 0) args.put("end_ms", end)

            // The ordinal the model emits indexes 当前素材, so it can point outside the user's
            // tap-selection. Selection is the source of truth: resolve the ordinal, then clamp it
            // back into the selection so a hallucinated index can never edit an unselected asset.
            val ordinalTarget = input?.takeIf { it > 0 && it <= assets.size }
                ?.let { assets[it - 1].assetId }
                ?.takeIf { selectedSet.isEmpty() || it in selectedSet }
            val scope: List<String> = when {
                // Both selection tools are arity-strict at execution time (swap needs exactly two
                // ids, replace exactly one target), and the model only ever emits a single ordinal
                // per step. Take the ids from the selection, which is what actually has that arity.
                tool == "layout_swap" -> selected.takeIf { it.size == 2 } ?: return@mapNotNull null
                tool == "asset_replace" -> selected.takeIf { it.size == 1 } ?: return@mapNotNull null
                tool == "template_collage" -> assets.map { it.assetId }
                ordinalTarget != null -> listOf(ordinalTarget)
                selected.isNotEmpty() -> selected
                else -> assets.map { it.assetId }
            }
            val scopedAssets = assets.filter { it.assetId in scope }
            if (!MobileToolRegistry.compatible(tool, scopedAssets)) return@mapNotNull null
            args.put("asset_ids", JSONArray(scope))

            // asset_replace reuses the effect slot to carry the retrieval phrase, because the STEP
            // format has no query field. The planner was told to put the *content* description
            // there ("海边玩的照片"), not a filter name.
            if (tool == "asset_replace" && effect.isNotEmpty()) args.put("query", effect)

            if (tool == "template_collage") {
                if (!args.has("layout")) args.put("layout", "vertical")
                if (!args.has("canvas")) args.put("canvas", "1080x1440")
            }
            // The evidence slot is gone: it fed only this display string and the audit, never an
            // executor, yet it cost 1/6 of the output budget and echoed its placeholder 50% of the
            // time -- degrading to "用户原话" and "用户原话证据：把颜色调鲜艳一些". The effect is the
            // brain's own description of the step and is strictly more informative.
            MobileToolCall(tool, args, effect)
        }.toList()
        // Collage composes the whole cast in one operation, yet the STEP format invites one line
        // per asset ordinal -- the brain happily emitted five identical collage steps for five
        // assets, which would run five full exports. The two selection tools are arity-strict
        // singletons too (swap needs exactly two taps, replace exactly one), so a repeated line is
        // always the model pattern-copying the template: two layout_swaps cancel out and leave the
        // collage unchanged, exactly the "swap did nothing" bug. Collapse consecutive duplicates.
        val singletonOps = setOf("template_collage", "layout_swap", "asset_replace")
        val deduped = calls.fold(mutableListOf<MobileToolCall>()) { acc, call ->
            if (call.tool in singletonOps && acc.lastOrNull()?.tool == call.tool) acc
            else acc.apply { add(call) }
        }
        if (deduped.isEmpty()) return null
        return MobilePlan(query, "qnn_genie", assets.map { it.assetId }, deduped)
    }

    /**
     * Runs the intent arm selected at build time.
     *
     * `scratchpad` is deliberately not called native thinking: the deployed
     * Qwen3-4B-Instruct-2507 model is officially non-thinking-only. It is a one-call, visible
     * free-form draft followed by JSON. `two_turn` gives the draft and formatter separate model
     * calls, which is the actual reasoning/formatting decoupling evaluated by The Format Tax.
     */
    private fun generateIntent(query: String, selectedAssetIds: List<String>): GeneratedText = when (experiment) {
        Experiment.BASELINE -> {
            val prompt = baselineIntentPrompt(query, selectedAssetIds)
            val run = timedRunText(prompt, 256)
            GeneratedText(run.text, promptChars = prompt.length, elapsedMs = run.elapsedMs)
        }
        Experiment.P1 -> {
            val prompt = cleanIntentPrompt(query, selectedAssetIds)
            val run = timedRunText(prompt, 256)
            GeneratedText(run.text, promptChars = prompt.length, elapsedMs = run.elapsedMs)
        }
        Experiment.SCRATCHPAD -> {
            val prompt = scratchpadIntentPrompt(query, selectedAssetIds)
            val run = timedRunText(prompt, 512)
            val (reasoning, finalText) = splitFinal(run.text, "FINAL_JSON")
            GeneratedText(
                finalText = finalText,
                reasoningText = reasoning,
                promptChars = prompt.length,
                elapsedMs = run.elapsedMs
            )
        }
        Experiment.TWO_TURN -> {
            val reasoningPrompt = freeIntentPrompt(query, selectedAssetIds)
            val reasoning = timedRunText(reasoningPrompt, 384)
            val formatPrompt = formatIntentPrompt(query, reasoning.text)
            val formatted = timedRunText(formatPrompt, 256)
            GeneratedText(
                finalText = formatted.text,
                reasoningText = reasoning.text,
                promptChars = reasoningPrompt.length + formatPrompt.length,
                elapsedMs = reasoning.elapsedMs + formatted.elapsedMs,
                reasoningElapsedMs = reasoning.elapsedMs,
                formatElapsedMs = formatted.elapsedMs
            )
        }
    }

    /** Same four arms for the post-retrieval plan, where STEP formatting is the constraint. */
    private fun generatePlan(
        query: String,
        assets: List<PreparedAsset>,
        selectedAssetIds: List<String>
    ): GeneratedText = when (experiment) {
        Experiment.BASELINE, Experiment.P1 -> {
            val prompt = baselinePlanPrompt(query, assets, selectedAssetIds)
            val run = timedRunText(prompt, 384)
            GeneratedText(run.text, promptChars = prompt.length, elapsedMs = run.elapsedMs)
        }
        Experiment.SCRATCHPAD -> {
            val prompt = scratchpadPlanPrompt(query, assets, selectedAssetIds)
            val run = timedRunText(prompt, 512)
            val (reasoning, finalText) = splitFinal(run.text, "FINAL_STEPS")
            GeneratedText(
                finalText = finalText,
                reasoningText = reasoning,
                promptChars = prompt.length,
                elapsedMs = run.elapsedMs
            )
        }
        Experiment.TWO_TURN -> {
            val reasoningPrompt = freePlanPrompt(query, assets, selectedAssetIds)
            val reasoning = timedRunText(reasoningPrompt, 384)
            val formatPrompt = formatPlanPrompt(query, reasoning.text, assets.size, selectedAssetIds.size)
            val formatted = timedRunText(formatPrompt, 384)
            GeneratedText(
                finalText = formatted.text,
                reasoningText = reasoning.text,
                promptChars = reasoningPrompt.length + formatPrompt.length,
                elapsedMs = reasoning.elapsedMs + formatted.elapsedMs,
                reasoningElapsedMs = reasoning.elapsedMs,
                formatElapsedMs = formatted.elapsedMs
            )
        }
    }

    private fun timedRunText(prompt: String, maxTokens: Int): TimedText {
        val started = System.currentTimeMillis()
        val text = runText(prompt, maxTokens, 30L).orEmpty()
        return TimedText(text, System.currentTimeMillis() - started)
    }

    private fun generateRuntimeText(
        prompt: String,
        directMaxTokens: Int,
        stage: RuntimeStage
    ): GeneratedText {
        val hybrid = BuildConfig.QWEN_MODEL_PACK == "hybrid"
        val thinking = hybrid && when (BuildConfig.QWEN_THINKING) {
            "all" -> true
            "route" -> stage in setOf(RuntimeStage.ROUTE, RuntimeStage.RETRIEVAL, RuntimeStage.REVISE)
            else -> false
        }
        // This is the exact no-thinking assistant prefix generated by Qwen3's official Jinja
        // template. Thinking mode uses the ordinary assistant prefix and lets the model emit its
        // native <think> block; no prompt-induced scratchpad is involved.
        val controlledPrompt = when {
            hybrid && thinking -> "${enableNativeThinking(prompt)}\n<think>\n"
            hybrid -> "$prompt\n<think>\n\n</think>\n\n"
            else -> prompt
        }
        val maxTokens = if (thinking) BuildConfig.QWEN_THINKING_TOKENS else directMaxTokens
        val started = System.currentTimeMillis()
        val raw = runText(controlledPrompt, maxTokens, if (thinking) 120L else 30L).orEmpty()
        val elapsed = System.currentTimeMillis() - started
        val (reasoning, finalText) = if (thinking) splitNativeThinking(raw) else "" to raw
        return GeneratedText(
            finalText = finalText,
            reasoningText = reasoning,
            promptChars = controlledPrompt.length,
            elapsedMs = elapsed,
            reasoningElapsedMs = if (thinking) elapsed else 0L,
            formatElapsedMs = if (thinking) 0L else elapsed
        )
    }

    private fun enableNativeThinking(prompt: String): String {
        val userEnd = prompt.lastIndexOf("<|im_end|>")
        if (userEnd < 0) return "$prompt\n/think"
        val instruction = """

            /think
            请先在隐藏的思考区核对用户主体、动作、否定约束和输出格式；思考结束后的最终答案仍须严格遵守上述短结构，不能附带解释。
        """.trimIndent()
        return prompt.substring(0, userEnd) + instruction + "\n" + prompt.substring(userEnd)
    }

    private fun splitNativeThinking(text: String): Pair<String, String> {
        val close = text.lastIndexOf("</think>")
        if (close < 0) {
            // A token-budget-truncated thought is not an executable answer. Preserve it for audit
            // and return an empty final section so parser success cannot be fabricated.
            return text.removePrefix("<think>").trim() to ""
        }
        val reasoning = text.substring(0, close)
            .substringAfterLast("<think>")
            .trim()
        val finalText = text.substring(close + "</think>".length).trim()
        return reasoning to finalText
    }

    private fun runtimeStrategy(): String = buildString {
        append("runtime_loop:")
        append(BuildConfig.QWEN_MODEL_PACK)
        append(':')
        append(BuildConfig.QWEN_THINKING)
        if (BuildConfig.QWEN_MODEL_PACK == "hybrid" && BuildConfig.QWEN_THINKING != "off") {
            append(':')
            append(BuildConfig.QWEN_THINKING_TOKENS)
        }
    }

    /**
     * Separates a one-call scratchpad from its final answer without feeding the scratchpad to the
     * parser. If the model drops the marker, the first JSON/STEP/QUESTION/REPLY token is used as a
     * conservative boundary. An output with no recognisable final section remains unparseable.
     */
    private fun splitFinal(text: String, marker: String): Pair<String, String> {
        val marked = Regex("(?im)^\\s*${Regex.escape(marker)}\\s*[:=：]\\s*").find(text)
        if (marked != null) {
            return text.substring(0, marked.range.first).trim() to text.substring(marked.range.last + 1).trim()
        }
        val boundaries = listOfNotNull(
            text.indexOf('{').takeIf { it >= 0 },
            Regex("(?im)^\\s*(STEP|QUESTION|CLARIFICATION|REPLY)\\s*[=:：]").find(text)?.range?.first
        )
        val boundary = boundaries.minOrNull() ?: return text.trim() to ""
        return text.substring(0, boundary).trim() to text.substring(boundary).trim()
    }

    private fun runText(prompt: String, maxTokens: Int, timeoutSeconds: Long): String? {
        val model = ModelPack.executionRoot(context, ModelPack.qwenRoot(context))
        val runner = ModelPack.genieRunner(context, model) ?: return null
        val work = File(context.cacheDir, "planner-${System.nanoTime()}").apply { mkdirs() }
        val promptFile = File(work, "prompt.txt")
        val outputFile = File(work, "output.txt")
        val scriptFile = File(work, "script.txt")
        promptFile.writeText(prompt, Charsets.UTF_8)
        scriptFile.writeText(
            "dialog config create cfg ${File(model, "genie_config.json").absolutePath}\n" +
                "dialog create d cfg\ndialog setMaxNumTokens d $maxTokens\n" +
                "dialog query d ${promptFile.absolutePath} ${outputFile.absolutePath}\n" +
                "dialog free d\ndialog config free cfg\nexit\n", Charsets.UTF_8
        )
        return try {
            val process = ProcessBuilder(runner.absolutePath, "-s", scriptFile.absolutePath)
                .directory(model).redirectErrorStream(true).apply {
                    environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir + ":" +
                        ModelPack.runtimeLibRoot(context, model).absolutePath + ":/vendor/lib64:/vendor/lib64/hw"
                    environment()["ADSP_LIBRARY_PATH"] = File(model, "runtime/dsp").absolutePath + ";/vendor/dsp/cdsp"
                }.start()
            // 30s, not 90s: a hung planner used to cost the user 90s before the keyword fallback
            // could take over, and two calls per turn stacked into a ~170s ceiling. Real plans
            // arrive in 6-11s, so 30s already leaves 3x headroom.
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS) || !outputFile.isFile) {
                process.destroyForcibly()
                null
            } else outputFile.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        } finally {
            work.deleteRecursively()
        }
    }

    /** Exact pre-P1 prompt, retained as the control arm. */
    private fun baselineIntentPrompt(query: String, selectedAssetIds: List<String>): String = """
        <|im_start|>system
         你是 Live Photo Agent 的意图规划器。严格输出 JSON，不要 Markdown。
        先规划工具链，不要直接挑图片，不要凭空生成 asset_id。
        如果用户要求按内容找素材，必须先调用 search_assets，arguments 包含 query 和 k；
        随后需要拼接时调用 template_collage。用户没有缺失关键信息时不要 CLARIFY。
        如果用户只是在闲聊、提问、打招呼或询问你的能力，这不是编辑请求：不要调用任何工具，
        输出 {"intent":"chat","reply":"用一句话直接回答用户"}，tool_calls 留空。
        工具：
        ${MobileToolRegistry.promptCatalog()}
        JSON：{"user_goal":"...","intent":"...","tool_calls":[{"tool":"search_assets","reason":"...","arguments":{"query":"...","k":3}}]}
         <|im_end|>
         <|im_start|>user
         用户请求：$query
         已明确选择的 asset_id（可为空）：${selectedAssetIds.joinToString(",")}
         当前会话上下文：${sessionContext()}
         <|im_end|>
        <|im_start|>assistant
    """.trimIndent()

    /**
     * P1 prompt: each semantic rule is stated once and no example contains a value that can be
     * copied as an answer. The old `"reply":"用一句话直接回答用户"` was echoed verbatim for
     * D005, and the runner treated that instruction text as a real chat reply.
     */
    private fun cleanIntentPrompt(query: String, selectedAssetIds: List<String>): String = """
        <|im_start|>system
        你是 Live Photo Agent 的意图规划器。只输出一个 JSON 对象，不要 Markdown。
        先判断用户是在编辑媒体，还是在闲聊、提问、打招呼、询问能力。
        编辑请求：intent 写 plan。按内容找素材时，tool_calls 的第一个调用必须是 search_assets，
        arguments 中 query 是要找的内容，k 是数量；还要求拼接时，随后调用 template_collage。
        非编辑请求：intent 写 chat，tool_calls 写空数组，reply 必须是你现在要直接回答用户的真实内容。
        用户没有缺失关键信息时不要追问。不要挑选或编造 asset_id。
        顶层字段只使用 user_goal、intent、tool_calls，以及非编辑请求所需的 reply。
        tool_calls 的每一项只使用 tool、reason、arguments。
        可用工具：
        ${MobileToolRegistry.promptCatalog()}
        <|im_end|>
        <|im_start|>user
        用户请求：$query
        已明确选择的 asset_id（可为空）：${selectedAssetIds.joinToString(",")}
        当前会话上下文：${sessionContext()}
        <|im_end|>
        <|im_start|>assistant
    """.trimIndent()

    /** One-call visible scratchpad followed by a JSON section. Not native model thinking. */
    private fun scratchpadIntentPrompt(query: String, selectedAssetIds: List<String>): String = """
        <|im_start|>system
        你是 Live Photo Agent 的意图规划器。
        先用简短自然语言分析用户真实意图、是否需要检索、需要哪些工具。分析阶段不要写 JSON 或大括号。
        分析完成后另起一行写 FINAL_JSON:，其后只输出一个 JSON 对象，不要 Markdown。
        编辑请求的 intent 写 plan。按内容找素材时先调用 search_assets，arguments 含 query 和 k；
        还要求拼接时随后调用 template_collage。非编辑请求的 intent 写 chat、tool_calls 写空数组，
        reply 写直接给用户的真实回答。不要挑选或编造 asset_id。
        顶层字段只使用 user_goal、intent、tool_calls，以及非编辑请求所需的 reply；
        tool_calls 每项只使用 tool、reason、arguments。
        可用工具：
        ${MobileToolRegistry.promptCatalog()}
        <|im_end|>
        <|im_start|>user
        用户请求：$query
        已明确选择的 asset_id（可为空）：${selectedAssetIds.joinToString(",")}
        当前会话上下文：${sessionContext()}
        <|im_end|>
        <|im_start|>assistant
    """.trimIndent()

    /** First turn of true 2-Turn: solve the semantic task without any output schema. */
    private fun freeIntentPrompt(query: String, selectedAssetIds: List<String>): String = """
        <|im_start|>system
        你负责理解 Live Photo Agent 的用户请求。请用简短自然语言自由说明：
        这是媒体编辑还是普通对话；若是普通对话，应该怎样直接回答；若是编辑，需要依次使用哪些工具，
        搜索什么内容、多少张。不要输出 JSON、代码或 asset_id，不要受结构化格式约束。
        可用工具：
        ${MobileToolRegistry.promptCatalog()}
        <|im_end|>
        <|im_start|>user
        用户请求：$query
        已明确选择的 asset_id（可为空）：${selectedAssetIds.joinToString(",")}
        当前会话上下文：${sessionContext()}
        <|im_end|>
        <|im_start|>assistant
    """.trimIndent()

    /** Second turn of true 2-Turn: mechanically format the already-decided free plan. */
    private fun formatIntentPrompt(query: String, freePlan: String): String = """
        <|im_start|>system
        你是格式转换器，不重新解释请求，也不改变自由规划中的决定。只输出一个 JSON 对象，不要 Markdown。
        编辑请求的 intent 写 plan；普通对话的 intent 写 chat、tool_calls 写空数组，并把自由规划中的实际回答写入 reply。
        顶层字段只使用 user_goal、intent、tool_calls，以及普通对话所需的 reply。
        tool_calls 每项只使用 tool、reason、arguments；search_assets.arguments 使用 query 和 k。
        tool 必须逐字沿用自由规划中已经选好的工具 ID，不新增、替换或删除工具。
        <|im_end|>
        <|im_start|>user
        原始请求：$query
        待格式化的自由规划：
        ---
        $freePlan
        ---
        <|im_end|>
        <|im_start|>assistant
    """.trimIndent()

    /**
     * Reads a `NAME=value` line, tolerating the colon variants the model actually emits.
     *
     * Audit data shows both ASCII and full-width separators in the wild (`用户原话证据:` and
     * `用户原话证据：`). Since the field name is now the only thing that identifies a turn's
     * class, a separator mismatch would silently discard a valid QUESTION or REPLY and drop the
     * turn to the keyword tables.
     */
    private fun field(text: String, name: String): String =
        Regex("(?im)^\\s*$name\\s*[=:：]\\s*(.*)$")
            .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    private data class PlanPromptContext(
        val catalog: String,
        val assetLines: String,
        val scopeRule: String
    )

    private fun planPromptContext(
        assets: List<PreparedAsset>,
        selectedAssetIds: List<String>
    ): PlanPromptContext {
        val selected = selectedAssetIds.toSet()
        // Tool visibility follows the concrete media scope. Offering video_vertical_stack for
        // three JPEGs made the 4B planner choose it for "三拼", after which the video SDK received
        // one image and failed with "SDK does not support video: null". This filter removes
        // impossible actions; it does not decide which of the remaining actions the user wants.
        val scopedAssets = if (selected.isEmpty()) assets else assets.filter { it.assetId in selected }
        val catalog = MobileToolRegistry.promptCatalog(selected.size, scopedAssets)
        // Marking the tapped assets is what lets the planner scope an edit to them. Selection is
        // UI state it cannot otherwise observe, and without the marker it plans against the whole
        // cast -- which is how a single-asset request used to silently edit everything.
        val assetLines = assets.joinToString("\n") { asset ->
            val tag = if (asset.assetId in selected) "[已选中] " else ""
            "$tag${asset.promptDescription()}"
        }
        val scopeRule = if (selected.isNotEmpty()) {
            "用户已选中 ${selected.size} 个素材（下方标有[已选中]）。指令默认只作用于这些素材，#序号 只能填[已选中]的序号。"
        } else {
            "用户未选中素材，指令作用于当前全部素材，不需要 #序号。"
        }
        return PlanPromptContext(catalog, assetLines, scopeRule)
    }

    /**
     * Exact P2a plan prompt used by both the baseline and P1-only arms.
     *
     * `/no_think` is retained here solely to keep that control prompt byte-compatible. It does
     * not toggle a mode in Qwen3-4B-Instruct-2507, whose official model card says the weights are
     * non-thinking-only.
     */
    private fun baselinePlanPrompt(
        query: String,
        assets: List<PreparedAsset>,
        selectedAssetIds: List<String>
    ): String {
        val promptContext = planPromptContext(assets, selectedAssetIds)
        return """
               <|im_start|>system
            你是手机端 Live Photo Agent，只能选择注册表中的工具。不要输出 Markdown。
            按本轮意图只输出下面三种之一，字段名即意图，不要输出别的字段：
            1) 要编辑：每个动作一行 STEP=工具ID|effect
               effect 填用户说的效果名或内容描述；不需要效果的工具留空即可。
               只有裁剪(clip_trim)需要时间，在 effect 后加 @起始毫秒-结束毫秒，例如 STEP=clip_trim|@0-3000
               需要指定单个素材时在 effect 后加 #序号，例如 STEP=color_tone|鲜艳#2
            2) 信息不足、需要追问：只输出一行 QUESTION=你的追问，不要输出 STEP。
            3) 只是闲聊、提问、打招呼或问你能做什么：只输出一行 REPLY=你的回答，不要输出 STEP。
            只规划本轮用户原话中明确要求的动作。历史上下文仅用于理解指代（这张/它/中间那个），
            不要重复执行历史轮次已完成的动作。
            ${promptContext.scopeRule}
            区分两类"换"：用户想改变素材本身的画面内容（换成另一张照片里的东西）才用
            asset_replace，effect 填那一张照片的内容描述（人物、场景、动物等名词短语）；
            用户想改变素材的颜色、色调、滤镜或风格时，素材没有换，只是调色，
            用 color_tone、video_filter 或 live_photo_filter_effect，effect 原样填用户说的风格名。
            每个动作只输出一行 STEP，不要重复输出同一动作。
            工具注册表：
            ${promptContext.catalog}
             <|im_end|>
             <|im_start|>user
            当前素材：
            ${promptContext.assetLines}
            最近对话上下文：${sessionContext()}
            用户原话：$query
            /no_think<|im_end|>
             <|im_start|>assistant
        """.trimIndent()
    }

    /** One generation with an unconstrained visible draft before the final STEP protocol. */
    private fun scratchpadPlanPrompt(
        query: String,
        assets: List<PreparedAsset>,
        selectedAssetIds: List<String>
    ): String {
        val promptContext = planPromptContext(assets, selectedAssetIds)
        return """
            <|im_start|>system
            你是手机端 Live Photo Agent，只能选择注册表中的工具。
            先用简短自然语言分析本轮意图、素材类型、作用范围和所需工具。分析阶段不要写 STEP/QUESTION/REPLY。
            分析完成后另起一行写 FINAL_STEPS:，其后只能输出以下三种最终格式之一，不要 Markdown：
            编辑：每个动作一行 STEP=工具ID|effect；无 effect 可只写工具ID。
            只有 clip_trim 需要时间后缀 @起始毫秒-结束毫秒；单个素材可加 #序号。
            信息不足：QUESTION=你的追问。普通对话：REPLY=你的回答。
            只规划本轮明确动作；历史只用于理解指代，不重复历史动作。
            ${promptContext.scopeRule}
            只有更换画面内容才用 asset_replace，effect 写新素材的内容描述；
            改颜色、色调、滤镜或风格使用调色工具，effect 写用户的风格名。
            同一动作不要重复。
            工具注册表：
            ${promptContext.catalog}
            <|im_end|>
            <|im_start|>user
            当前素材：
            ${promptContext.assetLines}
            最近对话上下文：${sessionContext()}
            用户原话：$query
            <|im_end|>
            <|im_start|>assistant
        """.trimIndent()
    }

    /** First call of plan-stage 2-Turn: understand and choose tools with no output schema. */
    private fun freePlanPrompt(
        query: String,
        assets: List<PreparedAsset>,
        selectedAssetIds: List<String>
    ): String {
        val promptContext = planPromptContext(assets, selectedAssetIds)
        return """
            <|im_start|>system
            你负责为手机端 Live Photo Agent 制定计划。请用简短自然语言自由说明本轮真实意图、
            作用于哪些素材、按顺序调用哪些工具及各自效果。不要输出 JSON、STEP 或其他固定格式。
            只规划本轮明确动作；历史只用于理解指代，不重复历史动作。
            ${promptContext.scopeRule}
            只有更换画面内容才用 asset_replace；改颜色、色调、滤镜或风格使用调色工具。
            同一动作只规划一次。只能选择注册表中的工具：
            ${promptContext.catalog}
            <|im_end|>
            <|im_start|>user
            当前素材：
            ${promptContext.assetLines}
            最近对话上下文：${sessionContext()}
            用户原话：$query
            <|im_end|>
            <|im_start|>assistant
        """.trimIndent()
    }

    /** Second call of plan-stage 2-Turn: format only; semantic decisions stay in [freePlan]. */
    private fun formatPlanPrompt(
        query: String,
        freePlan: String,
        assetCount: Int,
        selectedCount: Int
    ): String = """
        <|im_start|>system
        你是格式转换器，不重新解释请求，不增删或改变自由规划中的动作。不要输出 Markdown。
        将编辑计划转换为每个动作一行 STEP=工具ID|effect；无 effect 可只写工具ID。
        clip_trim 的时间写成 effect 后缀 @起始毫秒-结束毫秒；单个素材序号写成 #序号。
        若自由规划判断信息不足，只输出 QUESTION=追问；若判断为普通对话，只输出 REPLY=回答。
        同一动作只输出一次。当前共 $assetCount 个素材，已选中 $selectedCount 个。
        工具 ID 必须逐字沿用自由规划，不新增、替换或删除动作。
        <|im_end|>
        <|im_start|>user
        原始请求：$query
        待格式化的自由规划：
        ---
        $freePlan
        ---
        <|im_end|>
        <|im_start|>assistant
    """.trimIndent()

    private fun runtimeRoutePrompt(
        query: String,
        state: AgentRuntimeState,
        correction: String
    ): String {
        val materialState = if (state.sourceAssets.isEmpty()) {
            "当前没有用户指定素材；媒体编辑路线必须先 RETRIEVE。"
        } else {
            val formats = state.sourceAssets.groupingBy {
                it.metadata.optString("media_format", "unknown")
            }.eachCount().entries.joinToString { "${it.key}:${it.value}" }
            "当前已有 ${state.sourceAssets.size} 个素材（$formats），不要规划 RETRIEVE。"
        }
        val families = runtimeRouteFamilies(state).joinToString("、") { it.name }
        val correctionLine = correction.takeIf { it.isNotBlank() }
            ?.let { "上一次输出无效，必须修正：$it" }.orEmpty()
        return """
            <|im_start|>system
            你是手机端 Live Photo Agent 的语义路线规划器。直接输出短结构，不要 Markdown，
            不要写分析过程，也不要选择具体 SDK 工具。

            普通聊天、提问或能力询问：只输出 REPLY=直接回答。
            缺少只有用户才能补充的信息：只输出 QUESTION=追问。
            媒体任务：按执行顺序每步一行 ROUTE=能力族|用户要求的内容|数量|媒体类型。
            后两个字段可省略；不要输出空占位符。最多 8 步，同一动作只写一次。
            一旦输出 ROUTE，就绝对不要同时输出 REPLY 或 QUESTION；本地相册可由 RETRIEVE
            自动查找，不要追问用户提供素材。每一行都必须以 ROUTE= 开头，包括 PUBLISH。

            本轮允许的能力族只有：$families。
            RETRIEVE 找本地素材；COMPOSE 拼贴/转场/画布组合；STYLE 滤镜、色调和画面风格；
            TEMPORAL 裁剪、倒放、变速、镜像和时间效果；LAYOUT 调整已有布局；
            REPLACE 仅表示用户明确要求替换当前单选素材；ANALYZE 摘要或主色分析；
            PUBLISH 发布最终媒体。没有明确“替换/换掉”要求时绝不能规划 REPLACE。

            $materialState
            $correctionLine
            路线必须覆盖用户本轮明确要求的全部动作，但不要增加贴纸、滤镜等未要求动作。
            “拼、拼接、拼图、几拼”必须包含 COMPOSE；滤镜/颜色风格包含 STYLE；倒放/变速/
            裁剪/镜像包含 TEMPORAL；不能只写 RETRIEVE 和 PUBLISH 而漏掉中间编辑动作。
            会产生最终媒体的编辑路线最后加 PUBLISH；仅分析或聊天不需要 PUBLISH。
            只有用户明确说了图片、视频或 Live Photo 时才填写媒体类型，否则省略该字段。
            <|im_end|>
            <|im_start|>user
            用户原话：$query
            当前选中素材数：${state.selectedAssetIds.size}
            最近对话上下文：${sessionContext()}
            <|im_end|>
            <|im_start|>assistant
        """.trimIndent()
    }

    private fun runtimeRetrievalPrompt(
        query: String,
        requestedCount: Int,
        requiredMedia: RuntimeMediaKind?
    ): String {
        return """
            <|im_start|>system
            你只负责从用户原话中提取“相册里要找什么画面内容”。直接输出一行，不要解释：
            这一行必须以 SEARCH= 开头，等号后只写要寻找的画面主体名词短语，不写其他字段。
            画面主体必须是人物、动物、物体、地点、场景或动作等可在画面中看到的
            名词短语；必须删除“三拼、拼图、滤镜、倒放、导出、裁剪”等编辑动作和格式词。
            不要回答用户，不要输出 ROUTE、STEP、QUESTION、Markdown，也不要凭空增加画面内容。
            数量 $requestedCount 和媒体约束 ${requiredMedia?.name ?: "未限定"} 已由运行时保存，
            不要在输出中重复数量或媒体类型。
            <|im_end|>
            <|im_start|>user
            用户原话：$query
            <|im_end|>
            <|im_start|>assistant
        """.trimIndent()
    }

    private fun runtimeRouteFamilies(state: AgentRuntimeState): Set<ToolFamily> = buildSet {
        add(ToolFamily.RETRIEVE)
        add(ToolFamily.COMPOSE)
        add(ToolFamily.STYLE)
        add(ToolFamily.TEMPORAL)
        add(ToolFamily.ANALYZE)
        add(ToolFamily.PUBLISH)
        if (state.sourceAssets.isNotEmpty() && state.selectedAssetIds.size == 1) {
            add(ToolFamily.REPLACE)
        }
        if (state.layout != null && state.selectedAssetIds.size == 2) {
            add(ToolFamily.LAYOUT)
        }
    }

    private fun runtimeBindPrompt(
        query: String,
        routeStep: SemanticRouteStep,
        state: AgentRuntimeState,
        catalog: ToolCatalogDecision,
        observation: String
    ): String {
        val assets = state.sourceAssets.mapIndexed { index, asset ->
            val selected = if (asset.assetId in state.selectedAssetIds) "[已选中]" else ""
            val summary = asset.semantic.optString("summary").take(100)
            "$selected 序号=${index + 1} media=${asset.metadata.optString("media_format", "unknown")} summary=$summary"
        }.joinToString("\n").ifBlank { "无" }
        val artifact = state.latestValidatedArtifact()?.let {
            "id=${it.artifactId}, kind=${it.kind}, producer=${it.producerStepId}, validated=${it.validated}"
        } ?: "无"
        val observationLine = observation.ifBlank { "无" }
        val suffixRule = if (routeStep.family == ToolFamily.TEMPORAL) {
            "只有用户明确要求裁剪且选择 clip_trim 时可写一个 @起始毫秒-结束毫秒；只有明确指向单个素材时可写一个 #序号。"
        } else {
            "本步骤禁止输出 @时间或 #序号；输入范围由运行时绑定。"
        }
        return """
            <|im_start|>system
            你负责把一个语义路线步骤绑定为当前可执行工具。不要重新规划已完成动作，不要 Markdown，
            不要输出分析过程。只能从给定目录选择一个工具，并只输出一行：
            STEP=工具ID|用户要求的效果
            不需要效果参数时可只写 STEP=工具ID。$suffixRule
            如果目录里没有能完成该步骤的工具，只输出 QUESTION=说明缺少什么。

            当前路线步骤：${routeStep.family}|${routeStep.instruction}
            可用工具（共 ${catalog.available.size} 个）：
            ${catalog.prompt()}
            <|im_end|>
            <|im_start|>user
            原始请求：$query
            当前素材：
            $assets
            当前已验证产物：$artifact
            最近观察或错误：$observationLine
            <|im_end|>
            <|im_start|>assistant
        """.trimIndent()
    }

    private fun runtimeRevisePrompt(
        query: String,
        state: AgentRuntimeState,
        completed: List<SemanticRouteStep>,
        pending: List<SemanticRouteStep>,
        observation: String
    ): String {
        val completedText = completed.joinToString(" > ") { "${it.family}(${it.instruction})" }
            .ifBlank { "无" }
        val pendingText = pending.joinToString(" > ") { "${it.family}(${it.instruction})" }
            .ifBlank { "无" }
        val media = state.sourceAssets.groupingBy {
            it.metadata.optString("media_format", "unknown")
        }.eachCount().entries.joinToString { "${it.key}:${it.value}" }.ifBlank { "无素材" }
        return """
            <|im_start|>system
            你是手机端 Live Photo Agent 的路线修订器。已完成前缀是事实，绝对不能重复或改写。
            根据最新工具结果，只输出尚未执行的新后缀；不要 Markdown，不要分析过程，不要具体 SDK 工具。
            每步格式：ROUTE=能力族|用户要求的内容|数量|媒体类型，后两个字段可省略。
            能力族只有 RETRIEVE、COMPOSE、STYLE、TEMPORAL、LAYOUT、REPLACE、ANALYZE、PUBLISH。
            无法在现有能力下继续时输出 QUESTION=需要用户决定的事项，或 REPLY=明确失败说明。
            最多 8 步，不要重复已完成动作。会产生最终媒体的后缀最终必须包含 PUBLISH。
            <|im_end|>
            <|im_start|>user
            原始请求：$query
            已完成前缀：$completedText
            原待执行后缀：$pendingText
            当前素材：${state.sourceAssets.size} 个（$media）
            最新观察或错误：$observation
            <|im_end|>
            <|im_start|>assistant
        """.trimIndent()
    }

    private fun sessionContext(): String = sessionId?.let {
        val database = LibraryDatabase(context)
        try {
            database.plannerContext(it)
        } finally {
            database.close()
        }
    }.orEmpty().ifBlank { "无" }
}
