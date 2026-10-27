package com.vivo.lpa

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/** Real-device acceptance for every Android registry entry, independent of LLM routing quality. */
@RunWith(AndroidJUnit4::class)
class RuntimeToolAcceptanceTest {
    companion object {
        private const val TAG = "LPA_TOOL_ACCEPTANCE"
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cancelled = AtomicBoolean(false)

    @Test
    fun executeEveryRegisteredTool() {
        val root = File(context.cacheDir, "runtime-tool-acceptance").apply {
            deleteRecursively()
            mkdirs()
        }
        val reportFile = File(context.filesDir, "runtime-tool-acceptance.json")
        val records = LibraryDatabase(context).list()
            .filter { it.mediaFormat == "livephoto" }
            .take(3)
        assertTrue("需要至少3个已索引 Live Photo fixture", records.size >= 3)
        val assets = LivePhotoPreprocessor(context).prepare(
            records.map { Uri.parse(it.sourceUri) },
            cancelled = { false },
            progress = { Log.i(TAG, it) }
        )
        assertTrue("fixture prepare failed", assets.size >= 3)

        val image = assets.first().imageFile ?: error("fixture has no image track")
        val video = assets.first().videoFile ?: error("fixture has no video track")
        val imageArtifact = AgentArtifact(
            "fixture-image", AgentArtifactKind.IMAGE, "fixture-producer",
            file = image, imageFile = image, validated = true
        )
        val videoArtifact = AgentArtifact(
            "fixture-video", AgentArtifactKind.VIDEO, "fixture-producer",
            file = video, videoFile = video, validated = true
        )
        val liveArtifact = AgentArtifact(
            "fixture-live", AgentArtifactKind.LIVE_PHOTO, "fixture-producer",
            file = assets.first().sourceFile,
            imageFile = image,
            videoFile = video,
            validated = true
        )
        val template = MobileTemplateCatalog(context).chooseForCount("三拼", 3)
        val layout = MobileTemplateParser.parse(template, assets.take(3))

        val results = JSONArray()
        val failures = mutableListOf<String>()
        val attempted = linkedSetOf<String>()
        val published = mutableListOf<Uri>()

        fun probe(
            tool: String,
            state: AgentRuntimeState,
            arguments: JSONObject = JSONObject(),
            inputs: List<AgentInputRef> = defaultInputs(state),
            validate: (ToolExecutionResult, AgentRuntimeState) -> Unit = { execution, reduced ->
                validateExecution(execution, reduced)
            }
        ) {
            attempted += tool
            val started = System.currentTimeMillis()
            try {
                val spec = MobileToolRegistry.spec(tool) ?: error("missing ToolSpec")
                val step = RuntimeStep(
                    stepId = "accept-${attempted.size}-${tool}",
                    family = spec.family,
                    tool = tool,
                    arguments = JSONObject(arguments.toString()),
                    inputs = inputs,
                    idempotencyKey = "acceptance:$tool"
                )
                val stateWithStep = state.copy(plan = (state.plan ?: emptyPlan()).copy(
                    steps = (state.plan?.steps ?: emptyList()) + step
                ))
                val executor = MobileToolExecutor(
                    context = context,
                    query = "真机验收 $tool",
                    sessionId = null,
                    jobDir = File(root, tool).apply { mkdirs() },
                    cancelled = cancelled,
                    progress = { message -> Log.i(TAG, "$tool $message") }
                )
                val execution = executor.execute(step, stateWithStep)
                var reduced = AgentStateReducer.apply(stateWithStep, execution.delta)
                reduced = AgentStateReducer.apply(reduced, StateDelta.MarkStepCompleted(step.stepId))
                assertTrue("$tool returned success=false", execution.result.success)
                validate(execution, reduced)
                execution.result.payload.optString("output_uri")
                    .takeIf { it.isNotBlank() }?.let { published += Uri.parse(it) }
                results.put(JSONObject()
                    .put("tool", tool)
                    .put("success", true)
                    .put("elapsed_ms", System.currentTimeMillis() - started)
                    .put("payload", execution.result.payload))
            } catch (error: Throwable) {
                val message = "$tool: ${error::class.java.simpleName}: ${error.message}"
                failures += message
                results.put(JSONObject()
                    .put("tool", tool)
                    .put("success", false)
                    .put("elapsed_ms", System.currentTimeMillis() - started)
                    .put("error", message))
                Log.e(TAG, message, error)
            }
        }

        val base = baseState(assets)
        val imageState = artifactState(assets, imageArtifact)
        val videoState = artifactState(assets, videoArtifact)
        val liveState = artifactState(assets.take(1), liveArtifact)

        // Retrieval and analysis.
        probe("search_assets", emptyState(), JSONObject().put("query", "猫").put("k", 1), emptyList())
        probe("asset_summarize", base)
        probe("color_analysis", base.copy(selectedAssetIds = listOf(assets[0].assetId)))
        probe("live_xdr_process", liveState, inputs = listOf(AgentInputRef.AssetSet(listOf(assets[0].assetId))))

        // Single-input video tools use an explicit VIDEO artifact, proving they do not fall back
        // to the original Live Photo track.
        probe("clip_trim", videoState, JSONObject().put("start_ms", 0).put("end_ms", 500))
        probe("video_mirror", videoState)
        probe("video_blur", videoState)
        probe("video_timeline_effect", videoState)
        probe("video_motion_effect", videoState, JSONObject().put("effect", "motion_light"))
        // VideoReverser is a different SDK path from EditorVideoProcessor. Run its atomic
        // acceptance before the fit/crop reference processors; a separate test below validates
        // the realistic fit -> reverse chain in one bounded task.
        probe("video_reverse", videoState)
        probe("video_fit_band", videoState)
        probe("video_crop_band", videoState)
        probe("video_border_watermark", videoState)
        probe("video_filter", videoState, JSONObject().put("effect", "黑白"))
        probe("video_speed", videoState, JSONObject().put("speed", 2.0))

        // Multi-input video and composition tools.
        val twoIds = assets.take(2).map { it.assetId }
        val threeIds = assets.take(3).map { it.assetId }
        probe(
            "video_transition", base.copy(sourceAssets = assets.take(2)),
            JSONObject().put("asset_ids", JSONArray(twoIds)),
            listOf(AgentInputRef.AssetSet(twoIds))
        )
        probe(
            "video_dynamic_sticker", base.copy(sourceAssets = assets.take(2)),
            JSONObject().put("asset_ids", JSONArray(twoIds)).put("effect", "dynamic_sticker"),
            listOf(AgentInputRef.AssetSet(twoIds))
        )
        probe(
            "video_vertical_stack", base,
            JSONObject().put("asset_ids", JSONArray(threeIds)),
            listOf(AgentInputRef.AssetSet(threeIds))
        )
        probe(
            "template_collage", base,
            JSONObject()
                .put("asset_ids", JSONArray(threeIds))
                .put("layout", "vertical")
                .put("canvas", "1080x1440"),
            listOf(AgentInputRef.AssetSet(threeIds))
        )

        // Image tools consume a plain IMAGE artifact made from the Live Photo cover.
        probe("brighten", imageState, JSONObject().put("brightness", 0.15))
        probe("sharpen", imageState, JSONObject().put("sharpen", 0.25))
        probe("denoise", imageState, JSONObject().put("denoise", 0.6))
        probe("color_tone", imageState, JSONObject().put("effect", "暖色"))
        probe("portrait_beauty", imageState, JSONObject().put("smooth_skin", 30))

        // Live Photo-specific tools.
        val oneId = listOf(assets[0].assetId)
        probe(
            "live_photo_filter_effect", liveState, JSONObject()
                .put("effect", "黑白").put("asset_ids", JSONArray(oneId)),
            listOf(AgentInputRef.AssetSet(oneId))
        )
        probe(
            "live_photo_motion_effect", liveState, JSONObject()
                .put("effect", "motion_light").put("asset_ids", JSONArray(oneId)),
            listOf(AgentInputRef.AssetSet(oneId))
        )
        probe(
            "set_display_frame", liveState, JSONObject()
                .put("cover_time_ms", 500).put("asset_ids", JSONArray(oneId)),
            listOf(AgentInputRef.AssetSet(oneId))
        )

        // Stateful layout and replacement tools.
        val layoutState = base.copy(
            selectedAssetIds = twoIds,
            layout = layout,
            layoutVersion = 1
        )
        probe(
            "layout_swap", layoutState,
            JSONObject().put("asset_ids", JSONArray(twoIds)),
            listOf(AgentInputRef.AssetSet(twoIds), AgentInputRef.Layout(1))
        )
        val replaceState = base.copy(selectedAssetIds = oneId)
        probe(
            "asset_replace", replaceState,
            JSONObject().put("asset_ids", JSONArray(oneId)).put("query", "狗"),
            listOf(AgentInputRef.AssetSet(oneId))
        )

        // Both the legacy alias and runtime-v2 publisher are accepted. Published rows are removed
        // after validation so the acceptance run does not pollute the user's gallery.
        probe(
            "export_mp4", videoState,
            JSONObject().put("output_kind", "video").put("output_name", "accept-export.mp4")
        )
        probe(
            "publish_result", imageState,
            JSONObject().put("output_name", "accept-image.jpg")
        ) { execution, reduced ->
            validateExecution(execution, reduced)
            val uri = Uri.parse(execution.result.payload.getString("output_uri"))
            val marker = context.contentResolver.query(
                uri, arrayOf("live_photo"), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            assertTrue("plain IMAGE publish was falsely marked Live Photo", marker.isNullOrBlank())
        }

        val registered = MobileToolRegistry.allSpecs().map { it.id }.toSet()
        val missing = registered - attempted
        if (missing.isNotEmpty()) failures += "未执行工具: ${missing.sorted()}"

        val report = JSONObject()
            .put("device", android.os.Build.MODEL)
            .put("registered_count", registered.size)
            .put("attempted_count", attempted.size)
            .put("passed_count", attempted.size - failures.size)
            .put("failed_count", failures.size)
            .put("failures", JSONArray(failures))
            .put("results", results)
        reportFile.writeText(report.toString(2))
        Log.i(TAG, "REPORT ${report.toString()}")

        published.distinct().forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        root.deleteRecursively()
        assertTrue("tool acceptance failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun executeFitThenReverseInOneBoundedChain() {
        val record = LibraryDatabase(context).list().first { it.mediaFormat == "livephoto" }
        val asset = LivePhotoPreprocessor(context).prepare(
            listOf(Uri.parse(record.sourceUri)), cancelled = { false }
        ).single()
        val sourceVideo = asset.videoFile ?: error("fixture has no video")
        val root = File(context.cacheDir, "runtime-fit-reverse-acceptance").apply {
            deleteRecursively(); mkdirs()
        }
        val fixture = AgentArtifact(
            "fixture-video", AgentArtifactKind.VIDEO, "fixture-producer",
            file = sourceVideo, videoFile = sourceVideo, validated = true
        )
        var state = artifactState(listOf(asset), fixture)
        val executor = MobileToolExecutor(
            context, "适配画布后倒放", null, root, AtomicBoolean(false),
            { message -> Log.i(TAG, "fit-reverse $message") }
        )

        val fit = RuntimeStep(
            "fit-step", ToolFamily.COMPOSE, "video_fit_band",
            inputs = listOf(AgentInputRef.Artifact(fixture.artifactId))
        )
        state = state.copy(plan = state.plan!!.copy(steps = state.plan!!.steps + fit))
        val fitResult = executor.execute(fit, state)
        state = AgentStateReducer.apply(state, fitResult.delta)
        state = AgentStateReducer.apply(state, StateDelta.MarkStepCompleted(fit.stepId))
        validateExecution(fitResult, state)

        val fitted = state.latestValidatedArtifact() ?: error("fit produced no artifact")
        val reverse = RuntimeStep(
            "reverse-step", ToolFamily.TEMPORAL, "video_reverse",
            inputs = listOf(AgentInputRef.Artifact(fitted.artifactId))
        )
        state = state.copy(plan = state.plan!!.copy(steps = state.plan!!.steps + reverse))
        val reverseResult = executor.execute(reverse, state)
        state = AgentStateReducer.apply(state, reverseResult.delta)
        state = AgentStateReducer.apply(state, StateDelta.MarkStepCompleted(reverse.stepId))
        validateExecution(reverseResult, state)
        root.deleteRecursively()
    }

    @Test
    fun bindEveryModelSelectedLoopTool() {
        val records = LibraryDatabase(context).list()
            .filter { it.mediaFormat == "livephoto" }
            .take(3)
        val assets = LivePhotoPreprocessor(context).prepare(
            records.map { Uri.parse(it.sourceUri) }, cancelled = { false }
        )
        assertTrue("binder fixtures unavailable", assets.size >= 3)
        val image = assets[0].imageFile ?: error("fixture image missing")
        val video = assets[0].videoFile ?: error("fixture video missing")
        val imageState = artifactState(assets, AgentArtifact(
            "bind-image", AgentArtifactKind.IMAGE, "fixture-producer",
            file = image, imageFile = image, validated = true
        ))
        val videoState = artifactState(assets, AgentArtifact(
            "bind-video", AgentArtifactKind.VIDEO, "fixture-producer",
            file = video, videoFile = video, validated = true
        ))
        val one = baseState(assets.take(1))
        val two = baseState(assets.take(2))
        val three = baseState(assets.take(3))
        val ids = assets.map { it.assetId }
        val layout = MobileTemplateParser.parse(
            MobileTemplateCatalog(context).chooseForCount("三拼", 3), assets
        )
        val swapState = three.copy(selectedAssetIds = ids.take(2), layout = layout, layoutVersion = 1)
        val replaceState = three.copy(selectedAssetIds = ids.take(1))

        data class BindingCase(
            val tool: String,
            val query: String,
            val instruction: String,
            val state: AgentRuntimeState
        )

        val cases = listOf(
            BindingCase("asset_summarize", "查看这段素材的内容摘要", "查看素材摘要", one),
            BindingCase("color_analysis", "提取这张素材的主色调色板", "提取主色调色板", one),
            BindingCase("live_xdr_process", "处理这张Live Photo的Live XDR资源", "处理Live XDR资源", one),
            BindingCase("clip_trim", "把视频裁剪到前1秒", "裁剪到前1秒", videoState),
            BindingCase("video_mirror", "把视频左右镜像翻转", "左右镜像翻转", videoState),
            BindingCase("video_timeline_effect", "给视频加时间线缩放效果", "时间线缩放效果", videoState),
            BindingCase("video_speed", "把视频加速两倍", "加速两倍", videoState),
            BindingCase("video_reverse", "把视频倒放", "倒放", videoState),
            BindingCase("set_display_frame", "把Live Photo中间一帧设成封面", "中间一帧设成封面", one),
            BindingCase("template_collage", "把三段素材做成三拼", "三拼", three),
            BindingCase("video_transition", "给两段视频添加转场", "两段视频转场", two),
            BindingCase("video_fit_band", "让视频适配画布并保留完整背景", "适配画布保留背景", one),
            BindingCase("video_crop_band", "裁剪视频填满画布", "裁剪填满画布", one),
            BindingCase("video_vertical_stack", "把三段视频上下竖向堆叠", "三段视频上下竖排", three),
            BindingCase("video_blur", "把视频背景虚化", "背景虚化", videoState),
            BindingCase("video_motion_effect", "给视频添加动感光效", "动感光效", videoState),
            BindingCase("video_dynamic_sticker", "给两段视频叠加动态贴纸", "动态贴纸", two),
            BindingCase("video_border_watermark", "给视频加边框和LIVE水印", "边框和LIVE水印", videoState),
            BindingCase("video_filter", "把视频调成黑白", "黑白", videoState),
            BindingCase("brighten", "把这张图片提亮", "提亮", imageState),
            BindingCase("sharpen", "把这张图片锐化", "锐化", imageState),
            BindingCase("denoise", "给这张图片降噪", "降噪", imageState),
            BindingCase("color_tone", "把这张图片调成暖色", "暖色", imageState),
            BindingCase("portrait_beauty", "给这张人像图片美颜", "人像美颜", imageState),
            BindingCase("live_photo_filter_effect", "给这张Live Photo加复古滤镜", "复古滤镜", one),
            BindingCase("live_photo_motion_effect", "给这张Live Photo加动感特效", "动感特效", one),
            BindingCase("layout_swap", "交换当前选中的两个拼贴位置", "交换两个位置", swapState),
            BindingCase("asset_replace", "把当前选中的素材替换成狗", "替换成狗", replaceState)
        )

        val reportRows = JSONArray()
        val failures = mutableListOf<String>()
        cases.forEachIndexed { index, case ->
            val spec = MobileToolRegistry.spec(case.tool) ?: error("missing spec ${case.tool}")
            val catalog = MobileToolRegistry.loopCatalog(case.state.toolContext(spec.family))
            val routeStep = SemanticRouteStep(
                "bind-$index", spec.family, case.instruction
            )
            val planner = GenieTextPlanner(context, null)
            val started = System.currentTimeMillis()
            var result = planner.tryBindStep(case.query, routeStep, case.state, catalog)
            if (result == null) {
                result = planner.tryBindStep(
                    case.query, routeStep, case.state, catalog,
                    "上一输出无法解析，只输出一行 STEP=目录中的工具ID|效果"
                )
            }
            val actual = result?.call?.tool
            val passed = actual == case.tool
            if (!passed) failures += "${case.tool}: selected=$actual raw=${planner.lastTrace?.rawOutput}"
            reportRows.put(JSONObject()
                .put("expected_tool", case.tool)
                .put("selected_tool", actual)
                .put("success", passed)
                .put("elapsed_ms", System.currentTimeMillis() - started)
                .put("catalog", JSONArray(catalog.available.map { it.id }))
                .put("raw_output", planner.lastTrace?.rawOutput))
        }

        // RETRIEVE and PUBLISH each have one implementation and are deterministically bound;
        // export_mp4 is a tested legacy alias deliberately hidden from the loop catalogue.
        val auto = mapOf(
            ToolFamily.RETRIEVE to "search_assets",
            ToolFamily.PUBLISH to "publish_result"
        )
        auto.forEach { (family, expected) ->
            val state = if (family == ToolFamily.RETRIEVE) emptyState() else videoState
            val catalog = MobileToolRegistry.loopCatalog(state.toolContext(family))
            val actual = catalog.available.singleOrNull()?.id
            if (actual != expected) failures += "$expected auto-bind selected=$actual"
            reportRows.put(JSONObject()
                .put("expected_tool", expected)
                .put("selected_tool", actual)
                .put("success", actual == expected)
                .put("auto_bound", true)
                .put("catalog", JSONArray(catalog.available.map { it.id })))
        }

        val report = JSONObject()
            .put("case_count", cases.size + auto.size)
            .put("passed_count", cases.size + auto.size - failures.size)
            .put("failed_count", failures.size)
            .put("failures", JSONArray(failures))
            .put("results", reportRows)
        File(context.filesDir, "runtime-tool-binding-acceptance.json")
            .writeText(report.toString(2))
        Log.i(TAG, "BIND_REPORT ${report.toString()}")
        assertTrue("tool binding failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun repairRetrievalContentWithOnDeviceBrain() {
        val planner = GenieTextPlanner(context, null)
        val result = planner.tryRetrieveSpec("给我的猫做个三拼", 3, null)
            ?: error("retrieval repair did not parse: ${planner.lastTrace?.rawOutput}")
        val trace = planner.lastTrace
        val probe = JSONObject()
            .put("query", result.spec.query)
            .put("count", result.spec.requestedCount)
            .put("raw_output", trace?.rawOutput)
            .put("reasoning_output", trace?.reasoningOutput)
            .put("elapsed_ms", trace?.elapsedMs ?: 0)
            .put("strategy", trace?.strategy)
            .put("parsed", trace?.parsed ?: false)
        File(context.filesDir, "thinking-retrieval-probe.json").writeText(probe.toString(2))
        Log.i(TAG, "THINKING_PROBE ${probe.toString()}")
        assertTrue(
            "repaired query is not grounded: ${result.spec.query}",
            RetrievalQueryGuard.isUsable("给我的猫做个三拼", result.spec.query)
        )
        assertTrue("repaired query lost cat semantics: ${result.spec.query}", "猫" in result.spec.query)
        assertTrue("explicit count was not preserved", result.spec.requestedCount == 3)
    }

    @Test
    fun auditSemanticIndexTopResults() {
        data class AuditCase(
            val query: String,
            val evidence: List<String>
        )
        val cases = listOf(
            AuditCase("猫", listOf("猫", "cat", "kitten")),
            AuditCase("狗", listOf("狗", "dog", "puppy")),
            AuditCase("海边", listOf("海", "海岸", "海滩", "海浪", "ocean", "beach")),
            AuditCase("美食", listOf("食", "烹饪", "甜点", "咖啡", "food", "cooking", "dessert"))
        )
        val rows = JSONArray()
        val failures = mutableListOf<String>()
        MobileAssetSearch(context).use { search ->
            cases.forEach { case ->
                val outcome = search.search(AssetSearchRequest(case.query, 3))
                val selected = outcome.selected
                val invalid = selected.filterNot { record ->
                    val haystack = (record.displayName + " " + record.semantic.toString()).lowercase()
                    case.evidence.any { evidence -> evidence.lowercase() in haystack }
                }
                if (selected.size != 3 || invalid.isNotEmpty()) {
                    failures += "${case.query}: ${selected.map { it.displayName }} invalid=${invalid.map { it.displayName }}"
                }
                rows.put(JSONObject()
                    .put("query", case.query)
                    .put("selected", JSONArray(selected.map { record ->
                        JSONObject()
                            .put("asset_id", record.assetId)
                            .put("display_name", record.displayName)
                            .put("summary", record.semantic.optString("summary"))
                    })))
            }
        }
        val report = JSONObject()
            .put("case_count", cases.size)
            .put("passed_count", cases.size - failures.size)
            .put("failed_count", failures.size)
            .put("failures", JSONArray(failures))
            .put("results", rows)
        File(context.filesDir, "semantic-index-acceptance.json").writeText(report.toString(2))
        assertTrue("semantic index failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    @Ignore("manual cross-model experiment; some candidate arms are expected to fail")
    fun evaluateRouteReasoningProbe() {
        data class RouteCase(
            val id: String,
            val query: String,
            val expectedConversation: Boolean = false,
            val must: Set<ToolFamily> = emptySet(),
            val forbid: Set<ToolFamily> = emptySet(),
            val retrievalSubject: String? = null
        )
        val cases = listOf(
            RouteCase("P01", "你好", expectedConversation = true),
            RouteCase("P02", "请介绍一下你的能力", expectedConversation = true),
            RouteCase("P03", "给我的猫做个三拼", must = setOf(ToolFamily.RETRIEVE, ToolFamily.COMPOSE, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE, ToolFamily.TEMPORAL), retrievalSubject = "猫"),
            RouteCase("P04", "用海边的照片做个拼图", must = setOf(ToolFamily.RETRIEVE, ToolFamily.COMPOSE, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE, ToolFamily.TEMPORAL), retrievalSubject = "海边"),
            RouteCase("P05", "找一段视频调成黑白后导出", must = setOf(ToolFamily.RETRIEVE, ToolFamily.STYLE, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.COMPOSE, ToolFamily.REPLACE)),
            RouteCase("P06", "找一段猫的视频并倒放后导出", must = setOf(ToolFamily.RETRIEVE, ToolFamily.TEMPORAL, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE, ToolFamily.COMPOSE), retrievalSubject = "猫"),
            RouteCase("P07", "找海边视频加速两倍再导出", must = setOf(ToolFamily.RETRIEVE, ToolFamily.TEMPORAL, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE, ToolFamily.COMPOSE), retrievalSubject = "海边"),
            RouteCase("P08", "给猫做三拼，不要任何滤镜和贴纸", must = setOf(ToolFamily.RETRIEVE, ToolFamily.COMPOSE, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE, ToolFamily.TEMPORAL), retrievalSubject = "猫"),
            RouteCase("P09", "给猫做三拼，再把整体调成黑白并导出", must = setOf(ToolFamily.RETRIEVE, ToolFamily.COMPOSE, ToolFamily.STYLE, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.TEMPORAL), retrievalSubject = "猫"),
            RouteCase("P10", "找三段海边视频上下竖排后导出", must = setOf(ToolFamily.RETRIEVE, ToolFamily.COMPOSE, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE, ToolFamily.TEMPORAL), retrievalSubject = "海边"),
            RouteCase("P11", "make a three-photo collage of my cat", must = setOf(ToolFamily.RETRIEVE, ToolFamily.COMPOSE, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE, ToolFamily.TEMPORAL), retrievalSubject = "cat"),
            RouteCase("P12", "找三张猫做三拼再倒放，别加滤镜", must = setOf(ToolFamily.RETRIEVE, ToolFamily.COMPOSE, ToolFamily.TEMPORAL, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE), retrievalSubject = "猫")
        )
        val state = emptyState()
        val rows = JSONArray()
        val failures = mutableListOf<String>()
        cases.forEach { case ->
            val planner = GenieTextPlanner(context, null)
            val started = System.currentTimeMillis()
            var result = planner.tryRoute(case.query, state)
            if (result == null) {
                result = planner.tryRoute(
                    case.query,
                    state,
                    "只输出完整 ROUTE 行；编辑请求不能同时输出 REPLY/QUESTION，也不能漏掉实际编辑能力"
                )
            }
            val route = result?.route
            val families = route?.steps?.map { it.family }?.toSet().orEmpty()
            val retrieval = route?.steps?.firstOrNull { it.family == ToolFamily.RETRIEVE }
                ?.instruction.orEmpty()
            val conversationOk = if (case.expectedConversation) {
                route != null && route.steps.isEmpty() &&
                    (!route.reply.isNullOrBlank() || !route.clarification.isNullOrBlank())
            } else route != null && route.steps.isNotEmpty()
            val familyOk = case.must.all { it in families } && case.forbid.none { it in families }
            val retrievalOk = case.retrievalSubject?.let { subject ->
                RetrievalQueryGuard.canonicalize(case.query, retrieval)
                    ?.contains(subject, ignoreCase = true) == true
            } ?: true
            val passed = conversationOk && familyOk && retrievalOk
            if (!passed) failures += "${case.id}: families=$families retrieval=$retrieval raw=${planner.lastTrace?.rawOutput}"
            val trace = planner.lastTrace
            rows.put(JSONObject()
                .put("case_id", case.id)
                .put("query", case.query)
                .put("success", passed)
                .put("families", JSONArray(families.map { it.name }))
                .put("retrieval", retrieval)
                .put("elapsed_ms", System.currentTimeMillis() - started)
                .put("reasoning_chars", trace?.reasoningOutput?.length ?: 0)
                .put("reasoning", trace?.reasoningOutput)
                .put("final_output", trace?.rawOutput)
                .put("strategy", trace?.strategy))
        }
        val report = JSONObject()
            .put("case_count", cases.size)
            .put("passed_count", cases.size - failures.size)
            .put("failed_count", failures.size)
            .put("failures", JSONArray(failures))
            .put("results", rows)
        File(context.filesDir, "route-reasoning-acceptance.json").writeText(report.toString(2))
        assertTrue("route reasoning failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    @Ignore("manual loop experiment; do not include in routine connectedAndroidTest")
    fun evaluateBoundedLoopPlannerProbe() {
        check(BuildConfig.PLANNER_EVAL_ONLY) { "planner probe requires plannerEvalOnly=true" }
        data class LoopCase(
            val id: String,
            val query: String,
            val conversation: Boolean = false,
            val expectedTool: String? = null,
            val must: Set<ToolFamily> = emptySet(),
            val forbid: Set<ToolFamily> = emptySet(),
            val retrievalSubject: String? = null
        )
        val cases = listOf(
            LoopCase("L01", "你好", conversation = true),
            LoopCase("L02", "请介绍一下你的能力", conversation = true),
            LoopCase("L03", "给我的猫做个三拼", expectedTool = "template_collage", must = setOf(ToolFamily.RETRIEVE, ToolFamily.COMPOSE, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE, ToolFamily.TEMPORAL), retrievalSubject = "猫"),
            LoopCase("L04", "用海边的照片做个拼图", expectedTool = "template_collage", must = setOf(ToolFamily.RETRIEVE, ToolFamily.COMPOSE, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE, ToolFamily.TEMPORAL), retrievalSubject = "海边"),
            LoopCase("L05", "找一段视频调成黑白后导出", expectedTool = "video_filter", must = setOf(ToolFamily.RETRIEVE, ToolFamily.STYLE, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.COMPOSE, ToolFamily.REPLACE)),
            LoopCase("L06", "找一段猫的视频并倒放后导出", expectedTool = "video_reverse", must = setOf(ToolFamily.RETRIEVE, ToolFamily.TEMPORAL, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE, ToolFamily.COMPOSE), retrievalSubject = "猫"),
            LoopCase("L07", "找海边视频加速两倍再导出", expectedTool = "video_speed", must = setOf(ToolFamily.RETRIEVE, ToolFamily.TEMPORAL, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE, ToolFamily.COMPOSE), retrievalSubject = "海边"),
            LoopCase("L08", "给猫做三拼，不要任何滤镜和贴纸", expectedTool = "template_collage", must = setOf(ToolFamily.RETRIEVE, ToolFamily.COMPOSE, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE, ToolFamily.TEMPORAL), retrievalSubject = "猫"),
            LoopCase("L09", "给猫做三拼，再把整体调成黑白并导出", expectedTool = "template_collage", must = setOf(ToolFamily.RETRIEVE, ToolFamily.COMPOSE, ToolFamily.STYLE, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.TEMPORAL), retrievalSubject = "猫"),
            LoopCase("L10", "找三段海边视频上下竖排后导出", expectedTool = "video_vertical_stack", must = setOf(ToolFamily.RETRIEVE, ToolFamily.COMPOSE, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE, ToolFamily.TEMPORAL), retrievalSubject = "海边"),
            LoopCase("L11", "make a three-photo collage of my cat", expectedTool = "template_collage", must = setOf(ToolFamily.RETRIEVE, ToolFamily.COMPOSE, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE, ToolFamily.TEMPORAL), retrievalSubject = "cat"),
            LoopCase("L12", "找三张猫做三拼再倒放，别加滤镜", expectedTool = "template_collage", must = setOf(ToolFamily.RETRIEVE, ToolFamily.COMPOSE, ToolFamily.TEMPORAL, ToolFamily.PUBLISH), forbid = setOf(ToolFamily.STYLE), retrievalSubject = "猫")
        )
        val rows = JSONArray()
        val failures = mutableListOf<String>()
        cases.forEach { case ->
            var finalPlan: MobilePlan? = null
            val routes = mutableListOf<SemanticRoute>()
            val results = mutableListOf<MobileToolResult>()
            val traces = mutableListOf<Pair<String, BrainTrace>>()
            var error: String? = null
            var finishMessage = ""
            val started = System.currentTimeMillis()
            MobileAgentLoop(context).run(
                query = case.query,
                uris = emptyList(),
                layoutContext = null,
                cancelled = AtomicBoolean(false),
                listener = object : MobileAgentRunner.Listener {
                    override fun onProgress(message: String) = Unit
                    override fun onRuntimeRoute(route: SemanticRoute, trace: BrainTrace) {
                        routes += route
                    }
                    override fun onRuntimeBrainTrace(stage: String, trace: BrainTrace) {
                        traces += stage to trace
                    }
                    override fun onPlan(plan: MobilePlan) { finalPlan = plan }
                    override fun onResult(result: MobileToolResult) { results += result }
                    override fun onFinished(uri: Uri?, message: String) { finishMessage = message }
                    override fun onError(message: String) { error = message }
                }
            )
            val route = routes.firstOrNull { it.steps.isNotEmpty() } ?: routes.firstOrNull()
            val families = route?.steps?.map { it.family }?.toSet().orEmpty()
            val searchPayload = results.lastOrNull { it.tool == "search_assets" }?.payload
            val retrieval = searchPayload?.optString("query").orEmpty()
            val tools = finalPlan?.toolCalls?.map { it.tool }.orEmpty()
            val conversationOk = if (case.conversation) {
                finalPlan?.toolCalls?.isEmpty() == true && error == null
            } else error == null && case.expectedTool in tools
            val familyOk = case.must.all { it in families } && case.forbid.none { it in families }
            val retrievalOk = case.retrievalSubject?.let {
                RetrievalQueryGuard.canonicalize(case.query, retrieval)?.contains(it, true) == true
            } ?: true
            val passed = conversationOk && familyOk && retrievalOk
            if (!passed) failures += "${case.id}: error=$error tools=$tools families=$families retrieval=$retrieval"
            val reasoningChars = traces.sumOf { it.second.reasoningOutput.length }
            rows.put(JSONObject()
                .put("case_id", case.id)
                .put("query", case.query)
                .put("success", passed)
                .put("error", error)
                .put("finish_message", finishMessage)
                .put("tools", JSONArray(tools))
                .put("families", JSONArray(families.map { it.name }))
                .put("retrieval", retrieval)
                .put("elapsed_ms", System.currentTimeMillis() - started)
                .put("model_elapsed_ms", traces.sumOf { it.second.elapsedMs })
                .put("reasoning_chars", reasoningChars)
                .put("model_calls", traces.size)
                .put("traces", JSONArray(traces.map { (stage, trace) ->
                    JSONObject()
                        .put("stage", stage)
                        .put("parsed", trace.parsed)
                        .put("elapsed_ms", trace.elapsedMs)
                        .put("reasoning_chars", trace.reasoningOutput.length)
                        .put("reasoning", trace.reasoningOutput)
                        .put("final_output", trace.rawOutput)
                })))
        }
        val report = JSONObject()
            .put("case_count", cases.size)
            .put("passed_count", cases.size - failures.size)
            .put("failed_count", failures.size)
            .put("failures", JSONArray(failures))
            .put("results", rows)
        File(context.filesDir, "bounded-loop-planner-acceptance.json").writeText(report.toString(2))
        assertTrue("bounded loop planner failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun emptyState(): AgentRuntimeState = AgentRuntimeState(
        sessionId = null,
        turnId = "tool-acceptance",
        originalQuery = "tool acceptance"
    )

    private fun baseState(assets: List<PreparedAsset>): AgentRuntimeState = emptyState().copy(
        sourceAssets = assets,
        plan = emptyPlan()
    )

    private fun artifactState(
        assets: List<PreparedAsset>,
        artifact: AgentArtifact
    ): AgentRuntimeState {
        val producer = RuntimeStep(
            stepId = artifact.producerStepId,
            family = ToolFamily.ANALYZE,
            tool = "asset_summarize",
            status = RuntimeStepStatus.COMPLETED
        )
        return baseState(assets).copy(
            artifacts = mapOf(artifact.artifactId to artifact),
            plan = emptyPlan().copy(steps = listOf(producer))
        )
    }

    private fun emptyPlan(): RuntimePlan = RuntimePlan(
        planId = "acceptance-plan",
        version = 1,
        route = SemanticRoute("tool acceptance", emptyList())
    )

    private fun defaultInputs(state: AgentRuntimeState): List<AgentInputRef> {
        state.latestValidatedArtifact()?.let { return listOf(AgentInputRef.Artifact(it.artifactId)) }
        return listOf(AgentInputRef.AssetSet(state.scopedAssets().map { it.assetId }))
    }

    private fun validateExecution(execution: ToolExecutionResult, state: AgentRuntimeState) {
        assertTrue("tool result is unsuccessful", execution.result.success)
        fun inspect(delta: StateDelta) {
            when (delta) {
                is StateDelta.AddArtifact -> {
                    assertTrue("artifact is not validated", delta.artifact.validated)
                    val files = listOfNotNull(
                        delta.artifact.file, delta.artifact.imageFile, delta.artifact.videoFile
                    ).distinct()
                    if (delta.artifact.kind != AgentArtifactKind.JSON || files.isNotEmpty()) {
                        assertTrue("artifact has no output file", files.isNotEmpty())
                        files.forEach { file -> assertTrue("missing/empty artifact ${file.path}", file.isFile && file.length() > 0) }
                    }
                }
                is StateDelta.AddAssets -> assertTrue("search returned no prepared assets", delta.assets.isNotEmpty())
                is StateDelta.ReplaceAsset -> assertTrue(
                    "replacement not reduced", state.sourceAssets.any { it.assetId == delta.asset.assetId }
                )
                is StateDelta.UpdateLayout -> assertTrue(
                    "layout has no slots", (delta.layout.optJSONArray("items")?.length() ?: 0) > 0
                )
                is StateDelta.PublishArtifact -> {
                    context.contentResolver.openInputStream(delta.uri).use { input ->
                        assertTrue("published URI cannot be opened", input != null && input.read() >= 0)
                    }
                }
                is StateDelta.Batch -> delta.deltas.forEach(::inspect)
                else -> Unit
            }
        }
        inspect(execution.delta)
    }
}
