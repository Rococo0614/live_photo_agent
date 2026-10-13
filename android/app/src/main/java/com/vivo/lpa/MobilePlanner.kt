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
    private val appContext = context?.applicationContext
    private val activeSessionId = sessionId
    private val geniePlanner = context?.let { GenieTextPlanner(it.applicationContext, sessionId) }
    private val templates = context?.let { MobileTemplateCatalog(it.applicationContext) }

    fun plan(query: String, assets: List<PreparedAsset>, layoutContext: JSONObject? = null): MobilePlan {
        geniePlanner?.tryPlan(query, assets)?.let { generated ->
            val explicitAction = containsAny(
                query.lowercase(Locale.ROOT),
                "拼", "拼接", "模板", "模版", "版式", "template", "layout", "裁剪", "剪", "调色", "滤镜", "增强", "识别", "摘要", "倒放", "变速"
            )
            if (generated.toolCalls.isNotEmpty() || generated.clarification == null || !explicitAction) {
                return attachLayout(generated, layoutContext)
            }
        }
        restoreContinuation(query, assets, layoutContext)?.let { return it }
        val text = query.trim()
        val lower = text.lowercase(Locale.ROOT)
        val ids = assets.map { it.assetId }
        val calls = mutableListOf<MobileToolCall>()
        val hasLivePhoto = assets.any { it.metadata.optString("media_format") == "livephoto" }
        val hasVideo = assets.any { it.videoFile != null }

        if (containsAny(lower, "能做什么", "可以做什么", "能力", "帮助", "what can you do")) {
            return MobilePlan(text, "capability", ids, emptyList(), null)
        }

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
            val smart = changingTemplate || containsAny(lower, "智能", "自动", "推荐", "smart")
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
                tool = if (smart) "smart_collage" else "template_collage",
                reason = if (smart) "按素材前处理结果选择模板" else "按当前布局模板拼接",
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

        if (containsAny(lower, "调色", "滤镜", "鲜艳", "暖色", "冷色", "color", "tone", "filter")) {
            val filterTool = when {
                hasLivePhoto -> "live_photo_filter_effect"
                hasVideo -> "video_filter"
                else -> "color_tone"
            }
            calls += MobileToolCall(
                tool = filterTool,
                reason = "调用端侧 ImageProcessSDK 调整色彩与影调",
                arguments = JSONObject().put("asset_ids", JSONArray(ids))
                    .put("contrast", 0.12).put("saturation", 0.12)
                    .put("temperature", if (lower.contains("冷色")) -0.08 else 0.08)
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

        val supportedCalls = calls.filter { MobileToolRegistry.supports(it.tool) }
        if (supportedCalls.isEmpty()) {
            return MobilePlan(
                userGoal = text,
                intent = "clarify",
                selectedAssetIds = ids,
                toolCalls = emptyList(),
                clarification = "请说明要对素材进行裁剪、拼接、调色、内容识别或图片增强中的哪一种操作；主体分割当前未接入。"
            )
        }

        return MobilePlan(
            userGoal = text,
            intent = if (calls.any { it.tool.endsWith("collage") }) "collage" else "media_edit",
            selectedAssetIds = ids,
            toolCalls = supportedCalls
        )
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
