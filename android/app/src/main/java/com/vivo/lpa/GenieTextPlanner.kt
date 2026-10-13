package com.vivo.lpa

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Optional bridge to the target project's Qualcomm Genie text runner. */
class GenieTextPlanner(private val context: Context, private val sessionId: String? = null) {
    /** Web-agent style plan: search_assets/smart_collage is planned before media preparation. */
    fun tryIntent(query: String, selectedAssetIds: List<String> = emptyList()): MobilePlan? {
        if (!ModelPack.qwenReady(context)) return null
        val raw = runText(intentPrompt(query, selectedAssetIds), 256) ?: return null
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
                clarification = json.optString("clarification", "").takeIf { it.isNotBlank() }
            ).takeIf { it.toolCalls.isNotEmpty() || it.clarification != null }
        }.getOrNull()
    }

    fun tryPlan(query: String, assets: List<PreparedAsset>): MobilePlan? {
        if (!ModelPack.qwenReady(context)) return null
        val model = ModelPack.executionRoot(context, ModelPack.qwenRoot(context))
        val runner = ModelPack.genieRunner(context, model) ?: return null
        val work = File(context.cacheDir, "planner-${System.nanoTime()}").apply { mkdirs() }
        val promptFile = File(work, "prompt.txt")
        val outputFile = File(work, "output.txt")
        val scriptFile = File(work, "script.txt")
        promptFile.writeText(prompt(query, assets), Charsets.UTF_8)
        scriptFile.writeText(
            "dialog config create cfg ${File(model, "genie_config.json").absolutePath}\n" +
                "dialog create d cfg\n" +
                "dialog setMaxNumTokens d 384\n" +
                "dialog query d ${promptFile.absolutePath} ${outputFile.absolutePath}\n" +
                "dialog free d\ndialog config free cfg\nexit\n",
            Charsets.UTF_8
        )
        return try {
            val process = ProcessBuilder(runner.absolutePath, "-s", scriptFile.absolutePath)
                .directory(model)
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] =
                        context.applicationInfo.nativeLibraryDir + ":" +
                            ModelPack.runtimeLibRoot(context, model).absolutePath + ":/vendor/lib64:/vendor/lib64/hw"
                    environment()["ADSP_LIBRARY_PATH"] =
                        File(model, "runtime/dsp").absolutePath + ";/vendor/dsp/cdsp"
                }
                .start()
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0 || !outputFile.isFile) return null
            parse(outputFile.readText(Charsets.UTF_8), query, assets)
        } catch (_: Throwable) {
            null
        } finally {
            work.deleteRecursively()
        }
    }

    private fun parse(text: String, query: String, assets: List<PreparedAsset>): MobilePlan? {
        val action = field(text, "ACTION").uppercase()
        if (action == "CLARIFY") {
            return MobilePlan(query, "clarify", assets.map { it.assetId }, emptyList(), field(text, "QUESTION"))
        }
        if (action != "PLAN") return null
        val calls = Regex("(?im)^\\s*STEP\\s*=\\s*(.+)$").findAll(text).mapNotNull { match ->
            val parts = match.groupValues[1].split('|')
            val tool = parts.getOrNull(0)?.trim().orEmpty()
            if (tool.isBlank() || !MobileToolRegistry.supports(tool)) return@mapNotNull null
            val args = JSONObject()
            val effect = parts.getOrNull(1)?.trim().orEmpty()
            val start = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
            val end = parts.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L
            val input = parts.getOrNull(4)?.trim()?.toIntOrNull()
            if (effect.isNotEmpty()) args.put("effect", effect)
            if (start > 0) args.put("start_ms", start)
            if (end > 0) args.put("end_ms", end)
            if (tool == "template_collage" || tool == "smart_collage") {
                args.put("asset_ids", JSONArray(assets.map { it.assetId }))
                if (!args.has("layout")) args.put("layout", "vertical")
                if (!args.has("canvas")) args.put("canvas", "1080x1440")
            } else if (input != null && input > 0 && input <= assets.size) {
                args.put("asset_ids", JSONArray(listOf(assets[input - 1].assetId)))
            } else {
                args.put("asset_ids", JSONArray(assets.map { it.assetId }))
            }
            MobileToolCall(tool, args, parts.getOrNull(5).orEmpty())
        }.toList()
        if (calls.isEmpty()) return null
        return MobilePlan(query, "qnn_genie", assets.map { it.assetId }, calls)
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
            if (!process.waitFor(90, TimeUnit.SECONDS) || !outputFile.isFile) null
            else outputFile.readText(Charsets.UTF_8)
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

    private fun prompt(query: String, assets: List<PreparedAsset>): String {
        val catalog = MobileToolRegistry.promptCatalog()
        return """
            <|im_start|>system
            你是手机端 Live Photo Agent，只能选择注册表中的工具。不要输出 Markdown。
            输出 ACTION=PLAN 或 ACTION=CLARIFY；PLAN 时每个步骤一行：
            STEP=工具ID|effect|start_ms|end_ms|素材序号|用户原话证据
            工具注册表：
            $catalog
            <|im_end|>
            <|im_start|>user
            当前素材：${assets.joinToString("\n") { it.promptDescription() }}
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
