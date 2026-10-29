package com.vivo.lpa

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vivo.imageprocess.ImageProcessRenderEngine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class MobileAgentRunner(private val context: Context) {

    companion object {
        /**
         * Where agent results land in the system gallery.
         *
         * These are the single source of truth: LibraryIndexer filters on them so that
         * generated output is never re-indexed as a source asset. Publishing a result
         * also sets the vendor `live_photo` motion marker, which means generated files
         * match the indexer's `live_photo IS NOT NULL` branch -- the path is the only
         * thing keeping them out, so both sides must read the same constant.
         */
        val OUTPUT_IMAGE_PATH: String =
            Environment.DIRECTORY_PICTURES + "/LivePhotoAgent/Generated"
        val OUTPUT_VIDEO_PATH: String =
            Environment.DIRECTORY_MOVIES + "/LivePhotoAgent"

        /**
         * Tools exempt from selection convergence.
         *
         * Collage composes the whole cast by design -- narrowing it to the tapped assets would
         * silently drop slots. layout_swap and asset_replace are arity-strict (exactly two ids /
         * exactly one target) and their own branches validate that, so converging them could
         * shrink the id list below the required arity and turn a valid step into an error.
         */
        private val unscopedTools = setOf(
            "template_collage", "layout_swap", "asset_replace"
        )

        /** Tools whose arguments may carry a style name that must be resolved to numeric params. */
        private val styleTools = setOf(
            "video_filter", "color_tone", "live_photo_filter_effect", "brighten", "portrait_beauty"
        )

        /**
         * Minimum cosine score for asset_replace to accept a candidate.
         *
         * Calibrated from audit data: genuine content replacements ("换成狗的", "换成一只猫的")
         * scored 0.51-0.69, while the misclassified filter query "黑白" scored 0.43. 0.48 sits in
         * the observed gap; it is a destructive-operation guard, not a content filter.
         */
        private const val REPLACE_MIN_SCORE = 0.48
    }
    interface Listener {
        fun onProgress(message: String)
        fun onAssetsPrepared(uris: List<Uri>, assets: List<PreparedAsset>) {}
        /**
         * Fired when asset_replace swaps an asset out mid-plan, so the strip can refresh.
         *
         * @param replacedIds maps each vacated asset_id to the one that took its slot. The caller
         *   uses it to move the user's tap-selection onto the replacement.
         */
        fun onAssetsUpdated(assets: List<PreparedAsset>, replacedIds: Map<String, String> = emptyMap()) {}
        /**
         * A tool step is about to run, with the arguments it will actually receive.
         *
         * [MobileToolResult] only reports the tool name and outcome, so the arguments a plan
         * carried -- the ones that decide which assets get touched and with which parameters --
         * were never recorded anywhere. This is the audit hook for them.
         */
        fun onToolCall(index: Int, total: Int, call: MobileToolCall) {}
        /**
         * The intent-stage brain attempt, logged the moment it completes.
         *
         * The intent plan is consumed for its search parameters and then discarded: on the
         * retrieval path its trace never reaches onPlan, so PLAN_BRAIN alone only ever shows
         * intent calls that took the chat fast-path. That survivorship is what made
         * intent_returned_tools unmeasurable (planner_reliability_plan.md §3.4).
         */
        fun onIntentTrace(trace: BrainTrace) {}
        /** Runtime-v2 semantic route, before concrete SDK tools are bound. */
        fun onRuntimeRoute(route: SemanticRoute, trace: BrainTrace) {}
        /** Every loop-model attempt, including outputs that failed to parse. */
        fun onRuntimeBrainTrace(stage: String, trace: BrainTrace) {}
        /** Exact catalogue shown to one runtime-v2 binding decision. */
        fun onToolCatalog(routeStep: SemanticRouteStep, decision: ToolCatalogDecision) {}
        /** A new concrete plan snapshot or suffix revision. */
        fun onRuntimePlan(plan: RuntimePlan) {}
        /** Runtime v3 shadow compilation; no DAG node is executed in this phase. */
        fun onRuntimeGraph(compilation: AgentDagCompilation) {}
        /** Versioned view of assets, tracks, artifacts and layout used by the DAG compiler. */
        fun onRuntimeContentGraph(snapshot: AgentContentGraphSnapshot) {}
        /** Goal IR preflight: bind, observe, ask, ready or unsupported. */
        fun onRuntimeGoal(goal: AgentGoalIr, result: AgentCompileResult) {}
        /** State change after one tool result has been reduced. */
        fun onStateDelta(stepId: String, delta: StateDelta, state: AgentRuntimeState) {}
        /** Runtime-v2 terminal decision after deterministic completion gates. */
        fun onRuntimeTerminal(state: AgentRuntimeState, message: String) {}
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
        sessionId: String? = null,
        selectedAssetIds: List<String> = emptyList()
    ) {
        if (BuildConfig.AGENT_RUNTIME in setOf("loop", "dag")) {
            MobileAgentLoop(context).run(
                query = query,
                uris = uris,
                layoutContext = layoutContext,
                cancelled = cancelled,
                listener = listener,
                preparedAssets = preparedAssets,
                sessionId = sessionId,
                selectedAssetIds = selectedAssetIds,
                dagShadow = BuildConfig.AGENT_RUNTIME == "dag"
            )
            return
        }
        val jobDir = File(context.cacheDir, "agent-jobs/job-${System.currentTimeMillis()}").apply { mkdirs() }
        var lastOutput: File? = null
        var lastOutputIsImage = false
        var lastUri: Uri? = null
        var published = false
        /**
         * Layout as it stands mid-plan.
         *
         * Deep-copied from the session layout so layout_swap / asset_replace mutate a private
         * object on the worker thread instead of aliasing the Activity's copy; the edited layout
         * is handed back only via onResult. The trailing re-collage composes this arrangement.
         */
        var workingLayout: JSONObject? = layoutContext?.let { JSONObject(it.toString()) }
        try {
            // A tap-selection must never be routed through the free-text intent planner: that
            // path returns a plan built from words alone and would silently drop the selection.
            // MobilePlanner.plan() is the only entry point that knows about selectedAssetIds.
            val intentPlan = if (uris.isEmpty() && selectedAssetIds.isEmpty()) {
                listener.onProgress("端侧大脑正在理解请求…")
                val intentPlanner = GenieTextPlanner(context, sessionId)
                val plan = intentPlanner.tryIntent(query)
                // Log the attempt whether or not it parsed into a usable plan: a null plan with
                // a raw output is exactly the failure the audit exists to capture.
                intentPlanner.lastTrace?.let { listener.onIntentTrace(it) }
                plan
            } else {
                null
            }
            // Chat and capability questions must answer before any retrieval runs. Searching with
            // the chat text as the query silently loads three unrelated assets into the strip and
            // leaves the user with no reply -- the exact failure a plain "你好 你可以做什么" produced.
            if (uris.isEmpty() && selectedAssetIds.isEmpty()) {
                // The keyword answer is a fallback for a missing brain only; when the brain is up
                // it understands capability questions itself through its chat channel.
                if (MobilePlanner.isCapabilityQuery(query) && !ModelPack.qwenReady(context)) {
                    val reply = MobilePlanner.CAPABILITY_TEXT
                    listener.onPlan(MobilePlan(query, "capability", emptyList(), emptyList(), reply))
                    listener.onFinished(null, reply)
                    return
                }
                // The brain looked at the sentence and found no tool chain at all: that is
                // conversation, not an edit request. Its clarifying words are the answer.
                intentPlan?.takeIf {
                    it.toolCalls.isEmpty() && !MobilePlanner.isExplicitEditQuery(query)
                }?.clarification?.let { reply ->
                    listener.onPlan(intentPlan)
                    listener.onFinished(null, reply)
                    return
                }
            }
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
            val assets = (preparedAssets ?: preprocessor.prepare(
                resolvedUris,
                cancelled = { cancelled.get() },
                progress = { message -> listener.onProgress(message) }
            )).toMutableList()
            if (cancelled.get()) throw InterruptedException("cancelled")
            listener.onAssetsPrepared(resolvedUris, assets)

            val planner = MobilePlanner(context, sessionId)
            // intentPlan came from the pre-retrieval prompt: it knows nothing about the assets that
            // were actually found, so it must not double as the final plan. Its only job is to
            // supply the search parameters consumed above. Planning happens here, once the cast is
            // real -- which also restores the deterministic colour-analysis pre-emption that this
            // shortcut used to bypass entirely.
            listener.onProgress("素材已就绪，正在规划执行步骤…")
            val plan = planner.plan(query, assets, workingLayout, selectedAssetIds)
            // Safety net: search_assets is consumed during URI resolution above and has no
            // execution mapping. Strip it from every plan source (genie STEP, restored
            // continuation) so it can never reach the runner's native-mapping fallback.
            val executablePlan = if (plan.toolCalls.any { it.tool == "search_assets" }) {
                plan.copy(toolCalls = plan.toolCalls.filterNot { it.tool == "search_assets" })
            } else plan
            // The tap-selection is the source of truth for what an edit may touch. The planner
            // already scopes calls to it, but a plan can also arrive from a restored continuation
            // or from a model that invented an ordinal, so converge here too: an edit step may
            // never reach an asset the user did not select.
            //
            // Excluded on purpose: collage tools act on the whole cast by design, and
            // layout_swap/asset_replace are arity-strict (exactly two / exactly one id) and are
            // validated by their own branches.
            val scopedPlan = if (selectedAssetIds.isNotEmpty()) {
                val allowed = selectedAssetIds.toSet()
                executablePlan.copy(toolCalls = executablePlan.toolCalls.map { call ->
                    if (call.tool in unscopedTools) return@map call
                    val ids = call.arguments.optJSONArray("asset_ids")
                        ?.let { array -> (0 until array.length()).map(array::optString) }
                        .orEmpty()
                    val kept = ids.filter { it in allowed }
                    // Empty intersection means the step targets nothing the user selected; leaving
                    // the original ids in place would edit the wrong asset, so drop the step.
                    if (ids.isNotEmpty() && kept.isEmpty()) return@map null
                    if (kept.size == ids.size) return@map call
                    call.copy(arguments = JSONObject(call.arguments.toString())
                        .put("asset_ids", JSONArray(kept)))
                }.filterNotNull())
            } else executablePlan
            listener.onPlan(scopedPlan)
            if (scopedPlan.clarification != null) {
                listener.onFinished(null, scopedPlan.clarification)
                return
            }
            // Device planner experiments need retrieval and real prepared-asset context, but not
            // dozens of identical SDK exports. This build-time-only arm stops after the exact same
            // PLAN_FINAL audit point used by production. The default is false, so a normal APK can
            // never silently skip user work.
            if (BuildConfig.PLANNER_EVAL_ONLY) {
                listener.onFinished(null, "规划评测完成（未执行工具）")
                return
            }

            // Style names ("黑白", "冷色") are the planner's vocabulary; the SDKs only read numeric
            // parameters. Resolve them once, before the loop, so the TOOL_CALL audit records exactly
            // the arguments each executor branch receives.
            //
            // video_filter's contrast/saturation/filter_level defaults live here too, not inside its
            // branch: onToolCall fires before the branch runs, so defaults injected there were
            // invisible to the audit. Keeping this the single place style arguments are finalised is
            // what lets the harness tell "the brain chose saturation=-1.0" from "a lookup table
            // filled it" (planner_reliability_plan.md §3.5 defect B).
            val styledPlan = scopedPlan.copy(toolCalls = scopedPlan.toolCalls.map { call ->
                if (call.tool !in styleTools) return@map call
                val resolved = applyStylePreset(call.arguments)
                if (call.tool == "video_filter") {
                    if (!resolved.has("contrast")) resolved.put("contrast", 0.12)
                    if (!resolved.has("saturation")) resolved.put("saturation", 0.08)
                    if (!resolved.has("filter_level")) resolved.put("filter_level", 0.8)
                }
                call.copy(arguments = resolved)
            })
            styledPlan.toolCalls.forEachIndexed { index, call ->
                if (cancelled.get()) throw InterruptedException("cancelled")
                listener.onProgress("执行 ${index + 1}/${styledPlan.toolCalls.size}: ${call.tool}")
                listener.onToolCall(index, styledPlan.toolCalls.size, call)
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
                        val perAsset = call.arguments.optBoolean("per_asset")
                        val writeback = call.arguments.optBoolean("writeback")
                        // Already resolved (preset + video_filter defaults) by the styledPlan
                        // mapping above, which is what onToolCall audited.
                        val videoArguments = call.arguments
                        if (perAsset && writeback) {
                            // One asset at a time, each result stored back under its own
                            // asset_id so the trailing re-collage finds the edited clip in its
                            // original slot. Nothing is published here.
                            selected.forEachIndexed { slot, asset ->
                                if (cancelled.get()) throw InterruptedException("cancelled")
                                val input = asset.videoFile?.absolutePath
                                    ?: throw IllegalArgumentException("${call.tool} 需要带运动轨道的素材")
                                listener.onProgress("处理选中素材 ${slot + 1}/${selected.size}：${call.tool}")
                                val result = VivoSdkExecutor.execute(
                                    context, "${jobDir.name}-$slot", call.tool, listOf(input),
                                    0L, 0L, "", 0, 0, editDir(asset.assetId), videoArguments
                                ) { percent, message -> listener.onProgress("$message $percent%") }
                                writebackVideo(assets, asset.assetId, result.output)
                            }
                            listener.onResult(MobileToolResult(call.tool, true, JSONObject()
                                .put("edited_asset_ids", JSONArray(selected.map { it.assetId }))
                                .put("writeback", true)
                                .put("execution_backend", "vivo_videoeditorsdk")))
                        } else {
                            val inputs = selected.mapNotNull { it.videoFile?.absolutePath }
                            val requiredVideos = when (call.tool) {
                                "video_vertical_stack" -> 3
                                "video_transition", "video_dynamic_sticker" -> 2
                                else -> 1
                            }
                            if (inputs.size < requiredVideos) {
                                throw IllegalArgumentException(
                                    "${call.tool} 需要 $requiredVideos 个视频素材，当前只有 ${inputs.size} 个"
                                )
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
                    }
                    "brighten", "sharpen", "denoise", "color_tone", "portrait_beauty" -> {
                        val selected = selectedAssets(call.arguments, assets)
                        val input = selected.firstOrNull()?.imageFile
                            ?: throw IllegalArgumentException("${call.tool} 需要图片素材")
                        val imageArguments = applyStylePreset(call.arguments)
                        val result = VivoImageSdkExecutor.execute(
                            context, jobDir.name, call.tool, input.absolutePath, imageArguments, jobDir
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
                        val selected = selectedAssets(call.arguments, assets)
                            .filter { it.metadata.optString("media_format") == "livephoto" && it.imageFile != null }
                        if (selected.isEmpty()) throw IllegalArgumentException("${call.tool} 需要 Live Photo 素材")
                        val perAsset = call.arguments.optBoolean("per_asset")
                        val writeback = call.arguments.optBoolean("writeback")

                        // One asset at a time. Under writeback the edited motion track is stored
                        // back onto the asset (no publish); the trailing re-collage reads
                        // videoFile, so that is the field that must carry the edit.
                        val targets = if (perAsset) selected else listOf(selected.first())
                        targets.forEachIndexed { slot, asset ->
                            if (cancelled.get()) throw InterruptedException("cancelled")
                            listener.onProgress("处理选中素材 ${slot + 1}/${targets.size}：${call.tool}")
                            val decoded = decodeLiveAsset(asset, File(jobDir, "decode-${call.tool}-$slot"))
                                ?: throw IllegalArgumentException("输入不是 vivo Live Photo 或缺少可用运动轨道")
                            val effectArguments = JSONObject(call.arguments.toString())
                            val operation = if (call.tool == "live_photo_filter_effect") {
                                val preset = applyStylePreset(effectArguments)
                                preset.keys().forEach { key -> effectArguments.put(key, preset.get(key)) }
                                if (!effectArguments.has("contrast")) effectArguments.put("contrast", 0.18)
                                if (!effectArguments.has("saturation")) effectArguments.put("saturation", 0.2)
                                if (!effectArguments.has("temperature")) effectArguments.put("temperature", 0.12)
                                "video_filter"
                            } else {
                                if (!effectArguments.has("effect")) effectArguments.put("effect", "motion_light")
                                "video_motion_effect"
                            }
                            val effected = VivoSdkExecutor.execute(
                                context, "${jobDir.name}-$slot", operation, listOf(decoded.video.absolutePath),
                                0L, 0L, "", 0, 0,
                                if (writeback) editDir(asset.assetId) else File(jobDir, "lp-$slot"),
                                effectArguments
                            ) { percent, message ->
                                if (cancelled.get()) {
                                    VivoSdkExecutor.cancel("${jobDir.name}-$slot")
                                    throw InterruptedException("cancelled")
                                }
                                listener.onProgress("$message $percent%")
                            }

                            if (writeback) {
                                writebackVideo(assets, asset.assetId, effected.output)
                                return@forEachIndexed
                            }

                            val cover = if (call.tool == "live_photo_filter_effect") {
                                val coverResult = VivoImageSdkExecutor.execute(
                                    context, "${jobDir.name}-cover-$slot", "video_filter",
                                    decoded.image.absolutePath, effectArguments, File(jobDir, "filter-cover-$slot")
                                ) { percent, message -> listener.onProgress("封面 $message $percent%") }
                                coverResult.output
                            } else {
                                decoded.image
                            }
                            val packed = livePhotoSdk.pack(
                                cover, effected.output, File(jobDir, "pack-$slot"), decoded.photoTimestampUs
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
                        if (writeback) {
                            listener.onResult(MobileToolResult(call.tool, true, JSONObject()
                                .put("edited_asset_ids", JSONArray(targets.map { it.assetId }))
                                .put("writeback", true)
                                .put("execution_backend", "vivo_livephotosdk+vivo_videoeditorsdk")))
                        }
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
                    "template_collage" -> {
                        // Plans embed an asset_ids snapshot taken when the plan was built. An
                        // earlier asset_replace step in the same plan makes that snapshot stale:
                        // filtering by it would drop the freshly replaced asset (its id is not in
                        // the snapshot) and the vacated id, shrinking the cast and shifting every
                        // later slot. When any snapshot id no longer exists, take the cast from
                        // the live layout instead, which replace/swap already repointed.
                        val snapshotIds = call.arguments.optJSONArray("asset_ids")
                            ?.let { array -> (0 until array.length()).map(array::optString) }
                        val snapshotStale = snapshotIds?.any { id -> assets.none { it.assetId == id } } == true
                        if (snapshotStale) {
                            val liveIds = workingLayout?.optJSONArray("items")
                                ?.let { items ->
                                    (0 until items.length()).mapNotNull { position ->
                                        items.optJSONObject(position)?.optString("asset_id")
                                            .takeIf { id -> assets.any { it.assetId == id } }
                                    }
                                }?.ifEmpty { assets.map { it.assetId } }
                                ?: assets.map { it.assetId }
                            call.arguments.put("asset_ids", JSONArray(liveIds))
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
                        // parse() assigns assets to slots positionally, so feeding it the raw
                        // prepared-asset order would silently undo any earlier layout_swap.
                        // Reordering to the current slot arrangement first is what makes
                        // "keep the assets, change the layout" actually keep the arrangement.
                        val orderedForParse = if (workingLayout != null) {
                            orderForLayout(selected, workingLayout)
                        } else {
                            selected
                        }
                        var contextForLayout = when {
                            // Explicit re-compose after a selection edit: reuse the working
                            // layout verbatim (it already carries any swap/replace), so slot
                            // geometry and asset order survive the edit.
                            call.arguments.optBoolean("reuse_layout") && workingLayout != null -> workingLayout!!
                            changingTemplate || requestedTemplate != null ->
                                MobileTemplateParser.parse(chosenTemplate, orderedForParse)
                            call.arguments.optJSONObject("layout_context") != null ->
                                call.arguments.optJSONObject("layout_context")!!
                            workingLayout != null -> workingLayout!!
                            else -> MobileTemplateParser.parse(chosenTemplate, orderedForParse)
                        }
                        // Guardrail: a reused layout whose slot count no longer matches the cast
                        // would feed fewer inputs than slots and render empty bands. Re-parse the
                        // chosen template (already sized to the cast) instead of dropping slots.
                        val slotCount = contextForLayout.optJSONArray("items")?.length() ?: 0
                        if (slotCount > 0 && slotCount != selected.size) {
                            contextForLayout = MobileTemplateParser.parse(chosenTemplate, selected)
                        }
                        workingLayout = contextForLayout
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
                    "layout_swap" -> {
                        val ids = call.arguments.optJSONArray("asset_ids")
                            ?.let { array -> (0 until array.length()).map(array::optString) }
                            .orEmpty()
                        if (ids.size != 2) throw IllegalArgumentException("交换位置需要选中两个素材")
                        val layout = workingLayout
                            ?: throw IllegalStateException("当前没有可交换的拼贴布局，请先完成一次拼接")
                        val items = layout.optJSONArray("items")
                            ?: throw IllegalStateException("布局缺少 items，无法交换位置")

                        // Swap the asset_id references, not the geometry. Slot position, size and
                        // timing belong to the layout; the user asked to move the *content*.
                        val indexes = (0 until items.length()).filter { index ->
                            items.optJSONObject(index)?.optString("asset_id") in ids
                        }
                        if (indexes.size != 2) {
                            throw IllegalStateException("选中的素材不在当前布局中，无法交换位置")
                        }
                        val a = items.optJSONObject(indexes[0])
                        val b = items.optJSONObject(indexes[1])
                        val idA = a.optString("asset_id")
                        val idB = b.optString("asset_id")
                        a.put("asset_id", idB)
                        b.put("asset_id", idA)
                        listener.onProgress("已交换两个选中素材的位置")

                        workingLayout = layout
                        listener.onResult(MobileToolResult(call.tool, true, JSONObject()
                            .put("swapped_asset_ids", JSONArray(ids))
                            .put("layout_context", layout)
                            .put("execution_backend", "layout_context")))
                    }
                    "asset_replace" -> {
                        val targetId = call.arguments.optJSONArray("asset_ids")?.optString(0)
                            ?: throw IllegalArgumentException("替换需要选中一个素材")
                        val searchQuery = call.arguments.optString("query").ifBlank { query }
                        val database = LibraryDatabase(context)
                        val embedder = BgeEmbedder(context)
                        if (!embedder.available()) {
                            throw IllegalStateException("BGE 检索模型未部署，无法按语义替换素材")
                        }
                        listener.onProgress("语义检索替换素材：$searchQuery")
                        // Over-fetch so the current cast can be excluded: the replacement must be
                        // a genuinely different asset, not one already occupying a slot.
                        val inUse = assets.map { it.assetId }.toSet() + targetId
                        val ranked = database.searchEmbeddingScored(embedder.embed(searchQuery), 8, searchQuery)
                        val (candidate, candidateScore) = ranked.firstOrNull { it.first.assetId !in inUse }
                            ?: throw IllegalStateException("素材库没有可用于替换的「$searchQuery」素材")
                        // A replacement is destructive: it throws away the asset the user tapped.
                        // When the brain misreads a recolour request as a retrieval, the query is a
                        // style name that matches nothing well, and the old code swapped in whatever
                        // ranked first regardless of how weak it was. Refusing a weak match keeps the
                        // asset intact and tells the user, which is recoverable; a wrong photo is not.
                        if (candidateScore < REPLACE_MIN_SCORE) {
                            throw IllegalStateException(
                                "没有找到与「$searchQuery」足够匹配的素材（最高相似度 " +
                                    String.format("%.2f", candidateScore) + "），已保留原素材。" +
                                    "如果想换的是滤镜或色调，请直接说明风格名称。"
                            )
                        }

                        val prepared = preprocessor.prepare(
                            listOf(Uri.parse(candidate.sourceUri)),
                            cancelled = { cancelled.get() },
                            progress = { message -> listener.onProgress(message) }
                        ).firstOrNull() ?: throw IllegalStateException("替换素材预处理失败")

                        val index = assets.indexOfFirst { it.assetId == targetId }
                        if (index < 0) throw IllegalStateException("被替换的素材不在当前会话中")
                        assets[index] = prepared

                        // Keep the slot geometry, repoint it at the new asset_id.
                        workingLayout?.optJSONArray("items")?.let { items ->
                            for (position in 0 until items.length()) {
                                val item = items.optJSONObject(position) ?: continue
                                if (item.optString("asset_id") == targetId) item.put("asset_id", prepared.assetId)
                            }
                        }
                        // The user's tap was on the slot, not on the old asset: the replacement
                        // inherits the selection so the next instruction keeps addressing the same
                        // slot. Without this the strip silently deselects and the following turn
                        // falls back to whole-cast semantics.
                        listener.onAssetsUpdated(assets.toList(), mapOf(targetId to prepared.assetId))
                        listener.onResult(MobileToolResult(call.tool, true, JSONObject()
                            .put("replaced_asset_id", targetId)
                            .put("new_asset_id", prepared.assetId)
                            .put("display_name", candidate.displayName)
                            .put("search_query", searchQuery)
                            .put("match_score", candidateScore)
                            .put("candidates", JSONArray(ranked.map { (record, score) ->
                                JSONObject()
                                    .put("asset_id", record.assetId)
                                    .put("display_name", record.displayName)
                                    .put("score", score)
                                    .put("excluded", record.assetId in inUse)
                            }))
                            .put("layout_context", workingLayout ?: JSONObject())
                            .put("execution_backend", "bge+library")))
                    }
                    "color_analysis" -> {
                        val selected = selectedAssets(call.arguments, assets)
                        val colorCount = call.arguments.optInt("color_count", 5)
                        val palette = JSONArray()
                        val swatches = mutableListOf<List<Int>>()
                        selected.forEach { asset ->
                            val cover = asset.imageFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
                                ?: asset.videoFile?.let { extractCoverFrame(it, jobDir) }
                                    ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                                ?: throw IllegalStateException("素材缺少可分析的封面图")
                            val colors = ImageProcessRenderEngine.nativeAnalyzeDominantColor(cover, colorCount)
                                .orEmpty()
                            cover.recycle()
                            val hex = JSONArray()
                            colors.forEach { hex.put(String.format("#%06X", 0xFFFFFF and it)) }
                            palette.put(JSONObject()
                                .put("asset_id", asset.assetId)
                                .put("dominant_colors", hex))
                            swatches += colors
                        }
                        if (swatches.isEmpty()) throw IllegalStateException("没有可分析的素材")
                        val swatchWidth = 200
                        val paletteImage = Bitmap.createBitmap(
                            swatchWidth * colorCount, swatchWidth * swatches.size, Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(paletteImage)
                        swatches.forEachIndexed { row, colors ->
                            colors.forEachIndexed { column, color ->
                                canvas.drawRect(
                                    android.graphics.RectF(
                                        column * swatchWidth.toFloat(), row * swatchWidth.toFloat(),
                                        (column + 1) * swatchWidth.toFloat(), (row + 1) * swatchWidth.toFloat()
                                    ),
                                    android.graphics.Paint().apply { this.color = color }
                                )
                            }
                        }
                        val paletteFile = File(jobDir, "palette.png")
                        paletteFile.outputStream().use {
                            paletteImage.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        paletteImage.recycle()
                        lastOutput = paletteFile
                        lastOutputIsImage = true
                        lastUri = null
                        listener.onProgress("主色提取完成 ${swatches.size} 个素材")
                        listener.onResult(MobileToolResult(call.tool, true, JSONObject()
                            .put("palette", palette)
                            .put("palette_image", paletteFile.absolutePath)
                            .put("execution_backend", "imageprocess-cpu")))
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
            published = true
            listener.onFinished(lastUri, if (lastUri == null) "已完成前处理" else "已写入系统相册")
        } catch (cancelledError: InterruptedException) {
            listener.onFinished(null, "用户已取消")
        } catch (error: Throwable) {
            val message = error.message ?: error::class.java.simpleName
            if (lastOutput?.isFile == true) {
                listener.onError("$message；中间结果已保留在缓存 ${jobDir.name}，未写入系统相册")
            } else {
                listener.onError(message)
            }
        } finally {
            // Only reclaim the workspace once the result is safely in the system gallery.
            // On failure or cancel the intermediates stay put, because the error message
            // above points the user at them for manual recovery.
            if (published) jobDir.deleteRecursively()
        }
    }

    /**
     * Stable per-asset directory for selection edits.
     *
     * jobDir is deleted once the result reaches the gallery, so an edited clip stored there
     * would dangle the next time the user taps the same asset. Keying by asset_id also means a
     * second edit overwrites the first rather than accumulating one file per attempt.
     */
    private fun editDir(assetId: String): File =
        File(context.cacheDir, "agent-edits/$assetId").apply { mkdirs() }

    /**
     * Stores an edited motion track back onto its asset, keeping the asset_id.
     *
     * Preserving the id is the whole point: layout_context items reference slots by asset_id,
     * so a new id would orphan the slot and the re-collage would silently drop the asset.
     */
    private fun writebackVideo(assets: MutableList<PreparedAsset>, assetId: String, edited: File) {
        val index = assets.indexOfFirst { it.assetId == assetId }
        if (index < 0) return
        assets[index] = assets[index].copy(videoFile = edited)
    }

    /**
     * Turns a style *name* into the numeric parameters the SDKs actually consume.
     *
     * The brain plans filters by name ("黑白", "冷色", "复古") but both the video and image
     * executors only read contrast/saturation/temperature/brightness -- an unmapped effect string
     * was silently ignored, so "黑白" produced a plain warm default. Mapping here keeps the name as
     * the planner's vocabulary while the executor stays numeric. Explicit numbers in the plan win.
     */
    private fun applyStylePreset(arguments: JSONObject): JSONObject {
        // Always a copy, never the input reference. The video_filter branch mutates the result
        // in place to add contrast/saturation/filter_level defaults; returning `arguments`
        // itself wrote those defaults back into the plan's own tool call. Because logAudit runs
        // on the UI thread, PLAN_FINAL was serialised after that mutation and recorded
        // parameters the brain never emitted -- which both corrupted the audit and masked
        // unresolved style names, since the defaults made every call look parameterised.
        val out = JSONObject(arguments.toString())
        val style = out.optString("effect").lowercase(Locale.ROOT)
        if (style.isBlank()) return out
        val preset: (JSONObject) -> Unit = when {
            listOf("黑白", "灰度", "mono", "grayscale", "black and white", "bw").any(style::contains) ->
                { target: JSONObject -> target.put("saturation", -1.0); target.put("contrast", 0.1); target.put("temperature", 0.0) }
            listOf("冷色", "冷调", "cool", "cold").any(style::contains) ->
                { target: JSONObject -> target.put("temperature", -0.18) }
            listOf("暖色", "暖调", "warm").any(style::contains) ->
                { target: JSONObject -> target.put("temperature", 0.18) }
            listOf("鲜艳", "vivid", "saturate").any(style::contains) ->
                { target: JSONObject -> target.put("saturation", 0.3); target.put("contrast", 0.08) }
            listOf("复古", "胶片", "vintage", "film").any(style::contains) ->
                { target: JSONObject -> target.put("saturation", -0.25); target.put("temperature", 0.1); target.put("contrast", -0.06) }
            listOf("明亮", "提亮", "bright").any(style::contains) ->
                { target: JSONObject -> target.put("brightness", 0.15) }
            else -> return out
        }
        presetKeys(out, preset)
        return out
    }

    /** Preset values fill only what the plan did not already set explicitly. */
    private fun presetKeys(target: JSONObject, preset: (JSONObject) -> Unit) {
        val filled = JSONObject()
        preset(filled)
        filled.keys().forEach { key -> if (!target.has(key)) target.put(key, filled.get(key)) }
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
                put(MediaStore.Video.Media.RELATIVE_PATH, OUTPUT_VIDEO_PATH)
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
                put(MediaStore.Images.Media.RELATIVE_PATH, OUTPUT_IMAGE_PATH)
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
