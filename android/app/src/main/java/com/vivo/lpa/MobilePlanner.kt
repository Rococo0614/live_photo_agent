package com.vivo.lpa

import android.content.Context
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small on-device planner using the same tool vocabulary as the Python agent.
 *
 * The target project's QNN text planner can replace this class once its model
 * pack is available. Keeping a deterministic fallback makes the APK useful for
 * SDK validation and prevents an unavailable model from producing fake edits.
 */
class MobilePlanner(context: Context? = null, sessionId: String? = null) {
    companion object {
        /** Spoken answer for "what can you do"; kept here so UI and runner share one wording. */
        const val CAPABILITY_TEXT =
            "我可以：按内容语义检索素材；2-6 格拼贴与换模板；裁剪、变速、倒放、镜像、模糊、动感光效；" +
                "滤镜调色（暖色、冷色、黑白、鲜艳、复古）；图片增强（提亮、锐化、降噪、美颜）；" +
                "Live Photo 换封面；选中素材后语义替换、交换位置；提取主色生成调色板。直接告诉我想怎么处理即可。"

        fun isCapabilityQuery(query: String): Boolean {
            val lower = query.lowercase(Locale.ROOT)
            return listOf("能做什么", "可以做什么", "能力", "帮助", "what can you do", "介绍自己", "你是谁")
                .any(lower::contains)
        }

        /**
         * Whether the literal request clearly names a media-edit action.
         *
         * This is a guard against a false CHAT fast-path, not a tool router: a guarded request still
         * goes through BGE retrieval and the post-retrieval brain. The exact same predicate already
         * protected final-plan CLARIFY responses; exposing it here keeps both brain stages aligned.
         */
        fun isExplicitEditQuery(query: String): Boolean {
            val lower = query.lowercase(Locale.ROOT)
            return listOf(
                "拼", "拼接", "模板", "模版", "版式", "template", "layout", "裁剪", "剪",
                "调色", "滤镜", "增强", "识别", "摘要", "倒放", "变速", "加速", "慢放",
                "反转", "翻转", "镜像", "模糊", "动感", "换", "替换", "主色", "配色",
                "色板", "取色", "美颜", "锐化", "降噪", "提亮", "palette", "mirror",
                "flip", "reverse", "replace", "collage", "trim", "filter"
            ).any(lower::contains)
        }
    }

    private val appContext = context?.applicationContext
    private val activeSessionId = sessionId
    private val geniePlanner = context?.let { GenieTextPlanner(it.applicationContext, sessionId) }
    private val templates = context?.let { MobileTemplateCatalog(it.applicationContext) }

