package com.vivo.lpa

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Optional bridge to the target project's Qualcomm Genie text runner. */
class GenieTextPlanner(private val context: Context, private val sessionId: String? = null) {
    /**
     * The most recent brain attempt, kept even when it failed to parse or the process errored.
     *
     * A failed brain run is exactly the case an audit must capture: without the raw words there is
     * no way to tell a bad prompt from a bad model output. [MobilePlanner] attaches this to the
     * fallback plan so the trail survives the failure.
     */
    var lastTrace: BrainTrace? = null
        private set

    /** Web-agent style plan: search_assets/smart_collage is planned before media preparation. */
    fun tryIntent(query: String, selectedAssetIds: List<String> = emptyList()): MobilePlan? {
        if (!ModelPack.qwenReady(context)) return null
        val prompt = intentPrompt(query, selectedAssetIds)
        val started = System.currentTimeMillis()
        val raw = runText(prompt, 256).orEmpty()
        lastTrace = BrainTrace(
            source = "qnn_genie_intent",
            rawOutput = raw,
            promptChars = prompt.length,
            elapsedMs = System.currentTimeMillis() - started
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
            MobilePlan(
                userGoal = json.optString("user_goal", query),
                intent = json.optString("intent", "plan"),
                selectedAssetIds = selectedAssetIds,
                toolCalls = calls,
                clarification = json.optString("clarification", "").takeIf { it.isNotBlank() },
                brainTrace = lastTrace
            ).takeIf { it.toolCalls.isNotEmpty() || it.clarification != null }
        }.getOrNull()
    }

    fun tryPlan(
        query: String,
        assets: List<PreparedAsset>,
        selectedAssetIds: List<String> = emptyList()
    ): MobilePlan? {
        if (!ModelPack.qwenReady(context)) return null
        val prompt = prompt(query, assets, selectedAssetIds)
        val started = System.currentTimeMillis()
        val raw = runText(prompt, 384).orEmpty()
        lastTrace = BrainTrace(
            source = "qnn_genie",
            rawOutput = raw,
            promptChars = prompt.length,
            elapsedMs = System.currentTimeMillis() - started
        )
        val parsed = parse(raw, query, assets, selectedAssetIds) ?: return null
        lastTrace = lastTrace?.copy(parsed = true)
        return parsed.copy(brainTrace = lastTrace)
    }

