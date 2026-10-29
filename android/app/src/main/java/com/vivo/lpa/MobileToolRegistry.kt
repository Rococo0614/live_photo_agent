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
    const val SPEC_VERSION = 1

    private val mediaValues = setOf(
        AgentDataKind.ASSET_SET,
        AgentDataKind.IMAGE,
        AgentDataKind.VIDEO,
        AgentDataKind.LIVE_PHOTO,
        AgentDataKind.PALETTE
    )
    private val videoValues = setOf(
        AgentDataKind.ASSET_SET, AgentDataKind.VIDEO, AgentDataKind.LIVE_PHOTO
    )
    private val imageValues = setOf(
        AgentDataKind.ASSET_SET, AgentDataKind.IMAGE, AgentDataKind.PALETTE
    )
    private val publishValues = setOf(
        AgentDataKind.IMAGE,
        AgentDataKind.VIDEO,
        AgentDataKind.LIVE_PHOTO,
        AgentDataKind.PALETTE
    )

    private fun port(
        name: String,
        kinds: Set<AgentDataKind>,
        required: Boolean = true,
        variadic: Boolean = false
    ) = AgentPortSpec(name, kinds, required, variadic)

    private fun resources(vararg kinds: AgentResourceKind): Set<AgentResourceClaim> =
        kinds.map { kind ->
            AgentResourceClaim(
                kind,
                exclusive = kind in setOf(
                    AgentResourceKind.GPU,
                    AgentResourceKind.NPU,
                    AgentResourceKind.VIDEO_CODEC,
                    AgentResourceKind.VIDEO_EDITOR_SDK,
                    AgentResourceKind.IMAGE_SDK,
                    AgentResourceKind.LIVE_PHOTO_SDK
                )
            )
        }.toSet()

    private fun params(vararg specs: AgentParameterSpec): Map<String, AgentParameterSpec> =
        specs.associateBy { it.name }

    private fun contract(
        phase: AgentActionPhase,
        inputs: List<AgentPortSpec> = emptyList(),
        outputs: List<AgentPortSpec> = emptyList(),
        effects: Set<AgentStateEffect> = emptySet(),
        resources: Set<AgentResourceClaim> = emptySet(),
        parameters: Map<String, AgentParameterSpec> = emptyMap(),
        postconditions: Set<String> = emptySet()
    ) = AgentActionContract(
        phase = phase,
        inputPorts = inputs,
        outputPorts = outputs,
        effects = effects,
        resources = resources,
        parameters = parameters,
        postconditionIds = postconditions
    )

    private fun videoTransformContract() = contract(
        phase = AgentActionPhase.TRANSFORM,
        inputs = listOf(port("media", videoValues)),
        outputs = listOf(port("artifact", setOf(AgentDataKind.VIDEO, AgentDataKind.ASSET_SET))),
        effects = setOf(AgentStateEffect.ADD_ARTIFACT, AgentStateEffect.REPLACE_ASSET),
        resources = resources(
            AgentResourceKind.VIDEO_EDITOR_SDK,
            AgentResourceKind.VIDEO_CODEC,
            AgentResourceKind.STORAGE_IO
        ),
        parameters = params(
            AgentParameterSpec("effect", AgentParameterType.STRING),
            AgentParameterSpec("asset_ids", AgentParameterType.STRING_LIST),
            AgentParameterSpec("per_asset", AgentParameterType.BOOLEAN),
            AgentParameterSpec("writeback", AgentParameterType.BOOLEAN)
        ),
        postconditions = setOf("media_file_exists", "video_track_decodable")
    )

    private fun imageTransformContract() = contract(
        phase = AgentActionPhase.TRANSFORM,
        inputs = listOf(port("media", imageValues)),
        outputs = listOf(port("artifact", setOf(AgentDataKind.IMAGE))),
        effects = setOf(AgentStateEffect.ADD_ARTIFACT),
        resources = resources(AgentResourceKind.IMAGE_SDK, AgentResourceKind.STORAGE_IO),
        parameters = params(
            AgentParameterSpec("effect", AgentParameterType.STRING),
            AgentParameterSpec("asset_ids", AgentParameterType.STRING_LIST)
        ),
        postconditions = setOf("media_file_exists", "image_decodable")
    )

    private fun livePhotoTransformContract() = contract(
        phase = AgentActionPhase.TRANSFORM,
        inputs = listOf(port("media", setOf(AgentDataKind.ASSET_SET, AgentDataKind.LIVE_PHOTO))),
        outputs = listOf(port("artifact", setOf(AgentDataKind.LIVE_PHOTO, AgentDataKind.ASSET_SET))),
        effects = setOf(AgentStateEffect.ADD_ARTIFACT, AgentStateEffect.REPLACE_ASSET),
        resources = resources(
            AgentResourceKind.LIVE_PHOTO_SDK,
            AgentResourceKind.VIDEO_EDITOR_SDK,
            AgentResourceKind.VIDEO_CODEC,
            AgentResourceKind.STORAGE_IO
        ),
        parameters = params(
            AgentParameterSpec("effect", AgentParameterType.STRING),
            AgentParameterSpec("asset_ids", AgentParameterType.STRING_LIST),
            AgentParameterSpec("per_asset", AgentParameterType.BOOLEAN),
            AgentParameterSpec("writeback", AgentParameterType.BOOLEAN)
        ),
        postconditions = setOf("live_photo_tracks_decodable", "photo_timestamp_valid")
    )

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
            contract = contract(
                AgentActionPhase.OBSERVE,
                inputs = listOf(port("media", mediaValues)),
                outputs = listOf(port("analysis", setOf(AgentDataKind.JSON))),
                effects = setOf(AgentStateEffect.ADD_ARTIFACT),
                resources = resources(AgentResourceKind.CPU)
            ),
            acceptedMedia = anyPreparedMedia, minAssets = 1
        ),
        MobileToolSpec(
            "search_assets", ToolFamily.RETRIEVE, "在 BGE 素材索引中按 query 检索 Top-K",
            contract = contract(
                AgentActionPhase.OBSERVE,
                outputs = listOf(port("assets", setOf(AgentDataKind.ASSET_SET))),
                effects = setOf(AgentStateEffect.ADD_ASSETS),
                resources = resources(AgentResourceKind.BGE, AgentResourceKind.STORAGE_IO),
                parameters = params(
                    AgentParameterSpec("query", AgentParameterType.STRING, required = true),
                    AgentParameterSpec("k", AgentParameterType.INTEGER, minimum = 1.0, maximum = 64.0),
                    AgentParameterSpec("required_media", AgentParameterType.STRING)
                )
            ),
            observationBarrier = true
        ),
        MobileToolSpec(
            "clip_trim", ToolFamily.TEMPORAL, "按毫秒裁剪视频",
            contract = videoTransformContract().copy(parameters = params(
                AgentParameterSpec("start_ms", AgentParameterType.LONG, unit = "ms", minimum = 0.0),
                AgentParameterSpec("end_ms", AgentParameterType.LONG, unit = "ms", minimum = 0.0),
                AgentParameterSpec("asset_ids", AgentParameterType.STRING_LIST)
            )),
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "template_collage", ToolFamily.COMPOSE,
            "通用 2-6 格模板拼贴；用户说三拼、四拼、拼图时优先使用",
            contract = contract(
                AgentActionPhase.TRANSFORM,
                inputs = listOf(port("media", setOf(AgentDataKind.ASSET_SET))),
                outputs = listOf(
                    port("artifact", setOf(AgentDataKind.VIDEO)),
                    port("layout", setOf(AgentDataKind.LAYOUT))
                ),
                effects = setOf(AgentStateEffect.ADD_ARTIFACT, AgentStateEffect.UPDATE_LAYOUT),
                resources = resources(
                    AgentResourceKind.VIDEO_EDITOR_SDK,
                    AgentResourceKind.VIDEO_CODEC,
                    AgentResourceKind.STORAGE_IO
                ),
                parameters = params(
                    AgentParameterSpec("asset_ids", AgentParameterType.STRING_LIST),
                    AgentParameterSpec("template_id", AgentParameterType.STRING),
                    AgentParameterSpec("layout", AgentParameterType.STRING),
                    AgentParameterSpec("canvas", AgentParameterType.STRING)
                ),
                postconditions = setOf("media_file_exists", "video_track_decodable", "layout_slot_count_matches")
            ),
            acceptedMedia = videoCapable, minAssets = 2, maxAssets = 6, minVideoTracks = 2
        ),
        MobileToolSpec(
            "video_mirror", ToolFamily.TEMPORAL, "左右翻转视频",
            contract = videoTransformContract(),
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "video_blur", ToolFamily.STYLE, "视频背景模糊",
            contract = videoTransformContract(),
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "video_timeline_effect", ToolFamily.TEMPORAL, "时间线缩放效果",
            contract = videoTransformContract(),
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "video_motion_effect", ToolFamily.STYLE, "MediaEffectSDK 动感光效",
            contract = videoTransformContract(),
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "video_transition", ToolFamily.COMPOSE, "两段视频转场",
            contract = videoTransformContract(),
            acceptedMedia = videoCapable, minAssets = 2, maxAssets = 2,
            minVideoTracks = 2, maxVideoTracks = 2
        ),
        MobileToolSpec(
            "video_fit_band", ToolFamily.COMPOSE, "适配画布并保留背景",
            contract = videoTransformContract(),
            acceptedMedia = videoCapable, minAssets = 1, maxAssets = 1,
            minVideoTracks = 1, maxVideoTracks = 1
        ),
        MobileToolSpec(
            "video_crop_band", ToolFamily.COMPOSE, "裁剪适配画布",
            contract = videoTransformContract(),
            acceptedMedia = videoCapable, minAssets = 1, maxAssets = 1,
            minVideoTracks = 1, maxVideoTracks = 1
        ),
        MobileToolSpec(
            "video_vertical_stack", ToolFamily.COMPOSE,
            "仅在用户明确要求三段视频上下竖排或竖向堆叠时使用",
            contract = videoTransformContract(),
            acceptedMedia = videoCapable, minAssets = 3, minVideoTracks = 3, maxVideoTracks = 3
        ),
        MobileToolSpec(
            "video_dynamic_sticker", ToolFamily.STYLE, "动态贴纸叠加",
            contract = videoTransformContract(),
            acceptedMedia = videoCapable, minAssets = 2, maxAssets = 2,
            minVideoTracks = 2, maxVideoTracks = 2
        ),
        MobileToolSpec(
            "video_border_watermark", ToolFamily.STYLE, "边框与 LIVE 水印",
            contract = videoTransformContract(),
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "video_filter", ToolFamily.STYLE, "视频色彩与影调滤镜",
            contract = videoTransformContract(),
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "video_speed", ToolFamily.TEMPORAL, "视频变速",
            contract = videoTransformContract().copy(parameters = videoTransformContract().parameters +
                ("speed" to AgentParameterSpec("speed", AgentParameterType.DECIMAL, minimum = 0.1, maximum = 8.0))),
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "video_reverse", ToolFamily.TEMPORAL, "视频倒放",
            contract = videoTransformContract(),
            acceptedMedia = videoCapable, minAssets = 1, minVideoTracks = 1
        ),
        MobileToolSpec(
            "brighten", ToolFamily.STYLE, "图片亮度调整",
            contract = imageTransformContract(),
            acceptedMedia = imageOnly, minAssets = 1, minImageTracks = 1
        ),
        MobileToolSpec(
            "sharpen", ToolFamily.STYLE, "图片锐化",
            contract = imageTransformContract(),
            acceptedMedia = imageOnly, minAssets = 1, minImageTracks = 1
        ),
        MobileToolSpec(
            "denoise", ToolFamily.STYLE, "图片降噪",
            contract = imageTransformContract(),
            acceptedMedia = imageOnly, minAssets = 1, minImageTracks = 1
        ),
        MobileToolSpec(
            "color_tone", ToolFamily.STYLE, "图片色彩影调",
            contract = imageTransformContract(),
            acceptedMedia = imageOnly, minAssets = 1, minImageTracks = 1
        ),
        MobileToolSpec(
            "portrait_beauty", ToolFamily.STYLE, "图片人像美颜",
            contract = imageTransformContract(),
            acceptedMedia = imageOnly, minAssets = 1, minImageTracks = 1
        ),
        MobileToolSpec(
            "live_photo_filter_effect", ToolFamily.STYLE, "Live Photo 滤镜",
            contract = livePhotoTransformContract(),
            acceptedMedia = liveOnly, minAssets = 1, minLivePhotos = 1
        ),
        MobileToolSpec(
            "live_photo_motion_effect", ToolFamily.STYLE, "Live Photo 动感特效",
            contract = livePhotoTransformContract(),
            acceptedMedia = liveOnly, minAssets = 1, minLivePhotos = 1
        ),
        MobileToolSpec(
            "set_display_frame", ToolFamily.TEMPORAL, "按视频帧更换 Live Photo 封面",
            contract = livePhotoTransformContract().copy(parameters = params(
                AgentParameterSpec("cover_time_ms", AgentParameterType.LONG, unit = "ms", minimum = 0.0),
                AgentParameterSpec("asset_ids", AgentParameterType.STRING_LIST)
            )),
            acceptedMedia = liveOnly, minAssets = 1, minLivePhotos = 1
        ),
        MobileToolSpec(
            "live_xdr_process", ToolFamily.ANALYZE, "Live XDR 资源处理",
            contract = contract(
                AgentActionPhase.OBSERVE,
                inputs = listOf(port("media", setOf(AgentDataKind.ASSET_SET, AgentDataKind.LIVE_PHOTO))),
                outputs = listOf(port("analysis", setOf(AgentDataKind.JSON))),
                effects = setOf(AgentStateEffect.ADD_ARTIFACT),
                resources = resources(AgentResourceKind.LIVE_PHOTO_SDK, AgentResourceKind.STORAGE_IO)
            ),
            acceptedMedia = liveOnly, minAssets = 1, minLivePhotos = 1
        ),
        MobileToolSpec(
            "export_mp4", ToolFamily.PUBLISH, "发布 MP4 结果",
            contract = contract(
                AgentActionPhase.COMMIT,
                inputs = listOf(port("artifact", setOf(AgentDataKind.VIDEO))),
                outputs = listOf(port("uri", setOf(AgentDataKind.PUBLISHED_URI))),
                effects = setOf(AgentStateEffect.PUBLISH),
                resources = resources(AgentResourceKind.MEDIA_STORE, AgentResourceKind.STORAGE_IO)
            ),
            requiredArtifactKinds = setOf(AgentArtifactKind.VIDEO), loopVisible = false
        ),
        MobileToolSpec(
            "layout_swap", ToolFamily.LAYOUT,
            "交换拼贴中两个素材的位置（一次交换即可，禁止重复调用）",
            contract = contract(
                AgentActionPhase.STATE_MUTATION,
                inputs = listOf(
                    port("media", setOf(AgentDataKind.ASSET_SET)),
                    port("layout", setOf(AgentDataKind.LAYOUT))
                ),
                outputs = listOf(port("layout", setOf(AgentDataKind.LAYOUT))),
                effects = setOf(AgentStateEffect.UPDATE_LAYOUT),
                parameters = params(AgentParameterSpec("asset_ids", AgentParameterType.STRING_LIST))
            ),
            acceptedMedia = anyPreparedMedia, minAssets = 2, selectionArity = 2,
            requiresLayout = true, destructive = true, idempotent = false, singleton = true,
            observationBarrier = true
        ),
        MobileToolSpec(
            "asset_replace", ToolFamily.REPLACE,
            "把素材本身换成库中另一张照片（仅限更换画面内容；改颜色/滤镜/风格请用调色工具）",
            contract = contract(
                AgentActionPhase.STATE_MUTATION,
                inputs = listOf(port("media", setOf(AgentDataKind.ASSET_SET))),
                outputs = listOf(port("assets", setOf(AgentDataKind.ASSET_SET))),
                effects = setOf(AgentStateEffect.REPLACE_ASSET),
                resources = resources(AgentResourceKind.BGE, AgentResourceKind.STORAGE_IO),
                parameters = params(
                    AgentParameterSpec("asset_ids", AgentParameterType.STRING_LIST),
                    AgentParameterSpec("query", AgentParameterType.STRING, required = true)
                )
            ),
            acceptedMedia = anyPreparedMedia, minAssets = 1, selectionArity = 1,
            destructive = true, idempotent = false, singleton = true, observationBarrier = true
        ),
        MobileToolSpec(
            "color_analysis", ToolFamily.ANALYZE, "提取素材主色并生成调色板图",
            contract = contract(
                AgentActionPhase.OBSERVE,
                inputs = listOf(port("media", mediaValues)),
                outputs = listOf(port("artifact", setOf(AgentDataKind.PALETTE))),
                effects = setOf(AgentStateEffect.ADD_ARTIFACT),
                resources = resources(AgentResourceKind.CPU, AgentResourceKind.STORAGE_IO),
                parameters = params(
                    AgentParameterSpec("color_count", AgentParameterType.INTEGER, minimum = 1.0, maximum = 8.0),
                    AgentParameterSpec("asset_ids", AgentParameterType.STRING_LIST)
                )
            ),
            acceptedMedia = anyPreparedMedia, minAssets = 1
        ),
        MobileToolSpec(
            "publish_result", ToolFamily.PUBLISH, "发布已验证的最终图片、视频或 Live Photo",
            contract = contract(
                AgentActionPhase.COMMIT,
                inputs = listOf(port("artifact", publishValues)),
                outputs = listOf(port("uri", setOf(AgentDataKind.PUBLISHED_URI))),
                effects = setOf(AgentStateEffect.PUBLISH),
                resources = resources(
                    AgentResourceKind.MEDIA_STORE,
                    AgentResourceKind.LIVE_PHOTO_SDK,
                    AgentResourceKind.STORAGE_IO
                ),
                parameters = params(
                    AgentParameterSpec("output_kind", AgentParameterType.STRING),
                    AgentParameterSpec("output_name", AgentParameterType.STRING)
                ),
                postconditions = setOf("artifact_validated", "published_uri_readable")
            ),
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
