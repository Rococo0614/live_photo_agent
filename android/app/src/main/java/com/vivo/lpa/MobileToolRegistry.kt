package com.vivo.lpa

/** One availability decision, including why tools were hidden from the on-device model. */
data class ToolCatalogDecision(
    val available: List<MobileToolSpec>,
    val excluded: Map<String, String>
) {
    fun prompt(): String = available.joinToString("\n") { "${it.id}: ${it.description}" }
}

/** Single source of truth for tools the Android executor can really run. */
object MobileToolRegistry {
    const val MAX_VISIBLE_TOOLS = 10

    private val imageOnly = setOf(RuntimeMediaKind.IMAGE)
    private val videoCapable = setOf(
        RuntimeMediaKind.VIDEO, RuntimeMediaKind.LIVE_PHOTO, RuntimeMediaKind.MIXED
    )
    private val liveOnly = setOf(RuntimeMediaKind.LIVE_PHOTO)
    private val anyPreparedMedia = setOf(
        RuntimeMediaKind.IMAGE, RuntimeMediaKind.VIDEO,
        RuntimeMediaKind.LIVE_PHOTO, RuntimeMediaKind.MIXED
    )

    private val specs = listOf(
        MobileToolSpec(
            "asset_summarize", ToolFamily.ANALYZE, "读取已缓存的 VLM 素材摘要",
            acceptedMedia = anyPreparedMedia, minAssets = 1
        ),
        MobileToolSpec(
            "search_assets", ToolFamily.RETRIEVE, "在 BGE 素材索引中按 query 检索 Top-K",
            observationBarrier = true
        ),
        MobileToolSpec(
            "clip_trim", ToolFamily.TEMPORAL, "按毫秒裁剪视频",
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "template_collage", ToolFamily.COMPOSE,
            "通用 2-6 格模板拼贴；用户说三拼、四拼、拼图时优先使用",
            acceptedMedia = videoCapable, minAssets = 2, maxAssets = 6, minVideoTracks = 2
        ),
        MobileToolSpec(
            "video_mirror", ToolFamily.TEMPORAL, "左右翻转视频",
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "video_blur", ToolFamily.STYLE, "视频背景模糊",
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "video_timeline_effect", ToolFamily.TEMPORAL, "时间线缩放效果",
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "video_motion_effect", ToolFamily.STYLE, "MediaEffectSDK 动感光效",
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "video_transition", ToolFamily.COMPOSE, "两段视频转场",
            acceptedMedia = videoCapable, minAssets = 2, maxAssets = 2,
            minVideoTracks = 2, maxVideoTracks = 2
        ),
        MobileToolSpec(
            "video_fit_band", ToolFamily.COMPOSE, "适配画布并保留背景",
            acceptedMedia = videoCapable, minAssets = 1, maxAssets = 1,
            minVideoTracks = 1, maxVideoTracks = 1
        ),
        MobileToolSpec(
            "video_crop_band", ToolFamily.COMPOSE, "裁剪适配画布",
            acceptedMedia = videoCapable, minAssets = 1, maxAssets = 1,
            minVideoTracks = 1, maxVideoTracks = 1
        ),
        MobileToolSpec(
            "video_vertical_stack", ToolFamily.COMPOSE,
            "仅在用户明确要求三段视频上下竖排或竖向堆叠时使用",
            acceptedMedia = videoCapable, minAssets = 3, minVideoTracks = 3, maxVideoTracks = 3
        ),
        MobileToolSpec(
            "video_dynamic_sticker", ToolFamily.STYLE, "动态贴纸叠加",
            acceptedMedia = videoCapable, minAssets = 2, maxAssets = 2,
            minVideoTracks = 2, maxVideoTracks = 2
        ),
        MobileToolSpec(
            "video_border_watermark", ToolFamily.STYLE, "边框与 LIVE 水印",
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "video_filter", ToolFamily.STYLE, "视频色彩与影调滤镜",
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "video_speed", ToolFamily.TEMPORAL, "视频变速",
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "video_reverse", ToolFamily.TEMPORAL, "视频倒放",
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "brighten", ToolFamily.STYLE, "图片亮度调整",
            acceptedMedia = imageOnly, minAssets = 1, minImageTracks = 1
        ),
        MobileToolSpec(
            "sharpen", ToolFamily.STYLE, "图片锐化",
            acceptedMedia = imageOnly, minAssets = 1, minImageTracks = 1
        ),
        MobileToolSpec(
            "denoise", ToolFamily.STYLE, "图片降噪",
            acceptedMedia = imageOnly, minAssets = 1, minImageTracks = 1
        ),
        MobileToolSpec(
            "color_tone", ToolFamily.STYLE, "图片色彩影调",
            acceptedMedia = imageOnly, minAssets = 1, minImageTracks = 1
        ),
        MobileToolSpec(
            "portrait_beauty", ToolFamily.STYLE, "图片人像美颜",
            acceptedMedia = imageOnly, minAssets = 1, minImageTracks = 1
        ),
        MobileToolSpec(
            "live_photo_filter_effect", ToolFamily.STYLE, "Live Photo 滤镜",
            acceptedMedia = liveOnly, minAssets = 1, minLivePhotos = 1
        ),
        MobileToolSpec(
            "live_photo_motion_effect", ToolFamily.STYLE, "Live Photo 动感特效",
            acceptedMedia = liveOnly, minAssets = 1, minLivePhotos = 1
        ),
        MobileToolSpec(
            "set_display_frame", ToolFamily.TEMPORAL, "按视频帧更换 Live Photo 封面",
            acceptedMedia = liveOnly, minAssets = 1, minLivePhotos = 1
        ),
        MobileToolSpec(
            "live_xdr_process", ToolFamily.ANALYZE, "Live XDR 资源处理",
            acceptedMedia = liveOnly, minAssets = 1, minLivePhotos = 1
        ),
        MobileToolSpec(
            "export_mp4", ToolFamily.PUBLISH, "发布 MP4 结果",
            requiredArtifactKinds = setOf(AgentArtifactKind.VIDEO), loopVisible = false
        ),
        MobileToolSpec(
            "layout_swap", ToolFamily.LAYOUT,
            "交换拼贴中两个素材的位置（一次交换即可，禁止重复调用）",
            acceptedMedia = anyPreparedMedia, minAssets = 2, selectionArity = 2,
            requiresLayout = true, destructive = true, idempotent = false, singleton = true,
            observationBarrier = true
        ),
        MobileToolSpec(
            "asset_replace", ToolFamily.REPLACE,
            "把素材本身换成库中另一张照片（仅限更换画面内容；改颜色/滤镜/风格请用调色工具）",
            acceptedMedia = anyPreparedMedia, minAssets = 1, selectionArity = 1,
            destructive = true, idempotent = false, singleton = true, observationBarrier = true
        ),
        MobileToolSpec(
            "color_analysis", ToolFamily.ANALYZE, "提取素材主色并生成调色板图",
            acceptedMedia = anyPreparedMedia, minAssets = 1
        ),
        MobileToolSpec(
            "publish_result", ToolFamily.PUBLISH, "发布已验证的最终图片、视频或 Live Photo",
            requiredArtifactKinds = setOf(
                AgentArtifactKind.IMAGE, AgentArtifactKind.VIDEO,
                AgentArtifactKind.LIVE_PHOTO, AgentArtifactKind.PALETTE
            ),
            destructive = true, idempotent = false, singleton = true, loopOnly = true
        )
    ).associateByTo(linkedMapOf()) { it.id }