    fun plan(
        query: String,
        assets: List<PreparedAsset>,
        layoutContext: JSONObject? = null,
        selectedAssetIds: List<String> = emptyList()
    ): MobilePlan {
        val text = query.trim()
        val lower = text.lowercase(Locale.ROOT)

        // Dominant-colour analysis is a tool the on-device text planner has never seen, so it
        // must be claimed before genie gets a chance to misread it as a collage request.
        if (containsAny(lower, "主色", "主色调", "配色", "色板", "取色", "dominant color", "palette")) {
            val colorIds = selectedAssetIds.ifEmpty { assets.map { it.assetId } }
            return MobilePlan(text, "color_analysis", colorIds, listOf(MobileToolCall(
                tool = "color_analysis",
                reason = "提取素材主色并生成调色板",
                arguments = JSONObject().put("asset_ids", JSONArray(colorIds)).put("color_count", 5)
            )))
        }

        // Brain first. The keyword tables below are a fallback, not the primary planner: a table
        // can only ever miss silently (an unlisted synonym such as 反转 drops the step and returns
        // a plan that looks complete), whereas the brain fails loudly -- null, unparseable, or
        // CLARIFY -- and the tables then take over. Only the brain can recognise a verb nobody
        // thought to enumerate.
        val selected = selectedAssetIds.filter { id -> assets.any { it.assetId == id } }
        val brainBefore = geniePlanner?.lastTrace
        geniePlanner?.tryPlan(text, assets, selected)?.let { generated ->
            // If the brain asks a clarifying question about a sentence that plainly names an
            // action, do not bother the user -- fall through to the deterministic tables instead.
            val explicitAction = isExplicitEditQuery(lower)
            if (generated.toolCalls.isNotEmpty() || generated.clarification == null || !explicitAction) {
                val scoped = applySelectionContract(generated, assets, layoutContext, selected)
                return attachLayout(scoped, layoutContext)
            }
        }
        // The brain ran but produced no usable plan. Its raw words still explain *why* the tables
        // had to take over, so they travel with the fallback instead of vanishing.
        val brainFailure = geniePlanner?.lastTrace?.takeIf { it !== brainBefore && !it.parsed }

        planSelection(query, assets, layoutContext, selectedAssetIds)
            ?.let { return it.withFailedBrain(brainFailure) }
        restoreContinuation(query, assets, layoutContext)
            ?.let { return it.withFailedBrain(brainFailure) }
        val ids = assets.map { it.assetId }
        val calls = mutableListOf<MobileToolCall>()
        val hasLivePhoto = assets.any { it.metadata.optString("media_format") == "livephoto" }
        val hasVideo = assets.any { it.videoFile != null }

        if (containsAny(lower, "识别", "查看信息", "素材信息", "内容", "summarize", "summary")) {
            calls += MobileToolCall(
                tool = "asset_summarize",
                reason = "读取前处理后的素材语义摘要",
                arguments = JSONObject().put("asset_ids", JSONArray(ids))
            )
        }

        if (hasLivePhoto && containsAny(
                lower,
                "换封面", "更换封面", "设置封面", "封面", "首帧", "开头画面", "中间画面",
                "cover", "display frame"
            )) {
            val coverTime = when {
                containsAny(lower, "首帧", "开头画面") -> 0L
                containsAny(lower, "结尾", "最后画面", "尾帧") -> -2L
                else -> -1L
            }
            calls += MobileToolCall(
                tool = "set_display_frame",
                reason = "从 Live Photo 运动轨道选取新封面并重新封装",
                arguments = JSONObject()
                    .put("asset_ids", JSONArray(ids))
                    .put("cover_time_ms", coverTime)
            )
        }

        if (containsAny(lower, "裁剪", "截取", "trim", "剪成", "剪到")) {
            val duration = durationMs(text)
            calls += MobileToolCall(
                tool = "clip_trim",
                reason = "按用户指定的时长裁剪",
                arguments = JSONObject()
                    .put("asset_ids", JSONArray(ids))
                    .put("start_ms", 0)
                    .put("end_ms", duration)
            )
            calls += MobileToolCall(
                tool = "export_mp4",
                reason = "导出手机端 SDK 生成的结果",
                arguments = JSONObject().put("output_name", "live-agent-trim.mp4")
            )
        }

        val changingTemplate = isTemplateChange(lower)
        if (containsAny(lower, "拼", "拼接", "拼贴", "拼图", "模板", "模版", "版式", "template", "layout", "collage", "stack", "当前布局", "画布")) {
            val layout = if (containsAny(lower, "横", "horizontal", "溶解")) "horizontal" else "vertical"
            val previousTemplateId = activeSessionId?.let { session ->
                appContext?.let { context ->
                    val state = LibraryDatabase(context).sessionState(session)
                    state.optJSONObject("layout_context")?.optString("template_id")
                        ?.takeIf { it.isNotBlank() }
                        ?: state.optJSONObject("selected_template")?.optString("id")
                }
            }
            val template = templates?.chooseForCount(
                text,
                ids.size,
                previousTemplateId?.takeIf { changingTemplate }
            )
            val arguments = JSONObject()
                .put("asset_ids", JSONArray(ids))
                .put("layout", layout)
                .put("template_id", template?.optString("id", "T02") ?: "T02")
                .put("canvas", "${template?.optInt("canvas_width", 1080) ?: 1080}x${template?.optInt("canvas_height", 1440) ?: 1440}")
            if (layoutContext != null && !changingTemplate) arguments.put("layout_context", layoutContext)
            calls += MobileToolCall(
                tool = "template_collage",
                reason = "按当前布局模板拼接",
                arguments = arguments
            )
        }

        if (containsAny(lower, "降噪", "去噪", "denoise")) {
            calls += MobileToolCall(
                tool = "denoise",
                reason = "调用端侧 ImageProcessSDK 降噪",
                arguments = JSONObject().put("asset_ids", JSONArray(ids)).put("denoise", 0.6)
            )
        }

        if (containsAny(lower, "锐化", "变清晰", "更清晰", "sharpen")) {
            calls += MobileToolCall(
                tool = "sharpen",
                reason = "调用端侧 ImageProcessSDK 锐化",
                arguments = JSONObject().put("asset_ids", JSONArray(ids)).put("sharpen", 0.25)
            )
        }

        if (containsAny(lower, "美颜", "磨皮", "美白", "瘦脸", "大眼", "beauty")) {
            calls += MobileToolCall(
                tool = "portrait_beauty",
                reason = "调用端侧 ImageProcessSDK 人像美颜",
                arguments = JSONObject().put("asset_ids", JSONArray(ids))
                    .put("smooth_skin", 30).put("whiten", 20).put("face_brighten", 10)
            )
        }

        if (containsAny(lower, "变亮", "更亮", "提亮", "brighten")) {
            calls += MobileToolCall(
                tool = "brighten",
                reason = "调用端侧 ImageProcessSDK 提亮",
                arguments = JSONObject().put("asset_ids", JSONArray(ids)).put("brightness", 0.2)
            )
        }

        if (containsAny(lower, "调色", "滤镜", "鲜艳", "暖色", "冷色", "黑白", "灰度", "复古", "胶片", "color", "tone", "filter", "grayscale", "vintage")) {
            val filterTool = when {
                hasLivePhoto -> "live_photo_filter_effect"
                hasVideo -> "video_filter"
                else -> "color_tone"
            }
            // Style names map to the numeric parameters the SDKs consume; the runner applies the
            // same presets to brain plans, so both paths speak one style vocabulary.
            val style = JSONObject()
            when {
                containsAny(lower, "黑白", "灰度", "grayscale") -> {
                    style.put("saturation", -1.0).put("contrast", 0.1).put("temperature", 0.0)
                }
                containsAny(lower, "冷色", "冷调", "cool") -> style.put("temperature", -0.18)
                containsAny(lower, "暖色", "暖调", "warm") -> style.put("temperature", 0.18)
                containsAny(lower, "鲜艳", "vivid") -> style.put("saturation", 0.3).put("contrast", 0.08)
                containsAny(lower, "复古", "胶片", "vintage") ->
                    style.put("saturation", -0.25).put("temperature", 0.1).put("contrast", -0.06)
                else -> style.put("contrast", 0.12).put("saturation", 0.12).put("temperature", 0.08)
            }
            val filterArguments = JSONObject().put("asset_ids", JSONArray(ids))
            style.keys().forEach { key -> filterArguments.put(key, style.get(key)) }
            calls += MobileToolCall(
                tool = filterTool,
                reason = "调用端侧 ImageProcessSDK 调整色彩与影调",
                arguments = filterArguments
            )
        }

        if (containsAny(lower, "镜像", "左右翻转", "mirror")) {
            calls += MobileToolCall("video_mirror", arguments = JSONObject().put("asset_ids", JSONArray(ids)),
                reason = "调用端侧 VideoEditorSDK 镜像效果")
        } else if (containsAny(lower, "模糊", "blur")) {
            calls += MobileToolCall("video_blur", arguments = JSONObject().put("asset_ids", JSONArray(ids)),
                reason = "调用端侧 VideoEditorSDK 背景模糊效果")
        } else if (containsAny(lower, "缩放", "推拉", "时间线", "zoom")) {
            calls += MobileToolCall("video_timeline_effect", arguments = JSONObject().put("asset_ids", JSONArray(ids)),
                reason = "调用端侧 VideoEditorSDK 时间线缩放效果")
        } else if (containsAny(lower, "动感", "光效", "motion light")) {
            calls += MobileToolCall(
                if (hasLivePhoto) "live_photo_motion_effect" else "video_motion_effect",
                arguments = JSONObject().put("asset_ids", JSONArray(ids)),
                reason = "调用端侧 MediaEffectSDK 动感光效"
            )
        } else if (containsAny(lower, "加速", "变快", "慢放", "变速", "speed")) {
            val speed = if (containsAny(lower, "慢", "slow")) 0.5 else 2.0
            calls += MobileToolCall("video_speed", arguments = JSONObject()
                .put("asset_ids", JSONArray(ids)).put("speed", speed),
                reason = "调用端侧 VideoEditorSDK 变速")
        } else if (containsAny(lower, "倒放", "反向播放", "reverse")) {
            calls += MobileToolCall("video_reverse", arguments = JSONObject().put("asset_ids", JSONArray(ids)),
                reason = "调用端侧 VideoReverser 倒放")
        }

        if (containsAny(lower, "live xdr", "live_xdr", "xdr 资源", "xdr lut")) {
            calls += MobileToolCall(
                tool = "live_xdr_process",
                reason = "调用端侧 MediaEffectSDK 获取 Live XDR 资源",
                arguments = JSONObject()
            )
        }

        // Capability questions reach the keyword table only when the brain is down or produced
        // nothing usable; with the brain up, its chat channel answers them itself.
        if (isCapabilityQuery(text)) {
            return MobilePlan(text, "capability", ids, emptyList(), CAPABILITY_TEXT)
        }

        val supportedCalls = calls.filter { MobileToolRegistry.supports(it.tool) }
        if (supportedCalls.isEmpty()) {
            return MobilePlan(
                userGoal = text,
                intent = "clarify",
                selectedAssetIds = ids,
                toolCalls = emptyList(),
                clarification = "请说明要对素材进行裁剪、拼接、调色、内容识别或图片增强中的哪一种操作；主体分割当前未接入。"
            ).withFailedBrain(brainFailure)
        }

        return MobilePlan(
            userGoal = text,
            intent = if (calls.any { it.tool.endsWith("collage") }) "collage" else "media_edit",
            selectedAssetIds = ids,
            toolCalls = supportedCalls
        ).withFailedBrain(brainFailure)
    }

