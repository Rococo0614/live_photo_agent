package com.vivo.lpa

/** Single source of truth for tools the Android executor can really run. */
object MobileToolRegistry {
    private val tools = linkedMapOf(
        "asset_summarize" to "读取已缓存的 VLM 素材摘要",
        "search_assets" to "在 BGE 素材索引中按 query 检索 Top-K",
        "clip_trim" to "按毫秒裁剪视频",
        "template_collage" to "按布局模板拼接素材",
        "smart_collage" to "按语义选择拼接模板",
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
        "layout_swap" to "交换拼贴中两个素材的位置",
        "asset_replace" to "按语义检索替换拼贴中的一个素材",
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

    fun supports(tool: String): Boolean = tools.containsKey(tool)

    /**
     * Tool list for the planner prompt.
     *
     * @param selectedCount how many assets the user has tapped right now. Selection is UI state,
     *   so the caller must supply it -- the planner cannot observe it. `layout_swap` needs exactly
     *   two taps to have two slots to exchange; `asset_replace` needs exactly one to know what to
     *   swap out.
     */
    fun promptCatalog(selectedCount: Int = 0): String = tools.entries
        .filter { (id, _) ->
            when (id) {
                "layout_swap" -> selectedCount == 2
                "asset_replace" -> selectedCount == 1
                else -> id !in selectionOnly
            }
        }
        .joinToString("\n") { (id, description) -> "$id: $description" }
}
