package com.vivo.lpa

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.vivo.imageprocess.ImageProcessRenderEngine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime-v2 single-tool executor.
 *
 * It never decides the next tool and never mutates [AgentRuntimeState]. Every successful branch
 * returns an explicit artifact/layout/asset delta for [AgentStateReducer]. The legacy static
 * runner remains intact while this implementation is verified behind `agentRuntime=loop`.
 */
class MobileToolExecutor(
    private val context: Context,
    private val query: String,
    private val sessionId: String?,
    private val jobDir: File,
    private val cancelled: AtomicBoolean,
    private val progress: (String) -> Unit
) {
    companion object {
        private const val REPLACE_MIN_SCORE = 0.48
        private val styleTools = setOf(
            "video_filter", "color_tone", "live_photo_filter_effect", "brighten", "portrait_beauty"
        )
    }

    private val preprocessor = LivePhotoPreprocessor(context)
    private val livePhotoSdk = LivePhotoSdkAdapter(context)

    fun execute(step: RuntimeStep, state: AgentRuntimeState): ToolExecutionResult {
        if (cancelled.get()) throw InterruptedException("cancelled")
        val spec = MobileToolRegistry.spec(step.tool)
            ?: throw IllegalArgumentException("未注册工具：${step.tool}")
        val availability = spec.availability(state.toolContext(step))
        if (!availability.available && step.tool !in setOf("search_assets")) {
            throw IllegalStateException("${step.tool} 当前不可执行：${availability.reason}")
        }

        val arguments = JSONObject(step.arguments.toString())
        if (step.tool in styleTools) applyStylePreset(arguments)
        if (step.tool == "video_filter") {
            if (!arguments.has("contrast")) arguments.put("contrast", 0.12)
            if (!arguments.has("saturation")) arguments.put("saturation", 0.08)
            if (!arguments.has("filter_level")) arguments.put("filter_level", 0.8)
        }

        val execution = when (step.tool) {
            "search_assets" -> executeSearch(step, arguments, state)
            "asset_summarize" -> executeSummary(step, state)
            "clip_trim" -> executeTrim(step, arguments, state)
            "video_mirror", "video_blur", "video_timeline_effect", "video_motion_effect",
            "video_transition", "video_fit_band", "video_crop_band", "video_vertical_stack",
            "video_dynamic_sticker", "video_border_watermark", "video_filter", "video_speed",
            "video_reverse" -> executeVideoTool(step, arguments, state)
            "brighten", "sharpen", "denoise", "color_tone", "portrait_beauty" ->
                executeImageTool(step, arguments, state)
            "live_photo_filter_effect", "live_photo_motion_effect" ->
                executeLivePhotoEffect(step, arguments, state)
            "set_display_frame" -> executeDisplayFrame(step, arguments, state)
            "live_xdr_process" -> executeLiveXdr(step)
            "template_collage" -> executeCollage(step, arguments, state)
            "layout_swap" -> executeLayoutSwap(step, arguments, state)
            "asset_replace" -> executeAssetReplace(step, arguments, state)
            "color_analysis" -> executeColorAnalysis(step, arguments, state)
            "publish_result", "export_mp4" -> executePublish(step, arguments, state)
            else -> throw IllegalStateException("${step.tool}: native SDK mapping missing")
        }
        execution.result.payload.put("resolved_arguments", JSONObject(arguments.toString()))
        return execution
    }

    private fun executeSearch(
        step: RuntimeStep,
        arguments: JSONObject,
        state: AgentRuntimeState
    ): ToolExecutionResult {
        val searchQuery = arguments.optString("query").ifBlank { query }
        val requested = arguments.optInt("k", 3).coerceAtLeast(1)
        val requiredMedia = parseMedia(arguments.optString("required_media"))
        // A RETRIEVE after the first observation is a补搜, not permission to return the same cast
        // and claim the missing count was filled. Explicit exclusions and current assets combine.
        val excludes = arguments.optJSONArray("exclude_asset_ids").strings().toSet() +
            state.sourceAssets.map { it.assetId }
        progress("语义检索：$searchQuery")
        val outcome = MobileAssetSearch(context).use { search ->
            search.search(AssetSearchRequest(
                query = searchQuery,
                requestedCount = requested,
                requiredMedia = requiredMedia,
                excludeAssetIds = excludes,
                minScore = arguments.optDouble("min_score").takeUnless { it.isNaN() || it == 0.0 }
            ))
        }
        val uris = outcome.selected.map { Uri.parse(it.sourceUri) }
        val prepared = if (uris.isEmpty()) emptyList() else preprocessor.prepare(
            uris,
            cancelled = { cancelled.get() },
            progress = progress
        )
        val payload = JSONObject()
            .put("query", searchQuery)
            .put("requested", requested)
            .put("returned", prepared.size)
            .put("asset_ids", JSONArray(prepared.map { it.assetId }))
            .put("candidates", JSONArray(outcome.candidates.map { candidate ->
                JSONObject()
                    .put("asset_id", candidate.record.assetId)
                    .put("display_name", candidate.record.displayName)
                    .put("media_format", candidate.record.mediaFormat)
                    .put("score", candidate.score)
                    .put("accepted", candidate.accepted)
                    .put("rejection_reason", candidate.rejectionReason)
            }))
            .put("execution_backend", "bge+sqlite")
        return ToolExecutionResult(
            MobileToolResult(step.tool, true, payload),
            StateDelta.AddAssets(prepared),
            invalidatesPendingPlan = prepared.size < requested
        )
    }

    private fun executeSummary(step: RuntimeStep, state: AgentRuntimeState): ToolExecutionResult {
        val assets = resolveAssets(step, state)
        val payload = JSONArray(assets.map { asset ->
            JSONObject()
                .put("asset_id", asset.assetId)
                .put("metadata", asset.metadata)
                .put("semantic", asset.semantic)
        })
        val result = JSONObject().put("assets", payload).put("execution_backend", "vlm_cache")
        val artifact = AgentArtifact(
            artifactId(step), AgentArtifactKind.JSON, step.stepId,
            metadataJson = result.toString(), validated = true
        )
        return ToolExecutionResult(
            MobileToolResult(step.tool, true, result),
            StateDelta.AddArtifact(artifact)
        )
    }

    private fun executeTrim(
        step: RuntimeStep,
        arguments: JSONObject,
        state: AgentRuntimeState
    ): ToolExecutionResult {
        val input = resolveVideoFiles(step, state).firstOrNull()
            ?: throw IllegalArgumentException("裁剪需要包含视频轨道的输入")
        val start = arguments.optLong("start_ms", 0L)
        val end = arguments.optLong("end_ms", 1000L)
        val result = VivoSdkExecutor.execute(
            context, jobId(step), "clip_trim", listOf(input.absolutePath),
            start, end, "", 0, 0, stepDir(step), null
        ) { percent, message -> sdkProgress(step, percent, message) }
        val artifact = videoArtifact(step, result.output)
        return artifactResult(step, artifact, JSONObject()
            .put("output_path", result.output.absolutePath)
            .put("duration_ms", result.durationMs)
            .put("execution_backend", "vivo_videoeditorsdk"))
    }

    private fun executeVideoTool(
        step: RuntimeStep,
        arguments: JSONObject,
        state: AgentRuntimeState
    ): ToolExecutionResult {
        val assets = resolveAssets(step, state)
        val perAsset = arguments.optBoolean("per_asset")
        val writeback = arguments.optBoolean("writeback")
        if (perAsset && writeback) {
            val deltas = mutableListOf<StateDelta>()
            val editedIds = mutableListOf<String>()
            assets.forEachIndexed { index, asset ->
                val input = asset.videoFile
                    ?: throw IllegalArgumentException("${step.tool} 需要带运动轨道的素材")
                val result = VivoSdkExecutor.execute(
                    context, "${jobId(step)}-$index", step.tool, listOf(input.absolutePath),
                    0L, 0L, "", 0, 0, editDir(asset.assetId), arguments
                ) { percent, message -> sdkProgress(step, percent, message) }
                deltas += StateDelta.ReplaceAsset(asset.assetId, asset.copy(videoFile = result.output))
                editedIds += asset.assetId
            }
            return ToolExecutionResult(
                MobileToolResult(step.tool, true, JSONObject()
                    .put("edited_asset_ids", JSONArray(editedIds))
                    .put("writeback", true)
                    .put("execution_backend", "vivo_videoeditorsdk")),
                StateDelta.Batch(deltas),
                invalidatesPendingPlan = true
            )
        }

        val inputs = resolveVideoFiles(step, state)
        val required = when (step.tool) {
            "video_vertical_stack" -> 3
            "video_transition", "video_dynamic_sticker" -> 2
            else -> 1
        }
        if (inputs.size < required) {
            throw IllegalArgumentException("${step.tool} 需要 $required 个视频输入，当前只有 ${inputs.size} 个")
        }
        val effectiveInputs = if (step.tool == "video_vertical_stack") inputs.take(3) else inputs
        if (step.tool == "video_reverse") {
            val source = effectiveInputs.first()
            val reverseInput = if (videoCodecMime(source).contains("hevc", ignoreCase = true)) {
                progress("HEVC 运动轨道正在规范化为 H.264 后倒放")
                VivoSdkExecutor.execute(
                    context,
                    "${jobId(step)}-normalize",
                    "video_fit_band",
                    listOf(source.absolutePath),
                    0L,
                    0L,
                    "",
                    0,
                    0,
                    File(stepDir(step), "reverse-normalize"),
                    JSONObject()
                ) { percent, message -> sdkProgress(step, percent, "规范化 $message") }.output
            } else source
            progress("独立进程正在倒放视频")
            val output = ReverseProcessExecutor.execute(
                context,
                jobId(step),
                reverseInput,
                stepDir(step)
            )
            return artifactResult(step, videoArtifact(step, output), JSONObject()
                .put("output_path", output.absolutePath)
                .put("input_codec", videoCodecMime(source))
                .put("normalized_before_reverse", reverseInput != source)
                .put("execution_backend", "vivo_videoreverser_isolated_process"))
        }
        val result = VivoSdkExecutor.execute(
            context, jobId(step), step.tool, effectiveInputs.map { it.absolutePath },
            0L, 0L, "", 0, 0, stepDir(step), arguments
        ) { percent, message -> sdkProgress(step, percent, message) }
        return artifactResult(step, videoArtifact(step, result.output), JSONObject()
            .put("output_path", result.output.absolutePath)
            .put("execution_backend", "vivo_videoeditorsdk"))
    }

    private fun executeImageTool(
        step: RuntimeStep,
        arguments: JSONObject,
        state: AgentRuntimeState
    ): ToolExecutionResult {
        val input = resolveImageFile(step, state)
            ?: throw IllegalArgumentException("${step.tool} 需要图片输入")
        val result = VivoImageSdkExecutor.execute(
            context, jobId(step), step.tool, input.absolutePath, arguments, stepDir(step)
        ) { percent, message -> sdkProgress(step, percent, message) }
        return artifactResult(step, imageArtifact(step, result.output), JSONObject()
            .put("output_path", result.output.absolutePath)
            .put("execution_backend", "vivo_image_sdk"))
    }

    private fun executeLivePhotoEffect(
        step: RuntimeStep,
        arguments: JSONObject,
        state: AgentRuntimeState
    ): ToolExecutionResult {
        val inputArtifact = resolveArtifact(step, state)
        val sourceAssets = resolveAssets(step, state).filter {
            it.metadata.optString("media_format") == "livephoto" &&
                it.imageFile != null && it.videoFile != null
        }
        if (inputArtifact == null && sourceAssets.isEmpty()) {
            throw IllegalArgumentException("${step.tool} 需要 Live Photo 输入")
        }
        val perAsset = arguments.optBoolean("per_asset")
        val writeback = arguments.optBoolean("writeback")
        if (inputArtifact == null && perAsset && writeback) {
            val deltas = mutableListOf<StateDelta>()
            val editedIds = mutableListOf<String>()
            sourceAssets.forEachIndexed { index, asset ->
                val decoded = decodeLiveAsset(asset, File(stepDir(step), "decode-$index"))
                    ?: throw IllegalArgumentException("输入不是可解码的 Live Photo")
                val effected = executeLiveMotion(step, arguments, decoded.video, index)
                deltas += StateDelta.ReplaceAsset(asset.assetId, asset.copy(videoFile = effected))
                editedIds += asset.assetId
            }
            return ToolExecutionResult(
                MobileToolResult(step.tool, true, JSONObject()
                    .put("edited_asset_ids", JSONArray(editedIds))
                    .put("writeback", true)
                    .put("execution_backend", "vivo_livephotosdk+vivo_videoeditorsdk")),
                StateDelta.Batch(deltas),
                invalidatesPendingPlan = true
            )
        }

        val decoded = if (inputArtifact != null) decodeLiveArtifact(inputArtifact) else
            decodeLiveAsset(sourceAssets.first(), File(stepDir(step), "decode"))
                ?: throw IllegalArgumentException("输入不是可解码的 Live Photo")
        val effected = executeLiveMotion(step, arguments, decoded.video, 0)
        val cover = if (step.tool == "live_photo_filter_effect") {
            VivoImageSdkExecutor.execute(
                context, "${jobId(step)}-cover", "video_filter",
                decoded.image.absolutePath, arguments, File(stepDir(step), "cover")
            ) { percent, message -> sdkProgress(step, percent, "封面 $message") }.output
        } else decoded.image
        val packed = livePhotoSdk.pack(cover, effected, File(stepDir(step), "pack"), decoded.photoTimestampUs)
        val artifact = AgentArtifact(
            artifactId(step), AgentArtifactKind.LIVE_PHOTO, step.stepId,
            file = packed, imageFile = cover, videoFile = effected,
            photoTimestampUs = decoded.photoTimestampUs, validated = true
        )
        return artifactResult(step, artifact, JSONObject()
            .put("output_path", packed.absolutePath)
            .put("execution_backend", "vivo_livephotosdk+vivo_videoeditorsdk"))
    }

    private fun executeLiveMotion(
        step: RuntimeStep,
        arguments: JSONObject,
        video: File,
        index: Int
    ): File {
        val operation = if (step.tool == "live_photo_filter_effect") "video_filter" else "video_motion_effect"
        if (operation == "video_motion_effect" && !arguments.has("effect")) {
            arguments.put("effect", "motion_light")
        }
        return VivoSdkExecutor.execute(
            context, "${jobId(step)}-$index", operation, listOf(video.absolutePath),
            0L, 0L, "", 0, 0, File(stepDir(step), "motion-$index"), arguments
        ) { percent, message -> sdkProgress(step, percent, message) }.output
    }

    private fun executeDisplayFrame(
        step: RuntimeStep,
        arguments: JSONObject,
        state: AgentRuntimeState
    ): ToolExecutionResult {
        val inputArtifact = resolveArtifact(step, state)
        val decoded = if (inputArtifact != null) decodeLiveArtifact(inputArtifact) else {
            val asset = resolveAssets(step, state).firstOrNull {
                it.metadata.optString("media_format") == "livephoto"
            } ?: throw IllegalArgumentException("换封面需要 Live Photo 输入")
            decodeLiveAsset(asset, File(stepDir(step), "decode"))
                ?: throw IllegalArgumentException("输入不是可解码的 Live Photo")
        }
        val timeMs = arguments.optLong("cover_time_ms", -1L)
        val cover = extractCoverFrame(decoded.video, File(stepDir(step), "cover-frame"), timeMs)
            ?: throw IllegalStateException("无法从运动轨道提取封面帧")
        val packed = livePhotoSdk.pack(cover, decoded.video, File(stepDir(step), "pack"), decoded.photoTimestampUs)
        val artifact = AgentArtifact(
            artifactId(step), AgentArtifactKind.LIVE_PHOTO, step.stepId,
            file = packed, imageFile = cover, videoFile = decoded.video,
            photoTimestampUs = decoded.photoTimestampUs, validated = true
        )
        return artifactResult(step, artifact, JSONObject()
            .put("output_path", packed.absolutePath)
            .put("cover_time_ms", timeMs)
            .put("execution_backend", "vivo_livephotosdk"))
    }

    private fun executeLiveXdr(step: RuntimeStep): ToolExecutionResult {
        val payload = livePhotoSdk.xdrAssets()
        val output = File(stepDir(step), "live-xdr-assets.json")
        output.writeText(payload.toString(2), Charsets.UTF_8)
        val artifact = AgentArtifact(
            artifactId(step), AgentArtifactKind.JSON, step.stepId,
            file = output, metadataJson = payload.toString(), validated = true
        )
        return artifactResult(step, artifact, payload
            .put("output_path", output.absolutePath)
            .put("execution_backend", "vivo_mediaeffectsdk"))
    }

    private fun executeCollage(
        step: RuntimeStep,
        arguments: JSONObject,
        state: AgentRuntimeState
    ): ToolExecutionResult {
        val assets = resolveAssets(step, state)
        if (assets.size < 2) throw IllegalArgumentException("拼贴至少需要两个素材")
        val catalog = MobileTemplateCatalog(context)
        val changingTemplate = isTemplateChange(query)
        val requested = arguments.optString("template_id").takeIf { it.isNotBlank() }
            ?.let(catalog::find)?.takeIf { it.optJSONArray("slots")?.length() == assets.size }
        val chosen = requested ?: catalog.chooseForCount(
            query, assets.size, previousTemplateId().takeIf { changingTemplate }
        )
        val currentLayout = state.layout?.let { JSONObject(it.toString()) }
        val argumentLayout = arguments.optJSONObject("layout_context")
        val orderedForParse = if (currentLayout != null) orderForLayout(assets, currentLayout) else assets
        var layout = when {
            arguments.optBoolean("reuse_layout") && currentLayout != null -> currentLayout
            changingTemplate || requested != null -> MobileTemplateParser.parse(chosen, orderedForParse)
            argumentLayout != null -> JSONObject(argumentLayout.toString())
            currentLayout != null -> currentLayout
            else -> MobileTemplateParser.parse(chosen, orderedForParse)
        }
        val slots = layout.optJSONArray("items")?.length() ?: 0
        if (slots > 0 && slots != assets.size) layout = MobileTemplateParser.parse(chosen, assets)
        val ordered = orderForLayout(assets, layout)
        val videos = ordered.mapNotNull { it.videoFile }
        if (videos.size < 2) throw IllegalArgumentException("当前拼贴后端至少需要两个视频轨道")
        val canvas = arguments.optString("canvas").split("x")
        val width = layout.optInt("canvas_width", canvas.getOrNull(0)?.toIntOrNull() ?: 0)
        val height = layout.optInt("canvas_height", canvas.getOrNull(1)?.toIntOrNull() ?: 0)
        val result = VivoSdkExecutor.execute(
            context, jobId(step), step.tool, videos.map { it.absolutePath }, 0L, 0L,
            layout.optString("layout_type", arguments.optString("layout", "vertical")),
            width, height, stepDir(step), layout
        ) { percent, message -> sdkProgress(step, percent, message) }
        return ToolExecutionResult(
            MobileToolResult(step.tool, true, JSONObject()
                .put("output_path", result.output.absolutePath)
                .put("duration_ms", result.durationMs)
                .put("template_id", layout.optString("template_id"))
                .put("layout_context", layout)
                .put("canvas_width", width)
                .put("canvas_height", height)
                .put("execution_backend", "vivo_videoeditorsdk")),
            StateDelta.Batch(listOf(
                StateDelta.UpdateLayout(layout),
                StateDelta.AddArtifact(videoArtifact(step, result.output))
            ))
        )
    }

    private fun executeLayoutSwap(
        step: RuntimeStep,
        arguments: JSONObject,
        state: AgentRuntimeState
    ): ToolExecutionResult {
        val ids = arguments.optJSONArray("asset_ids").strings()
        if (ids.size != 2) throw IllegalArgumentException("交换位置需要选中两个素材")
        val layout = state.layout?.let { JSONObject(it.toString()) }
            ?: throw IllegalStateException("当前没有可交换的拼贴布局")
        val items = layout.optJSONArray("items") ?: throw IllegalStateException("布局缺少 items")
        val indexes = (0 until items.length()).filter {
            items.optJSONObject(it)?.optString("asset_id") in ids
        }
        if (indexes.size != 2) throw IllegalStateException("选中的素材不在当前布局中")
        val first = items.optJSONObject(indexes[0])
        val second = items.optJSONObject(indexes[1])
        val firstId = first.optString("asset_id")
        first.put("asset_id", second.optString("asset_id"))
        second.put("asset_id", firstId)
        return ToolExecutionResult(
            MobileToolResult(step.tool, true, JSONObject()
                .put("swapped_asset_ids", JSONArray(ids))
                .put("layout_context", layout)
                .put("execution_backend", "layout_context")),
            StateDelta.UpdateLayout(layout),
            invalidatesPendingPlan = true
        )
    }

    private fun executeAssetReplace(
        step: RuntimeStep,
        arguments: JSONObject,
        state: AgentRuntimeState
    ): ToolExecutionResult {
        val targetId = arguments.optJSONArray("asset_ids")?.optString(0)
            ?.takeIf { it.isNotBlank() } ?: state.selectedAssetIds.singleOrNull()
            ?: throw IllegalArgumentException("替换需要选中一个素材")
        val searchQuery = arguments.optString("query").ifBlank {
            arguments.optString("effect").ifBlank { query }
        }
        val inUse = state.sourceAssets.map { it.assetId }.toSet() + targetId
        val outcome = MobileAssetSearch(context).use { search ->
            search.search(AssetSearchRequest(
                query = searchQuery,
                requestedCount = 1,
                excludeAssetIds = inUse,
                minScore = REPLACE_MIN_SCORE
            ))
        }
        val candidate = outcome.selected.firstOrNull()
            ?: throw IllegalStateException("没有找到与「$searchQuery」足够匹配的替换素材，已保留原素材")
        val prepared = preprocessor.prepare(
            listOf(Uri.parse(candidate.sourceUri)),
            cancelled = { cancelled.get() },
            progress = progress
        ).firstOrNull() ?: throw IllegalStateException("替换素材预处理失败")
        val score = outcome.candidates.firstOrNull { it.record.assetId == candidate.assetId }?.score
        return ToolExecutionResult(
            MobileToolResult(step.tool, true, JSONObject()
                .put("replaced_asset_id", targetId)
                .put("new_asset_id", prepared.assetId)
                .put("display_name", candidate.displayName)
                .put("search_query", searchQuery)
                .put("match_score", score)
                .put("execution_backend", "bge+library")),
            StateDelta.ReplaceAsset(targetId, prepared),
            invalidatesPendingPlan = true
        )
    }

    private fun executeColorAnalysis(
        step: RuntimeStep,
        arguments: JSONObject,
        state: AgentRuntimeState
    ): ToolExecutionResult {
        val assets = resolveAssets(step, state)
        val colorCount = arguments.optInt("color_count", 5).coerceIn(1, 8)
        val palette = JSONArray()
        val swatches = mutableListOf<List<Int>>()
        assets.forEach { asset ->
            val cover = asset.imageFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
                ?: asset.videoFile?.let { extractCoverFrame(it, stepDir(step)) }
                    ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                ?: throw IllegalStateException("素材缺少可分析的封面")
            val colors = ImageProcessRenderEngine.nativeAnalyzeDominantColor(cover, colorCount).orEmpty()
            cover.recycle()
            swatches += colors
            palette.put(JSONObject()
                .put("asset_id", asset.assetId)
                .put("dominant_colors", JSONArray(colors.map {
                    String.format("#%06X", 0xFFFFFF and it)
                })))
        }
        if (swatches.isEmpty()) throw IllegalStateException("没有可分析的素材")
        val size = 200
        val bitmap = Bitmap.createBitmap(size * colorCount, size * swatches.size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        swatches.forEachIndexed { row, colors ->
            colors.forEachIndexed { column, color ->
                canvas.drawRect(
                    android.graphics.RectF(
                        column * size.toFloat(), row * size.toFloat(),
                        (column + 1) * size.toFloat(), (row + 1) * size.toFloat()
                    ),
                    android.graphics.Paint().apply { this.color = color }
                )
            }
        }
        val output = File(stepDir(step), "palette.png")
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return artifactResult(step, imageArtifact(step, output, AgentArtifactKind.PALETTE), JSONObject()
            .put("palette", palette)
            .put("palette_image", output.absolutePath)
            .put("execution_backend", "imageprocess-cpu"))
    }

    private fun executePublish(
        step: RuntimeStep,
        arguments: JSONObject,
        state: AgentRuntimeState
    ): ToolExecutionResult {
        val artifact = resolveArtifact(step, state) ?: state.latestValidatedArtifact()
            ?: throw IllegalStateException("没有可发布的已验证产物")
        if (!artifact.validated) throw IllegalStateException("产物尚未通过验证，拒绝发布")
        if (artifact.published && artifact.contentUri != null) {
            return ToolExecutionResult(
                MobileToolResult(step.tool, true, JSONObject()
                    .put("output_uri", artifact.contentUri.toString())
                    .put("idempotent_reuse", true)),
                StateDelta.PublishArtifact(artifact.artifactId, artifact.contentUri)
            )
        }
        val requestedKind = arguments.optString("output_kind", "auto").lowercase()
        val uri = when (artifact.kind) {
            AgentArtifactKind.IMAGE, AgentArtifactKind.PALETTE -> {
                val file = artifact.file ?: artifact.imageFile
                    ?: throw IllegalStateException("图片产物缺少文件")
                publishImage(file, arguments.optString("output_name", file.name), isLivePhoto = false)
            }
            AgentArtifactKind.VIDEO -> {
                val video = artifact.videoFile ?: artifact.file
                    ?: throw IllegalStateException("视频产物缺少文件")
                val liveSource = state.sourceAssets.firstOrNull {
                    it.metadata.optString("media_format") == "livephoto" && it.imageFile != null
                }
                if (requestedKind != "video" && liveSource != null) {
                    val cover = extractCoverFrame(video, File(stepDir(step), "publish-cover"))
                        ?: liveSource.imageFile!!
                    val packed = livePhotoSdk.pack(cover, video, File(stepDir(step), "publish-pack"))
                    publishImage(
                        packed,
                        arguments.optString("output_name", "${video.nameWithoutExtension}.jpg"),
                        isLivePhoto = true
                    )
                } else {
                    publishVideo(video, arguments.optString("output_name", video.name))
                }
            }
            AgentArtifactKind.LIVE_PHOTO -> {
                val packed = artifact.file ?: run {
                    val image = artifact.imageFile ?: throw IllegalStateException("Live Photo 缺少封面")
                    val video = artifact.videoFile ?: throw IllegalStateException("Live Photo 缺少运动轨道")
                    livePhotoSdk.pack(image, video, File(stepDir(step), "publish-pack"), artifact.photoTimestampUs ?: 1_500_000L)
                }
                publishImage(packed, arguments.optString("output_name", packed.name), isLivePhoto = true)
            }
            else -> throw IllegalStateException("${artifact.kind} 不是可发布媒体")
        }
        return ToolExecutionResult(
            MobileToolResult(step.tool, true, JSONObject()
                .put("output_uri", uri.toString())
                .put("artifact_id", artifact.artifactId)
                .put("execution_backend", "android_media_store")),
            StateDelta.PublishArtifact(artifact.artifactId, uri)
        )
    }

    private fun artifactResult(
        step: RuntimeStep,
        artifact: AgentArtifact,
        payload: JSONObject
    ): ToolExecutionResult = ToolExecutionResult(
        MobileToolResult(step.tool, true, payload.put("artifact_id", artifact.artifactId)),
        StateDelta.AddArtifact(artifact)
    )

    private fun imageArtifact(
        step: RuntimeStep,
        file: File,
        kind: AgentArtifactKind = AgentArtifactKind.IMAGE
    ) = AgentArtifact(
        artifactId(step), kind, step.stepId, file = file, imageFile = file, validated = true
    )

    private fun videoArtifact(step: RuntimeStep, file: File) = AgentArtifact(
        artifactId(step), AgentArtifactKind.VIDEO, step.stepId,
        file = file, videoFile = file, validated = true
    )

    private fun artifactId(step: RuntimeStep): String = "artifact-${step.stepId}"

    private fun resolveAssets(step: RuntimeStep, state: AgentRuntimeState): List<PreparedAsset> {
        val refIds = step.inputs.filterIsInstance<AgentInputRef.AssetSet>()
            .firstOrNull()?.assetIds
        val argumentIds = step.arguments.optJSONArray("asset_ids").strings().takeIf { it.isNotEmpty() }
        val ids = refIds ?: argumentIds
        val base = if (ids == null) state.scopedAssets() else state.sourceAssets.filter { it.assetId in ids }
        if (ids != null && base.size != ids.distinct().size) {
            throw IllegalStateException("工具输入引用了不存在的素材")
        }
        return base
    }

    private fun resolveArtifact(step: RuntimeStep, state: AgentRuntimeState): AgentArtifact? {
        val id = step.inputs.filterIsInstance<AgentInputRef.Artifact>().firstOrNull()?.artifactId
            ?: return null
        return state.artifacts[id] ?: throw IllegalStateException("找不到输入产物 $id")
    }

    private fun resolveVideoFiles(step: RuntimeStep, state: AgentRuntimeState): List<File> {
        resolveArtifact(step, state)?.let { artifact ->
            val file = artifact.videoFile ?: artifact.file?.takeIf {
                artifact.kind == AgentArtifactKind.VIDEO
            }
            return listOf(file ?: throw IllegalStateException("${artifact.artifactId} 没有视频轨道"))
        }
        return resolveAssets(step, state).mapNotNull { it.videoFile }
    }

    private fun resolveImageFile(step: RuntimeStep, state: AgentRuntimeState): File? {
        resolveArtifact(step, state)?.let { artifact ->
            return artifact.imageFile ?: artifact.file?.takeIf {
                artifact.kind == AgentArtifactKind.IMAGE || artifact.kind == AgentArtifactKind.PALETTE
            }
        }
        return resolveAssets(step, state).firstOrNull()?.imageFile
    }

    private fun decodeLiveArtifact(artifact: AgentArtifact): LivePhotoSdkAdapter.DecodedLivePhoto {
        if (artifact.kind != AgentArtifactKind.LIVE_PHOTO) {
            throw IllegalArgumentException("${artifact.artifactId} 不是 Live Photo")
        }
        artifact.file?.let { file ->
            livePhotoSdk.decode(file, File(jobDir, "decode-${artifact.artifactId}"))?.let { return it }
        }
        return LivePhotoSdkAdapter.DecodedLivePhoto(
            artifact.imageFile ?: throw IllegalStateException("Live Photo artifact 缺少封面"),
            artifact.videoFile ?: throw IllegalStateException("Live Photo artifact 缺少运动轨道"),
            artifact.photoTimestampUs ?: 1_500_000L
        )
    }

    private fun decodeLiveAsset(
        asset: PreparedAsset,
        outputDir: File
    ): LivePhotoSdkAdapter.DecodedLivePhoto? {
        livePhotoSdk.decode(asset.sourceFile, outputDir)?.let { return it }
        val image = asset.imageFile ?: return null
        val video = asset.videoFile ?: return null
        return LivePhotoSdkAdapter.DecodedLivePhoto(image, video, 1_500_000L)
    }

    private fun stepDir(step: RuntimeStep): File = File(jobDir, step.stepId).apply { mkdirs() }
    private fun jobId(step: RuntimeStep): String = "${jobDir.name}-${step.stepId}"
    private fun editDir(assetId: String): File =
        File(context.cacheDir, "agent-edits/$assetId").apply { mkdirs() }

    private fun sdkProgress(step: RuntimeStep, percent: Int, message: String) {
        if (cancelled.get()) {
            VivoSdkExecutor.cancel(jobId(step))
            throw InterruptedException("cancelled")
        }
        progress("$message $percent%")
    }

    private fun applyStylePreset(arguments: JSONObject) {
        val style = arguments.optString("effect").lowercase(Locale.ROOT)
        if (style.isBlank()) return
        val values = JSONObject()
        when {
            listOf("黑白", "灰度", "mono", "grayscale", "black and white", "bw").any(style::contains) ->
                values.put("saturation", -1.0).put("contrast", 0.1).put("temperature", 0.0)
            listOf("冷色", "冷调", "cool", "cold").any(style::contains) ->
                values.put("temperature", -0.18)
            listOf("暖色", "暖调", "warm").any(style::contains) ->
                values.put("temperature", 0.18)
            listOf("鲜艳", "vivid", "saturate").any(style::contains) ->
                values.put("saturation", 0.3).put("contrast", 0.08)
            listOf("复古", "胶片", "vintage", "film").any(style::contains) ->
                values.put("saturation", -0.25).put("temperature", 0.1).put("contrast", -0.06)
            listOf("明亮", "提亮", "bright").any(style::contains) ->
                values.put("brightness", 0.15)
        }
        values.keys().forEach { key -> if (!arguments.has(key)) arguments.put(key, values.get(key)) }
    }

    private fun parseMedia(value: String): RuntimeMediaKind? = when (value.lowercase()) {
        "image", "photo" -> RuntimeMediaKind.IMAGE
        "video" -> RuntimeMediaKind.VIDEO
        "livephoto", "live_photo", "live photo" -> RuntimeMediaKind.LIVE_PHOTO
        "mixed" -> RuntimeMediaKind.MIXED
        else -> null
    }

    private fun videoCodecMime(file: File): String = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).asSequence()
                .map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                .firstOrNull { it.startsWith("video/") }
                .orEmpty()
        } finally {
            extractor.release()
        }
    }.getOrDefault("")

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }

    private fun isTemplateChange(text: String): Boolean {
        val lower = text.lowercase()
        return listOf(
            "换个模板", "换个模版", "换一种模板", "换一种模版", "改模板", "改模版", "换版式",
            "change template", "switch template", "change layout", "switch layout"
        ).any(lower::contains)
    }

    private fun previousTemplateId(): String? {
        if (sessionId.isNullOrBlank()) return null
        val database = LibraryDatabase(context)
        val saved = try {
            database.sessionState(sessionId)
        } finally {
            database.close()
        }
        return saved.optJSONObject("layout_context")?.optString("template_id")?.takeIf { it.isNotBlank() }
            ?: saved.optJSONObject("selected_template")?.optString("id")?.takeIf { it.isNotBlank() }
    }

    private fun orderForLayout(
        assets: List<PreparedAsset>,
        layout: JSONObject?
    ): List<PreparedAsset> {
        val items = layout?.optJSONArray("items") ?: return assets
        val byId = assets.associateBy { it.assetId }
        val ordered = (0 until items.length()).mapNotNull {
            byId[items.optJSONObject(it)?.optString("asset_id")]
        }
        return (ordered + assets.filterNot { it in ordered }).distinctBy { it.assetId }
    }

    private fun extractCoverFrame(
        video: File,
        outputDir: File,
        requestedTimeMs: Long = -1L
    ): File? = runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(video.absolutePath)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val time = when {
            requestedTimeMs >= 0L -> requestedTimeMs.coerceAtMost(duration)
            requestedTimeMs == -2L -> (duration * 0.9).toLong()
            else -> duration / 2L
        }
        val bitmap = retriever.getFrameAtTime(time * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: return@runCatching null
        outputDir.mkdirs()
        val output = File(outputDir, "cover-$time.jpg")
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        retriever.release()
        output
    }.getOrNull()

    private fun publishVideo(source: File, requestedName: String): Uri {
        val name = requestedName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".mp4", true)) it else "$it.mp4" }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, MobileAgentRunner.OUTPUT_VIDEO_PATH)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建系统相册视频条目")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(source).use { it.copyTo(output, 64 * 1024) }
            } ?: throw IllegalStateException("无法写入系统相册视频")
            if (Build.VERSION.SDK_INT >= 29) resolver.update(
                uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null
            )
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun publishImage(source: File, requestedName: String, isLivePhoto: Boolean): Uri {
        val png = source.extension.equals("png", true)
        val suffix = if (png) ".png" else ".jpg"
        val name = requestedName.replace(Regex("[^A-Za-z0-9._-]"), "_").let {
            if (it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true)) it
            else "$it$suffix"
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            if (!png && isLivePhoto) put("live_photo", "motionphoto00010000000000000")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, MobileAgentRunner.OUTPUT_IMAGE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建系统相册图片条目")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(source).use { it.copyTo(output, 64 * 1024) }
            } ?: throw IllegalStateException("无法写入系统相册图片")
            if (Build.VERSION.SDK_INT >= 29) resolver.update(
                uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null
            )
            if (!png && isLivePhoto) resolver.update(uri, ContentValues().apply {
                put("live_photo", "motionphoto00010000000000000")
            }, null, null)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