    /**
     * Attaches a failed brain attempt to a fallback plan without changing its attribution.
     *
     * The plan itself still comes from the deterministic tables, so intent keeps saying so; but the
     * brain's raw output is preserved so the audit can show what the model said before the tables
     * had to step in.
     */
    private fun MobilePlan.withFailedBrain(trace: BrainTrace?): MobilePlan =
        if (trace == null || brainTrace != null) this else copy(brainTrace = trace)

    /**
     * Deterministic planning for operations on assets the user tapped in the strip.
     *
     * Selection is UI state, so the text planner is bypassed entirely: with a dozen-slot
     * templates and free-form wording, letting an LLM guess which asset "这张" refers to is
     * both slower and less reliable than reading the tap. No selection means no override,
     * which keeps every existing whole-batch path untouched.
     *
     * Edits are flagged `per_asset` + `writeback` so the runner loops one asset at a time and
     * stores each result back under the same asset_id. That is what lets the trailing collage
     * reuse the stored layout verbatim: slot geometry is keyed by asset_id, so preserving the
     * id is what keeps an edited asset in its original slot.
     */
    private fun planSelection(
        query: String,
        assets: List<PreparedAsset>,
        layoutContext: JSONObject?,
        selectedAssetIds: List<String>
    ): MobilePlan? {
        if (selectedAssetIds.isEmpty()) return null
        val selected = selectedAssetIds.mapNotNull { id -> assets.firstOrNull { it.assetId == id } }
        if (selected.isEmpty()) return null

        val text = query.trim()
        val lower = text.lowercase(Locale.ROOT)
        // Template wording wins over selection: "换个模板" is about the whole collage even if
        // one asset happens to be tapped, and must not be read as "replace this asset".
        if (isTemplateChange(lower) || containsAny(lower, "模板", "模版", "版式", "宫格")) return null

        val selectedIds = selected.map { it.assetId }
        val hasLivePhoto = selected.any { it.metadata.optString("media_format") == "livephoto" }
        val hasVideo = selected.any { it.videoFile != null }
        val calls = mutableListOf<MobileToolCall>()
        val intent: String

        val swapping = selected.size == 2 &&
            containsAny(lower, "换位置", "换个位置", "换位", "交换", "调换", "对调", "互换", "swap")
        val replacementQuery = if (selected.size == 1) replacementQuery(text) else null

        when {
            swapping -> {
                intent = "selection_swap"
                calls += MobileToolCall(
                    tool = "layout_swap",
                    reason = "交换两个选中素材在拼贴中的位置",
                    arguments = JSONObject().put("asset_ids", JSONArray(selectedIds))
                )
            }
            replacementQuery != null -> {
                intent = "selection_replace"
                calls += MobileToolCall(
                    tool = "asset_replace",
                    reason = "按语义检索素材库，替换选中的素材",
                    arguments = JSONObject()
                        .put("asset_ids", JSONArray(selectedIds))
                        .put("query", replacementQuery)
                )
            }
            else -> intent = "selection_edit"
        }

        // A replace can carry a follow-up edit in the same sentence ("换成狗的素材并调滤镜变暖"),
        // so edit verbs are detected for every selection intent, not just the plain-edit one.
        calls += selectionEditCalls(lower, selectedIds, hasLivePhoto, hasVideo)

        if (calls.isEmpty()) return null

        // Re-collage only when there is a layout to reuse; otherwise the edit stands alone and
        // publishes normally.
        val recombine = layoutContext != null &&
            (swapping || replacementQuery != null || calls.any { it.tool != "asset_replace" })
        if (recombine) {
            calls += MobileToolCall(
                tool = "template_collage",
                reason = "沿用原模板与槽位重新拼接",
                arguments = JSONObject()
                    .put("asset_ids", JSONArray(assets.map { it.assetId }))
                    .put("reuse_layout", true)
                    .put("layout_context", layoutContext)
            )
        } else {
            // Standalone edit: publish it instead of writing it back into a slot.
            calls.replaceAll { call ->
                if (call.arguments.optBoolean("writeback")) {
                    call.copy(arguments = JSONObject(call.arguments.toString()).apply {
                        remove("writeback"); remove("per_asset")
                    })
                } else call
            }
        }

        return MobilePlan(
            userGoal = text,
            intent = intent,
            selectedAssetIds = selectedIds,
            toolCalls = calls
        )
    }