    private fun parse(
        text: String,
        query: String,
        assets: List<PreparedAsset>,
        selectedAssetIds: List<String> = emptyList()
    ): MobilePlan? {
        val action = field(text, "ACTION").uppercase()
        if (action == "CLARIFY") {
            return MobilePlan(query, "clarify", assets.map { it.assetId }, emptyList(), field(text, "QUESTION"))
        }
        // The sampler (temp 0.8) sometimes drops the ACTION header while still emitting coherent
        // STEP lines -- captured verbatim by the PLAN_BRAIN audit. The steps alone state the intent
        // unambiguously, so accept them as a plan; only a response with neither header nor steps is
        // truly unusable.
        val hasSteps = Regex("(?im)^\\s*STEP\\s*=").containsMatchIn(text)
        if (action != "PLAN" && !hasSteps) return null
        val selected = selectedAssetIds.filter { id -> assets.any { it.assetId == id } }
        val selectedSet = selected.toSet()
        val calls = Regex("(?im)^\\s*STEP\\s*=\\s*(.+)$").findAll(text).mapNotNull { match ->
            val parts = match.groupValues[1].split('|')
            val tool = parts.getOrNull(0)?.trim().orEmpty()
            // search_assets is a retrieval pre-step the runner already consumed while resolving
            // URIs; it has no execution mapping, so a genie STEP for it must never become a
            // runnable call (it would fall through to the native-mapping crash).
            if (tool.isBlank() || tool == "search_assets" || !MobileToolRegistry.supports(tool)) return@mapNotNull null
            val args = JSONObject()
            val effect = parts.getOrNull(1)?.trim().orEmpty()
            val start = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
            val end = parts.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L
            val input = parts.getOrNull(4)?.trim()?.toIntOrNull()
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
                tool == "template_collage" || tool == "smart_collage" -> assets.map { it.assetId }
                ordinalTarget != null -> listOf(ordinalTarget)
                selected.isNotEmpty() -> selected
                else -> assets.map { it.assetId }
            }
            args.put("asset_ids", JSONArray(scope))

            // asset_replace reuses the effect slot to carry the retrieval phrase, because the STEP
            // format has no query field. The planner was told to put the *content* description
            // there ("海边玩的照片"), not a filter name.
            if (tool == "asset_replace" && effect.isNotEmpty()) args.put("query", effect)

            if (tool == "template_collage" || tool == "smart_collage") {
                if (!args.has("layout")) args.put("layout", "vertical")
                if (!args.has("canvas")) args.put("canvas", "1080x1440")
            }
            MobileToolCall(tool, args, parts.getOrNull(5).orEmpty())
        }.toList()
        // Collage composes the whole cast in one operation, yet the STEP format invites one line
        // per asset ordinal -- the brain happily emitted five identical collage steps for five
        // assets, which would run five full exports. Collapse consecutive whole-cast duplicates.
        val wholeCast = setOf("template_collage", "smart_collage")
        val deduped = calls.fold(mutableListOf<MobileToolCall>()) { acc, call ->
            if (call.tool in wholeCast && acc.lastOrNull()?.tool == call.tool) acc
            else acc.apply { add(call) }
        }
        if (deduped.isEmpty()) return null
        return MobilePlan(query, "qnn_genie", assets.map { it.assetId }, deduped)
    }

    private fun runText(prompt: String, maxTokens: Int): String? {
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
            if (!process.waitFor(30, TimeUnit.SECONDS) || !outputFile.isFile) {
                process.destroyForcibly()
                null
            } else outputFile.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        } finally {
            work.deleteRecursively()
        }
    }

    private fun intentPrompt(query: String, selectedAssetIds: List<String>): String = """
        <|im_start|>system
        你是 Live Photo Agent 的意图规划器。严格输出 JSON，不要 Markdown。
        先规划工具链，不要直接挑图片，不要凭空生成 asset_id。
        如果用户要求按内容找素材，必须先调用 search_assets，arguments 包含 query 和 k；
        随后需要拼接时调用 smart_collage。用户没有缺失关键信息时不要 CLARIFY。
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

    private fun field(text: String, name: String): String = Regex("(?im)^\\s*$name\\s*=\\s*(.*)$")
        .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    private fun prompt(query: String, assets: List<PreparedAsset>, selectedAssetIds: List<String>): String {
        val selected = selectedAssetIds.toSet()
        val catalog = MobileToolRegistry.promptCatalog(selected.size)
        // Marking the tapped assets is what lets the planner scope an edit to them. Selection is
        // UI state it cannot otherwise observe, and without the marker it plans against the whole
        // cast -- which is how a single-asset request used to silently edit everything.
        val assetLines = assets.joinToString("\n") { asset ->
            val tag = if (asset.assetId in selected) "[已选中] " else ""
            "$tag${asset.promptDescription()}"
        }
        val scopeRule = if (selected.isNotEmpty()) {
            "用户已选中 ${selected.size} 个素材（下方标有[已选中]）。指令默认只作用于这些素材，素材序号只能填[已选中]的序号。"
        } else {
            "用户未选中素材，指令作用于当前全部素材。"
        }
        return """
             <|im_start|>system
            你是手机端 Live Photo Agent，只能选择注册表中的工具。不要输出 Markdown。
            输出 ACTION=PLAN 或 ACTION=CLARIFY；PLAN 时每个步骤一行：
            STEP=工具ID|effect|start_ms|end_ms|素材序号|用户原话证据
            只规划本轮用户原话中明确要求的动作。历史上下文仅用于理解指代（这张/它/中间那个），
            不要重复执行历史轮次已完成的动作。
            $scopeRule
            asset_replace 的 effect 字段填写要检索的素材内容描述（从本轮原话中提炼，如"海边玩的照片"），
            不要填滤镜或色调名称。
            工具注册表：
            $catalog
             <|im_end|>
             <|im_start|>user
            当前素材：
            $assetLines
            最近对话上下文：${sessionContext()}
            用户原话：$query
            /no_think<|im_end|>
             <|im_start|>assistant
        """.trimIndent()
    }

    private fun sessionContext(): String = sessionId?.let {
        LibraryDatabase(context).plannerContext(it)
    }.orEmpty().ifBlank { "无" }
}
