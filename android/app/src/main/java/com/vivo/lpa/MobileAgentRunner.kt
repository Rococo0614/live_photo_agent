package com.vivo.lpa

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class MobileAgentRunner(private val context: Context) {
    interface Listener {
        fun onProgress(message: String)
        fun onAssetsPrepared(uris: List<Uri>, assets: List<PreparedAsset>) {}
        fun onPlan(plan: MobilePlan)
        fun onResult(result: MobileToolResult)
        fun onFinished(uri: Uri?, message: String)
        fun onError(message: String)
    }

    private val preprocessor = LivePhotoPreprocessor(context)
    private val livePhotoSdk = LivePhotoSdkAdapter(context)

    fun run(
        query: String,
        uris: List<Uri>,
        layoutContext: JSONObject?,
        cancelled: AtomicBoolean,
        listener: Listener,
        preparedAssets: List<PreparedAsset>? = null,
        sessionId: String? = null
    ) {
        val jobDir = File(context.cacheDir, "agent-jobs/job-${System.currentTimeMillis()}").apply { mkdirs() }
        var lastOutput: File? = null
        var lastOutputIsImage = false
        var lastUri: Uri? = null
        try {
            val intentPlan = if (uris.isEmpty()) GenieTextPlanner(context, sessionId).tryIntent(query) else null
            val resolvedUris = if (uris.isNotEmpty()) {
                uris
            } else {
                val database = LibraryDatabase(context)
                val searchCall = intentPlan?.toolCalls?.firstOrNull { it.tool == "search_assets" }
                val previousQuery = previousQuery(sessionId, query)
                val continuationQuery = if (isTemplateChange(query)) previousQuery else query
                val searchQuery = if (isTemplateChange(query)) continuationQuery else
                    searchCall?.arguments?.optString("query", query).orEmpty().ifBlank { query }
                val explicitCount = database.requestedCount(query, 0)
                val previousCount = database.requestedCount(previousQuery, 0)
                val requested = explicitCount.takeIf { it > 0 }
                    ?: previousCount.takeIf { it > 0 }
                    ?: searchCall?.arguments?.optInt("k", 0)?.takeIf { it > 0 }
                    ?: 3
                val embedder = BgeEmbedder(context)
                val candidates = if (embedder.available()) {
                    database.searchEmbedding(embedder.embed(searchQuery), requested, searchQuery)
                } else {
                    listener.onProgress("BGE 检索模型未部署，无法进行网页端同等语义检索")
                    emptyList()
                }
                listener.onProgress("语义检索：${candidates.joinToString("、") { it.displayName }}")
                candidates.map { Uri.parse(it.sourceUri) }
            }
            if (resolvedUris.isEmpty()) throw IllegalArgumentException("素材库没有可推荐的媒体，请先添加素材")
            if (uris.isEmpty()) {
                listener.onProgress("素材库推荐 ${resolvedUris.size} 个最相关素材")
            }
            val assets = preparedAssets ?: preprocessor.prepare(
                resolvedUris,
                cancelled = { cancelled.get() },
                progress = { message -> listener.onProgress(message) }
            )
            if (cancelled.get()) throw InterruptedException("cancelled")
            listener.onAssetsPrepared(resolvedUris, assets)

            val planner = MobilePlanner(context, sessionId)
            val plan = intentPlan?.let { intent ->
                val executable = intent.toolCalls.filterNot { it.tool == "search_assets" }
                    .map { call ->
                        if (call.tool == "template_collage" || call.tool == "smart_collage") {
                            call.copy(arguments = call.arguments.put(
                                "asset_ids", JSONArray(assets.map { it.assetId })
                            ))
                        } else call
                    }
                executable.takeIf { it.isNotEmpty() }?.let {
                    intent.copy(selectedAssetIds = assets.map { it.assetId }, toolCalls = it)
                }
            } ?: planner.plan(query, assets, layoutContext)
            listener.onPlan(plan)
            if (plan.clarification != null) {
                listener.onFinished(null, plan.clarification)
                return
            }

            val assetById = assets.associateBy { it.assetId }
            plan.toolCalls.forEachIndexed { index, call ->
                if (cancelled.get()) throw InterruptedException("cancelled")
                listener.onProgress("执行 ${index + 1}/${plan.toolCalls.size}: ${call.tool}")
                when (call.tool) {
                    "asset_summarize" -> {
                        val payload = JSONArray()
                        assets.forEach { payload.put(JSONObject()
                            .put("asset_id", it.assetId)
                            .put("metadata", it.metadata)
                            .put("semantic", it.semantic)) }
                        val result = MobileToolResult(call.tool, true, JSONObject().put("assets", payload))
                        listener.onResult(result)
                    }
                    "clip_trim" -> {
                        val selected = selectedAssets(call.arguments, assets)
                        val input = selected.firstOrNull()?.videoFile
                            ?: throw IllegalArgumentException("裁剪需要包含视频轨道的 Live Photo 或视频")
                        val start = call.arguments.optLong("start_ms", 0L)
                        val end = call.arguments.optLong("end_ms", 1000L)
                        val result = VivoSdkExecutor.execute(
                            context, jobDir.name, "clip_trim", listOf(input.absolutePath),
                            start, end, "", 0, 0, jobDir, null
                        ) { percent, message ->
                            if (cancelled.get()) {
                                VivoSdkExecutor.cancel(jobDir.name)
                                throw InterruptedException("cancelled")
                            }
                            listener.onProgress("$message $percent%")
                        }
                        lastOutput = result.output
                        lastOutputIsImage = false
                        lastUri = null
                        val toolResult = MobileToolResult(call.tool, true, JSONObject()
                            .put("output_path", result.output.absolutePath)
                            .put("duration_ms", result.durationMs)
                            .put("execution_backend", "vivo_videoeditorsdk"))
                        listener.onResult(toolResult)
                    }
                    "video_mirror", "video_blur", "video_timeline_effect", "video_motion_effect", "video_transition",
                    "video_fit_band", "video_crop_band", "video_vertical_stack", "video_dynamic_sticker",
                    "video_border_watermark", "video_filter", "video_speed", "video_reverse" -> {
                        val selected = selectedAssets(call.arguments, assets)
                        val inputs = selected.mapNotNull { it.videoFile?.absolutePath }
                        if (inputs.isEmpty()) throw IllegalArgumentException("效果处理需要视频素材")
                        val videoArguments = if (call.tool == "video_filter") {
                            JSONObject(call.arguments.toString()).apply {
                                if (!has("contrast")) put("contrast", 0.12)
                                if (!has("saturation")) put("saturation", 0.08)
                                if (!has("filter_level")) put("filter_level", 0.8)
                            }
                        } else {
                            call.arguments
                        }
                        val result = VivoSdkExecutor.execute(
                            context, jobDir.name, call.tool, inputs, 0L, 0L, "", 0, 0, jobDir, videoArguments
                        ) { percent, message -> listener.onProgress("$message $percent%") }
                        lastOutput = result.output
                        lastOutputIsImage = false
                        lastUri = null
                        listener.onResult(MobileToolResult(call.tool, true, JSONObject()
                            .put("output_path", result.output.absolutePath)
                            .put("execution_backend", "vivo_videoeditorsdk")))
                    }
                    "brighten", "sharpen", "denoise", "color_tone", "portrait_beauty" -> {
                        val selected = selectedAssets(call.arguments, assets)
                        val input = selected.firstOrNull()?.imageFile
                            ?: throw IllegalArgumentException("${call.tool} 需要图片素材")
                        val result = VivoImageSdkExecutor.execute(
                            context, jobDir.name, call.tool, input.absolutePath, call.arguments, jobDir
                        ) { percent, message ->
                            if (cancelled.get()) {
                                VivoSdkExecutor.cancel(jobDir.name)
                                throw InterruptedException("cancelled")
                            }
                            listener.onProgress("$message $percent%")
                        }
                        lastOutput = result.output
                        lastOutputIsImage = true
                        lastUri = null
                        listener.onResult(MobileToolResult(call.tool, true, JSONObject()
                            .put("output_path", result.output.absolutePath)
                            .put("execution_backend", "vivo_image_sdk")))
                    }
                    "live_photo_filter_effect", "live_photo_motion_effect" -> {
                        val asset = selectedLiveAsset(call.arguments, assets)
                            ?: throw IllegalArgumentException("${call.tool} 需要 Live Photo 素材")
                        val decoded = decodeLiveAsset(
                            asset, File(jobDir, "decode-${call.tool}")
                        ) ?: throw IllegalArgumentException("输入不是 vivo Live Photo 或缺少可用运动轨道")
                        val effectArguments = JSONObject(call.arguments.toString())
                        val operation = if (call.tool == "live_photo_filter_effect") {
                            if (!effectArguments.has("contrast")) effectArguments.put("contrast", 0.18)
                            if (!effectArguments.has("saturation")) effectArguments.put("saturation", 0.2)
                            if (!effectArguments.has("temperature")) effectArguments.put("temperature", 0.12)
                            "video_filter"
                        } else {
                            if (!effectArguments.has("effect")) effectArguments.put("effect", "motion_light")
                            "video_motion_effect"
                        }
                        val effected = VivoSdkExecutor.execute(
                            context, jobDir.name, operation, listOf(decoded.video.absolutePath),
                            0L, 0L, "", 0, 0, jobDir, effectArguments
                        ) { percent, message ->
                            if (cancelled.get()) {
                                VivoSdkExecutor.cancel(jobDir.name)
                                throw InterruptedException("cancelled")
                            }
                            listener.onProgress("$message $percent%")
                        }
                        val cover = if (call.tool == "live_photo_filter_effect") {
                            val coverResult = VivoImageSdkExecutor.execute(
                                context,
                                "${jobDir.name}-cover",
                                "video_filter",
                                decoded.image.absolutePath,
                                effectArguments,
                                File(jobDir, "filter-cover")
                            ) { percent, message -> listener.onProgress("封面 $message $percent%") }
                            coverResult.output
                        } else {
                            decoded.image
                        }
                        val packed = livePhotoSdk.pack(
                            cover, effected.output, File(jobDir, "pack"),
                            decoded.photoTimestampUs
                        )
                        val outputUri = publishImage(packed, packed.name)
                        lastOutput = packed
                        lastOutputIsImage = true
                        lastUri = outputUri
                        listener.onResult(MobileToolResult(call.tool, true, JSONObject()
                            .put("output_path", packed.absolutePath)
                            .put("output_uri", outputUri.toString())
                            .put("execution_backend", "vivo_livephotosdk+vivo_videoeditorsdk")))
                    }
                    "set_display_frame" -> {
                        val asset = selectedLiveAsset(call.arguments, assets)
                            ?: throw IllegalArgumentException("换封面需要 Live Photo 素材")
                        val decoded = decodeLiveAsset(
                            asset, File(jobDir, "decode-cover")
                        ) ?: throw IllegalArgumentException("输入不是 vivo Live Photo 或缺少可用运动轨道")
                        val cover = extractCoverFrame(
                            decoded.video,
                            File(jobDir, "cover-frame"),
                            call.arguments.optLong("cover_time_ms", -1L)
                        ) ?: throw IllegalStateException("无法从运动轨道提取封面帧")
                        val packed = livePhotoSdk.pack(
                            cover,
                            decoded.video,
                            File(jobDir, "pack-cover"),
                            decoded.photoTimestampUs
                        )
                        val outputUri = publishImage(packed, packed.name)
                        lastOutput = packed
                        lastOutputIsImage = true
                        lastUri = outputUri
                        listener.onResult(MobileToolResult(call.tool, true, JSONObject()
                            .put("output_path", packed.absolutePath)
                            .put("output_uri", outputUri.toString())
                            .put("cover_time_ms", call.arguments.optLong("cover_time_ms", -1L))
                            .put("execution_backend", "vivo_livephotosdk")))
                    }
                    "live_xdr_process" -> {
                        val payload = livePhotoSdk.xdrAssets()
                        val output = File(jobDir, "live-xdr-assets.json")
                        output.writeText(payload.toString(2), Charsets.UTF_8)
                        lastOutput = null
                        lastUri = null
                        listener.onResult(MobileToolResult(call.tool, true, payload
                            .put("output_path", output.absolutePath)
                            .put("execution_backend", "vivo_mediaeffectsdk")))
                    }
                    "template_collage", "smart_collage" -> {
                        if (call.tool == "smart_collage" && assets.any { !it.semantic.optBoolean("ready", false) }) {
                            throw IllegalStateException("智能拼贴需要端侧 VLM 模型包；当前仅完成技术前处理")
                        }
                        val selected = selectedAssets(call.arguments, assets)
                        val catalog = MobileTemplateCatalog(context)
                        val changingTemplate = isTemplateChange(query)
                        val previousTemplateId = previousTemplateId(sessionId)
                        val requestedTemplate = call.arguments.optString("template_id")
                            .takeIf { it.isNotBlank() }
                            ?.let(catalog::find)
                            ?.takeIf { it.optJSONArray("slots")?.length() == selected.size }
                        val chosenTemplate = requestedTemplate
                            ?: catalog.chooseForCount(
                                query,
                                selected.size,
                                previousTemplateId.takeIf { changingTemplate }
                            )
                        val contextForLayout = when {
                            changingTemplate || requestedTemplate != null ->
                                MobileTemplateParser.parse(chosenTemplate, selected)
                            call.arguments.optJSONObject("layout_context") != null ->
                                call.arguments.optJSONObject("layout_context")!!
                            layoutContext != null -> layoutContext
                            else -> MobileTemplateParser.parse(chosenTemplate, selected)
                        }
                        val ordered = orderForLayout(selected, contextForLayout)
                        val inputs = ordered.mapNotNull { it.videoFile?.absolutePath }
                        if (inputs.size < 2) throw IllegalArgumentException("拼贴至少需要两个带运动轨道的素材")
                        val layout = contextForLayout.optString(
                            "layout_type", call.arguments.optString("layout", "vertical")
                        )
                        val canvas = call.arguments.optString("canvas", "").split("x")
                        val canvasWidth = contextForLayout.optInt("canvas_width", canvas.getOrNull(0)?.toIntOrNull() ?: 0)
                        val canvasHeight = contextForLayout.optInt("canvas_height", canvas.getOrNull(1)?.toIntOrNull() ?: 0)
                        val result = VivoSdkExecutor.execute(
                            context, jobDir.name, call.tool, inputs, 0L, 0L, layout,
                            canvasWidth, canvasHeight, jobDir, contextForLayout
                        ) { percent, message ->
                            if (cancelled.get()) {
                                VivoSdkExecutor.cancel(jobDir.name)
                                throw InterruptedException("cancelled")
                            }
                            listener.onProgress("$message $percent%")
                        }
                        lastOutput = result.output
                        lastOutputIsImage = false
                        lastUri = null
                        listener.onResult(MobileToolResult(call.tool, true, JSONObject()
                            .put("output_path", result.output.absolutePath)
                            .put("duration_ms", result.durationMs)
                            .put("template_id", contextForLayout.optString("template_id"))
                            .put("layout_context", contextForLayout)
                            .put("canvas_width", canvasWidth)
                            .put("canvas_height", canvasHeight)
                            .put("execution_backend", "vivo_videoeditorsdk")))
                    }
                    "export_mp4" -> {
                        if (lastOutputIsImage) {
                            throw IllegalStateException("当前中间结果是图片，不能导出为 MP4")
                        }
                        val output = lastOutput ?: throw IllegalStateException("没有可导出的中间结果")
                        val requestedName = call.arguments.optString("output_name").ifBlank {
                            "live-agent-export-${System.currentTimeMillis()}.mp4"
                        }
                        lastUri = publishVideo(output, requestedName)
                        val payload = JSONObject()
                            .put("output_uri", lastUri.toString())
                            .put("execution_backend", "android_media_store")
                        val liveAsset = selectedAssets(call.arguments, assets)
                            .firstOrNull { it.metadata.optString("media_format") == "livephoto" && it.imageFile != null }
                        if (liveAsset != null) {
                            val packed = livePhotoSdk.pack(liveAsset.imageFile!!, output, jobDir)
                            val liveUri = publishImage(packed, "${output.nameWithoutExtension}.jpg")
                            payload.put("live_photo_uri", liveUri.toString())
                            payload.put("live_photo_backend", "vivo_livephotosdk")
                            lastUri = liveUri
                        }
                        listener.onResult(MobileToolResult(call.tool, true, payload))
                    }
                    else -> {
                        val result = MobileToolResult(call.tool, false, JSONObject()
                            .put("error_code", "native_tool_not_mapped")
                            .put("message", "该工具必须接入目标 vivo SDK/QNN 后才能执行，当前未降级到 Python/ffmpeg"))
                        listener.onResult(result)
                        throw IllegalStateException("${call.tool}: native SDK mapping missing")
                    }
                }
            }

            if (lastUri == null && lastOutput != null) {
                if (lastOutputIsImage) {
                    lastUri = publishImage(lastOutput!!, lastOutput!!.name)
                } else {
                    val liveAsset = assets.firstOrNull {
                        it.metadata.optString("media_format") == "livephoto" && it.imageFile != null
                    }
                    if (liveAsset != null) {
                        val cover = extractCoverFrame(lastOutput!!, jobDir) ?: liveAsset.imageFile!!
                        val packed = livePhotoSdk.pack(cover, lastOutput!!, jobDir)
                        lastUri = publishImage(packed, "${lastOutput!!.nameWithoutExtension}.jpg")
                    } else {
                        lastUri = publishVideo(lastOutput!!, lastOutput!!.name)
                    }
                }
            }
            listener.onFinished(lastUri, if (lastUri == null) "已完成前处理" else "已写入系统相册")
        } catch (cancelledError: InterruptedException) {
            listener.onFinished(null, "用户已取消")
        } catch (error: Throwable) {
            val message = error.message ?: error::class.java.simpleName
            if (lastOutput?.isFile == true) {
                listener.onError("$message；中间结果已保留在应用缓存，未写入系统相册")
            } else {
                listener.onError(message)
            }
        }
    }

    private fun selectedAssets(arguments: JSONObject, assets: List<PreparedAsset>): List<PreparedAsset> {
        val ids = arguments.optJSONArray("asset_ids") ?: return assets
        val wanted = buildSet {
            for (index in 0 until ids.length()) add(ids.optString(index))
        }
        return assets.filter { it.assetId in wanted }.ifEmpty { assets }
    }

    private fun selectedLiveAsset(arguments: JSONObject, assets: List<PreparedAsset>): PreparedAsset? =
        selectedAssets(arguments, assets).firstOrNull {
            it.metadata.optString("media_format") == "livephoto" && it.videoFile != null && it.imageFile != null
        }

    private fun decodeLiveAsset(asset: PreparedAsset, outputDir: File): LivePhotoSdkAdapter.DecodedLivePhoto? {
        livePhotoSdk.decode(asset.sourceFile, outputDir)?.let { return it }
        val image = asset.imageFile ?: return null
        val video = asset.videoFile ?: return null
        return LivePhotoSdkAdapter.DecodedLivePhoto(image, video, 1_500_000L)
    }

    private fun isTemplateChange(query: String): Boolean {
        val lower = query.lowercase()
        return listOf(
            "换个模板", "换个模版", "换一种模板", "换一种模版", "改模板", "改模版", "换版式",
            "change template", "switch template", "change layout", "switch layout"
        )
            .any(lower::contains)
    }

    private fun previousTemplateId(sessionId: String?): String? {
        if (sessionId.isNullOrBlank()) return null
        val state = LibraryDatabase(context).sessionState(sessionId)
        return state.optJSONObject("layout_context")?.optString("template_id")
            ?.takeIf { it.isNotBlank() }
            ?: state.optJSONObject("selected_template")?.optString("id")?.takeIf { it.isNotBlank() }
    }

    private fun previousQuery(sessionId: String?, currentQuery: String): String {
        if (sessionId.isNullOrBlank()) return currentQuery
        val state = LibraryDatabase(context).sessionState(sessionId)
        val planGoal = state.optJSONObject("last_plan")?.optString("user_goal").orEmpty()
        if (planGoal.isNotBlank() && planGoal != currentQuery &&
            state.optJSONObject("last_plan")?.optJSONArray("tool_calls")?.length() ?: 0 > 0
        ) return planGoal
        return LibraryDatabase(context).recentMessages(sessionId, 24)
            .asSequence()
            .filter { it.startsWith("user: ") }
            .map { it.removePrefix("user: ").removePrefix("你\n").trim() }
            .firstOrNull { it.isNotBlank() && it != currentQuery && !isTemplateChange(it) }
            ?: currentQuery
    }

    private fun extractCoverFrame(video: File, outputDir: File, requestedTimeMs: Long = -1L): File? = runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(video.absolutePath)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val timeMs = when {
            requestedTimeMs >= 0L -> requestedTimeMs.coerceAtMost(durationMs)
            requestedTimeMs == -2L -> (durationMs * 0.9).toLong()
            else -> durationMs / 2L
        }
        val bitmap = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: return@runCatching null
        outputDir.mkdirs()
        val cover = File(outputDir, "final-cover-$timeMs.jpg")
        FileOutputStream(cover).use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream) }
        bitmap.recycle()
        retriever.release()
        cover
    }.getOrNull()

    private fun orderForLayout(assets: List<PreparedAsset>, layout: JSONObject?): List<PreparedAsset> {
        val items = layout?.optJSONArray("items") ?: return assets
        val byId = assets.associateBy { it.assetId }
        val ordered = mutableListOf<PreparedAsset>()
        for (index in 0 until items.length()) {
            val id = items.optJSONObject(index)?.optString("asset_id").orEmpty()
            byId[id]?.let { ordered += it }
        }
        return (ordered + assets.filterNot { ordered.contains(it) }).distinctBy { it.assetId }
    }

    private fun publishVideo(source: File, requestedName: String): Uri {
        val name = requestedName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".mp4", true)) it else "$it.mp4" }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LivePhotoAgent")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建系统相册视频条目")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output, 64 * 1024) }
            } ?: throw IllegalStateException("无法写入系统相册视频")
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun publishImage(source: File, requestedName: String): Uri {
        val isPng = source.extension.equals("png", true)
        val suffix = if (isPng) ".png" else ".jpg"
        val name = requestedName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let {
                if (it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true)) it
                else "$it$suffix"
            }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, if (isPng) "image/png" else "image/jpeg")
            if (!isPng) put("live_photo", "motionphoto00010000000000000")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LivePhotoAgent/Generated")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建系统相册 Live Photo 条目")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output, 64 * 1024) }
            } ?: throw IllegalStateException("无法写入系统相册 Live Photo")
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            if (!isPng) {
                resolver.update(uri, ContentValues().apply {
                    put("live_photo", "motionphoto00010000000000000")
                }, null, null)
            }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