    /**
     * Edit verbs recognized on a tapped asset.
     *
     * Deliberately narrower than [plan]: selection covers the operations a user points at a
     * single thumbnail for (colour, orientation, playback), while batch-only verbs such as
     * 裁剪 or 识别 keep their existing whole-set semantics.
     */
    private fun selectionEditCalls(
        lower: String,
        ids: List<String>,
        hasLivePhoto: Boolean,
        hasVideo: Boolean
    ): List<MobileToolCall> {
        val calls = mutableListOf<MobileToolCall>()
        val idArray = { JSONArray(ids) }
        val scoped = { extra: JSONObject.() -> Unit ->
            JSONObject().put("asset_ids", idArray()).put("per_asset", true).put("writeback", true).apply(extra)
        }

        if (containsAny(lower, "滤镜", "调色", "鲜艳", "暖", "冷", "黑白", "灰度", "复古", "胶片",
                "filter", "color", "tone", "grayscale", "vintage")) {
            // Same style vocabulary as the whole-batch filter block in plan(): a selection edit
            // saying 黑白 must desaturate, not fall through to the generic warm preset.
            val (saturation, contrast, temperature) = when {
                containsAny(lower, "黑白", "灰度", "grayscale") -> Triple(-1.0, 0.1, 0.0)
                containsAny(lower, "冷") -> Triple(0.0, 0.0, -0.18)
                containsAny(lower, "暖") -> Triple(0.0, 0.0, 0.18)
                containsAny(lower, "鲜艳") -> Triple(0.3, 0.08, 0.0)
                containsAny(lower, "复古", "胶片", "vintage") -> Triple(-0.25, -0.06, 0.1)
                else -> Triple(0.12, 0.12, 0.08)
            }
            calls += MobileToolCall(
                tool = if (hasLivePhoto) "live_photo_filter_effect" else if (hasVideo) "video_filter" else "color_tone",
                reason = "对选中素材调整色彩与影调",
                arguments = scoped {
                    put("contrast", contrast); put("saturation", saturation); put("temperature", temperature)
                }
            )
        }
        if (containsAny(lower, "翻转", "镜像", "左右翻", "mirror", "flip")) {
            calls += MobileToolCall("video_mirror", scoped {}, "对选中素材左右翻转")
        }
        if (containsAny(lower, "倒放", "反向播放", "reverse")) {
            calls += MobileToolCall("video_reverse", scoped {}, "对选中素材倒放")
        }
        if (containsAny(lower, "加速", "变快", "慢放", "减速", "变速", "speed")) {
            val speed = if (containsAny(lower, "慢", "减速", "slow")) 0.5 else 2.0
            calls += MobileToolCall("video_speed", scoped { put("speed", speed) }, "对选中素材变速")
        }
        if (containsAny(lower, "模糊", "blur")) {
            calls += MobileToolCall("video_blur", scoped {}, "对选中素材做背景模糊")
        }
        if (containsAny(lower, "动感", "光效", "motion light")) {
            calls += MobileToolCall(
                if (hasLivePhoto) "live_photo_motion_effect" else "video_motion_effect",
                scoped {}, "对选中素材叠加动感光效"
            )
        }
        return calls.filter { MobileToolRegistry.supports(it.tool) }
    }