    fun supports(tool: String): Boolean = specs.containsKey(tool)

    fun spec(tool: String): MobileToolSpec? = specs[tool]

    fun allSpecs(): List<MobileToolSpec> = specs.values.toList()

    /**
     * Legacy prompt adapter. The production static path keeps its existing broad catalogue while
     * media/count checks now derive from the same ToolSpec used by the loop runtime.
     */
    fun promptCatalog(
        selectedCount: Int = 0,
        assets: List<PreparedAsset> = emptyList()
    ): String = specs.values
        .asSequence()
        .filterNot { it.loopOnly }
        .filter { spec ->
            val selectionAllowed = spec.selectionArity?.let { it == selectedCount }
                ?: (spec.id !in setOf("layout_swap", "asset_replace"))
            selectionAllowed && (assets.isEmpty() || compatible(spec.id, assets))
        }
        .joinToString("\n") { "${it.id}: ${it.description}" }

    /**
     * Computes the exact concrete catalogue shown to a loop-stage model. More than ten is an
     * architecture error: silently taking the first ten would make registry order decide intent.
     */
    fun loopCatalog(context: ToolRuntimeContext): ToolCatalogDecision {
        requireNotNull(context.family) { "loop catalogue requires a semantic family" }
        val available = mutableListOf<MobileToolSpec>()
        val excluded = linkedMapOf<String, String>()
        specs.values.filter { it.loopVisible }.forEach { spec ->
            val decision = spec.availability(context)
            if (decision.available) available += spec else excluded[spec.id] = decision.reason
        }
        check(available.size <= MAX_VISIBLE_TOOLS) {
            "${context.family} exposes ${available.size} tools; split the family instead of truncating"
        }
        return ToolCatalogDecision(available, excluded)
    }

    /** Media/count compatibility used by the legacy parser; stateful checks belong to loopCatalog. */
    fun compatible(tool: String, assets: List<PreparedAsset>): Boolean {
        if (assets.isEmpty()) return true
        val spec = specs[tool] ?: return false
        val context = ToolRuntimeContext.from(assets)
        if (context.assetCount !in spec.minAssets..spec.maxAssets) return false
        if (context.imageCount < spec.minImageTracks) return false
        if (context.videoCount !in spec.minVideoTracks..spec.maxVideoTracks) return false
        if (context.livePhotoCount < spec.minLivePhotos) return false
        return true
    }
}
