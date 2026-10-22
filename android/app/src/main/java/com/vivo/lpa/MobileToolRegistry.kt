package com.vivo.lpa

/** Single source of truth for tools the Android executor can really run. */
object MobileToolRegistry {
    private val tools = linkedMapOf(
        "asset_summarize" to "读取已缓存的 VLM 素材摘要",
        "search_assets" to "在 BGE 素材索引中按 query 检索 Top-K",
        "clip_trim" to "按毫秒裁剪视频",
        "template_collage" to "按布局模板拼接素材",
        "video_mirror" to "左右翻转视频",
        "video_blur" to "视频背景模糊",
        "video_timeline_effect" to "时间线缩放效果",
        "video_motion_effect" to "MediaEffectSDK 动感光效",
        "video_transition" to "两段视频转场",
        "video_fit_band" to "适配画布并保留背景",
        "video_crop_band" to "裁剪适配画布",
        "video_vertical_stack" to "三段视频竖向堆叠",
        "video_dynamic_sticker" to "动态贴纸叠加",
        "video_border_watermark" to "边框与 LIVE 水印",
        "video_filter" to "视频色彩与影调滤镜",
        "video_speed" to "视频变速",
        "video_reverse" to "视频倒放",
        "brighten" to "图片亮度调整",
        "sharpen" to "图片锐化",
        "denoise" to "图片降噪",
        "color_tone" to "图片色彩影调",
        "portrait_beauty" to "图片人像美颜",
        "live_photo_filter_effect" to "Live Photo 滤镜",
        "live_photo_motion_effect" to "Live Photo 动感特效",
        "set_display_frame" to "按视频帧更换 Live Photo 封面",
        "live_xdr_process" to "Live XDR 资源处理",
        "export_mp4" to "发布 MP4 结果",
        "layout_swap" to "交换拼贴中两个素材的位置（一次交换即可，禁止重复调用）",
        "asset_replace" to "把素材本身换成库中另一张照片（仅限更换画面内容；改颜色/滤镜/风格请用调色工具）",
        "color_analysis" to "提取素材主色并生成调色板图"
    )

    /**
     * Tools that only make sense when the user has tapped specific assets in the strip.
     *
     * They are hidden from the catalogue unless the exact selection they require is present:
     * advertising them unconditionally would invite the planner to emit them with no selection
     * behind it, and the executor would then fail on a missing target.
     */
    private val selectionOnly = setOf("layout_swap", "asset_replace")

    /** Tools that cannot run unless at least one prepared asset has a video track. */
    private val videoTools = setOf(
        "clip_trim", "video_mirror", "video_blur", "video_timeline_effect",
        "video_motion_effect", "video_transition", "video_fit_band", "video_crop_band",
        "video_vertical_stack", "video_dynamic_sticker", "video_border_watermark",
        "video_filter", "video_speed", "video_reverse"
    )

    private val imageTools = setOf(
        "brighten", "sharpen", "denoise", "color_tone", "portrait_beauty"
    )

    private val livePhotoTools = setOf(
        "live_photo_filter_effect", "live_photo_motion_effect", "set_display_frame",
        "live_xdr_process"
    )

    fun supports(tool: String): Boolean = tools.containsKey(tool)

    /**
     * Tool list for the planner prompt.
     *
     * @param selectedCount how many assets the user has tapped right now. Selection is UI state,
     *   so the caller must supply it -- the planner cannot observe it. `layout_swap` needs exactly
     *   two taps to have two slots to exchange; `asset_replace` needs exactly one to know what to
     *   swap out.
     */
    fun promptCatalog(
        selectedCount: Int = 0,
        assets: List<PreparedAsset> = emptyList()
    ): String = tools.entries
        .filter { (id, _) ->
            val selectionAllowed = when (id) {
                "layout_swap" -> selectedCount == 2
                "asset_replace" -> selectedCount == 1
                else -> id !in selectionOnly
            }
            selectionAllowed && (assets.isEmpty() || compatible(id, assets))
        }
        .joinToString("\n") { (id, description) -> "$id: $description" }

    /**
     * Whether [tool] can consume this exact scoped set of prepared assets.
     *
     * This is capability filtering, not intent routing: the model still decides what operation the
     * user asked for, but it must never be offered a video-only function for three JPEGs. The same
     * predicate is checked again after parsing because a model can emit a hidden tool anyway.
     */
    fun compatible(tool: String, assets: List<PreparedAsset>): Boolean {
        if (assets.isEmpty()) return true // pre-retrieval intent prompt has no concrete cast yet
        val videoCount = assets.count { it.videoFile != null }
        val imageCount = assets.count { it.imageFile != null }
        val livePhotoCount = assets.count {
            it.metadata.optString("media_format") == "livephoto" &&
                it.videoFile != null && it.imageFile != null
        }
        return when {
            tool == "video_vertical_stack" -> videoCount >= 3
            tool == "video_transition" || tool == "video_dynamic_sticker" -> videoCount >= 2
            tool in videoTools -> videoCount >= 1
            tool in imageTools -> imageCount >= 1
            tool in livePhotoTools -> livePhotoCount >= 1
            else -> true
        }
    }
}