    /**
     * Pulls the semantic-search phrase out of a replacement request.
     *
     * "这张猫换成狗" -> "狗"; "更换成狗的素材并调滤镜变得更温暖一点" -> "狗". The clause
     * boundary matters: everything after 并/然后/再 describes a *follow-up edit* on the new
     * asset, not the search target, so it is cut before the trailing 的素材 noise is stripped.
     */
    private fun replacementQuery(text: String): String? {
        val triggers = listOf(
            "替换成", "更换成", "替换为", "更换为", "换成", "换为", "改成", "改为", "替换", "更换",
            // "swap with X" only: a bare "swap" belongs to layout_swap and must not be read as a
            // replacement request.
            "replace with", "replace by", "swap with", "replace"
        )
        // Earliest trigger wins, but among triggers starting at the same offset the *longest*
        // must be consumed. Otherwise "replace with dog" matches "replace" first and leaves
        // "with dog" as the search query.
        val start = triggers.mapNotNull { trigger ->
            text.indexOf(trigger).takeIf { it >= 0 }?.let { it to trigger.length }
        }.minWithOrNull(compareBy({ it.first }, { -it.second }))
            ?.let { (offset, length) -> offset + length }
            ?: return null

        val clause = text.substring(start)
            .split(Regex("[，,。；;！!？?]|并且|然后|接着|同时|另外|再|并"))
            .first()
            .trim()
            .removePrefix("一张").removePrefix("一个").removePrefix("个")
            .removeSuffix("的素材").removeSuffix("素材").removeSuffix("的图片")
            .removeSuffix("的照片").removeSuffix("的图").removeSuffix("的")
            .trim()
        return clause.takeIf { it.isNotEmpty() }
    }

    private fun restoreContinuation(
        query: String,
        assets: List<PreparedAsset>,
        layoutContext: JSONObject?
    ): MobilePlan? {
        val context = appContext ?: return null
        val session = activeSessionId ?: return null
        val lower = query.lowercase(Locale.ROOT)
        if (!containsAny(lower, "继续", "上次", "刚才", "之前", "再来", "按原来")) return null
        val stored = LibraryDatabase(context).sessionState(session).optJSONObject("last_plan") ?: return null
        val calls = mutableListOf<MobileToolCall>()
        val storedCalls = stored.optJSONArray("tool_calls") ?: return null
        for (index in 0 until storedCalls.length()) {
            val item = storedCalls.optJSONObject(index) ?: continue
            val tool = item.optString("tool")
            if (!MobileToolRegistry.supports(tool)) continue
            val arguments = JSONObject(item.optJSONObject("arguments")?.toString() ?: "{}")
            if (arguments.has("asset_ids")) {
                arguments.put("asset_ids", JSONArray(assets.map { it.assetId }))
            }
            calls += MobileToolCall(tool, arguments, item.optString("reason"))
        }
        if (calls.isEmpty()) return null
        return attachLayout(
            MobilePlan(
                userGoal = query,
                intent = "resume_${stored.optString("intent", "plan")}",
                selectedAssetIds = assets.map { it.assetId },
                toolCalls = calls
            ),
            layoutContext
        )
    }

    /**
     * Tools whose executor branch honours per_asset + writeback, i.e. it edits each asset in place
     * and stores the result back under the same asset_id so a trailing re-collage picks it up.
     *
     * The image branch (brighten/sharpen/denoise/color_tone/portrait_beauty) does NOT: it takes one
     * asset and publishes it. Marking those writeback would silently drop the edit, so they are
     * deliberately absent here and stay standalone.
     */
    private val writebackCapable = setOf(
        "video_mirror", "video_blur", "video_timeline_effect", "video_motion_effect",
        "video_transition", "video_fit_band", "video_crop_band", "video_vertical_stack",
        "video_dynamic_sticker", "video_border_watermark", "video_filter", "video_speed",
        "video_reverse", "live_photo_filter_effect", "live_photo_motion_effect"
    )

    /**
     * Imposes the selection execution contract on a brain plan.
     *
     * The brain decides *what* to do (which tools, which assets); this decides *how* it is wired
     * into the pipeline. The per_asset/writeback flags and the trailing re-collage are an executor
     * convention the model has never been shown, so leaving them to it would only widen the ways a
     * plan can be wrong. Applying them here keeps the brain's output honest to the selection while
     * the plumbing stays deterministic.
     */
    private fun applySelectionContract(
        plan: MobilePlan,
        assets: List<PreparedAsset>,
        layoutContext: JSONObject?,
        selected: List<String>
    ): MobilePlan {
        if (selected.isEmpty() || plan.clarification != null) return plan

        val calls = plan.toolCalls.toMutableList()
        val hasSlotEdit = calls.any { it.tool != "asset_replace" && it.tool in writebackCapable }
        val hasReplace = calls.any { it.tool == "asset_replace" }
        // layout_swap only repoints slots inside workingLayout; without a trailing re-collage the
        // plan produces no file at all and finishes with "已完成前处理". The deterministic path
        // appends the re-collage itself, so the brain path must as well.
        val hasSwap = calls.any { it.tool == "layout_swap" }
        val recombine = layoutContext != null && (hasSlotEdit || hasReplace || hasSwap)

        val rewritten = calls.map { call ->
            if (recombine && call.tool in writebackCapable) {
                val arguments = JSONObject(call.arguments.toString())
                if (!arguments.has("asset_ids") || arguments.optJSONArray("asset_ids")?.length() == 0) {
                    arguments.put("asset_ids", JSONArray(selected))
                }
                arguments.put("per_asset", true).put("writeback", true)
                call.copy(arguments = arguments)
            } else call
        }.toMutableList()

        if (recombine) {
            rewritten += MobileToolCall(
                tool = "template_collage",
                reason = "沿用原模板与槽位重新拼接",
                arguments = JSONObject()
                    .put("asset_ids", JSONArray(assets.map { it.assetId }))
                    .put("reuse_layout", true)
                    .put("layout_context", layoutContext)
            )
        }
        return plan.copy(selectedAssetIds = selected, toolCalls = rewritten)
    }

    private fun attachLayout(plan: MobilePlan, layoutContext: JSONObject?): MobilePlan {
        val changingTemplate = isTemplateChange(plan.userGoal.lowercase(Locale.ROOT))
        return plan.copy(toolCalls = plan.toolCalls.map { call ->
            if (!call.tool.endsWith("collage")) return@map call
            val arguments = call.arguments
            if (layoutContext != null && !changingTemplate) arguments.put("layout_context", layoutContext)
            call.copy(arguments = arguments)
        })
    }

    private fun durationMs(text: String): Long {
        Regex("(\\d+(?:\\.\\d+)?)\\s*(秒|s)", RegexOption.IGNORE_CASE)
            .find(text)?.let { return (it.groupValues[1].toDouble() * 1000.0).toLong().coerceAtLeast(1L) }
        Regex("(\\d+)\\s*毫秒").find(text)?.let { return it.groupValues[1].toLong().coerceAtLeast(1L) }
        return 1000L
    }

    private fun colorProfile(lower: String): String = when {
        lower.contains("冷色") || lower.contains("cool") -> "cool"
        lower.contains("暖色") || lower.contains("warm") -> "warm"
        else -> "vivid"
    }

    private fun containsAny(value: String, vararg needles: String): Boolean = needles.any { value.contains(it) }

    private fun isTemplateChange(value: String): Boolean = listOf(
        "换个模板", "换个模版", "换一种模板", "换一种模版", "改模板", "改模版", "换版式",
        "change template", "switch template", "change layout", "switch layout"
    ).any(value::contains)
}
