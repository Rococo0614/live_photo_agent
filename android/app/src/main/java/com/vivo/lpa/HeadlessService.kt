package com.vivo.lpa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.vivo.videoeditorsdk.editor.api.VMEditor
import com.vivo.videoeditorsdk.editor.api.VMVideoConverter
import com.vivo.videoeditorsdk.editor.api.entity.input.VMClip
import com.vivo.videoeditorsdk.editor.api.entity.input.VMResource
import com.vivo.videoeditorsdk.editor.api.entity.listener.VMExportListener
import com.vivo.videoeditorsdk.editor.api.entity.output.VMExportEncodeConfig
import com.vivo.videoeditorsdk.editor.api.view.VMEditorView
import com.vivo.videoeditorsdk.editor.api.entity.listener.VMConverterListener
import com.vivo.videoeditorsdk.editor.api.entity.output.VMConverterConfig
import com.vivo.videoeditorsdk.videoeditor.VideoConverter
import com.vivo.videoeditorsdk.videoeditor.VideoExportEngine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * 无头（headless）渲染验证。
 *
 * 关键点：本类是 Service，整个流程不创建任何 Activity、不创建任何 View、
 * 不持有 Window。如果导出能成功，就证明 vivo 视频编辑 SDK 存在一条
 * 可在后台服务里跑的通路 —— 这是 Agent 架构能否落地的前提。
 *
 * 用 `adb shell am start-service -n com.vivo.lpa/.HeadlessService` 触发。
 */
class HeadlessService : Service() {

    companion object {
        private const val TAG = "LPA_HEADLESS"
        /** SDK 转码等待上限。真机上 3s 视频通常几秒内完成，给足余量 */
        private const val TIMEOUT_SEC = 120L
        private const val EDGETAM_ENCODER_CONTEXT_SHA256 =
            "0edaed8a468ab003dff72fd57574b3ffc95bf2ac9fbb89fba94070b12811ddc0"
        private const val EDGETAM_ENCODER_IMAGE_SHA256 =
            "ee8ff74682e984efdc4bd60b190df8533efd36fdd6eeb93a2a6fbd3d64cff7a8"
        private const val EDGETAM_ENCODER_COORDINATES_SHA256 =
            "d7b6b3765bbae50df879cc75830861d01c6de4af5bf2f5be4a21ce9c35753abb"
        private const val EDGETAM_ENCODER_LABELS_SHA256 =
            "80b8fd6d60fa85fd14a38b5295cb92abd80dfec5ca406c9f969609a79d36809d"
        private const val QAIRT_245_V81_SKEL_SHA256 =
            "57330ed3f7846b94181b25b7a87bc3fe5dc71033766f2d5630520a1400477c97"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 8+ 禁止后台起普通 Service（adb 起也会被判 "app is in background"），
        // 因此必须立刻转前台。通知本身与验证无关，只为拿到前台豁免。
        promoteToForeground()
        // 不能占用主线程：SDK 回调依赖主线程 Looper 派发，阻塞会死锁
        val inputPath = intent?.getStringExtra("input_path")
        val inputPaths = intent?.getStringExtra("input_paths")
            ?.split("|", ",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOfNotNull(inputPath)
        val operation = intent?.getStringExtra("native_operation") ?: "clip_trim"
        val layout = intent?.getStringExtra("native_layout") ?: "vertical"
        val layoutContext = intent?.getStringExtra("layout_context")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val imageParams = intent?.getStringExtra("image_params")
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val photoTimestampUs = intent?.getLongExtra("photo_timestamp_us", 1_500_000L)
            ?: 1_500_000L
        val outputName = intent?.getStringExtra("output_name")
        val userAuthorizedPublish = intent?.getBooleanExtra("user_authorized_publish", false) == true
        val agentQuery = intent?.getStringExtra("agent_query")
        Thread({
            if (inputPaths.isEmpty()) runAll()
            else runNativeAdapter(
                inputPaths,
                operation,
                layout,
                layoutContext,
                imageParams,
                photoTimestampUs,
                outputName,
                userAuthorizedPublish,
                agentQuery
            )
        }, "lpa-headless").start()
        return START_NOT_STICKY
    }

    /** 前台通知只是为了让进程活到导出结束，不代表验证依赖 UI。 */
    private fun promoteToForeground() {
        val ch = "lpa_headless"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ch, "LPA Headless", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, ch)
            .setContentTitle("LPA 无头导出验证中")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        startForeground(1, n)
    }

    private fun log(s: String) = Log.i(TAG, s)

    private fun runAll() {
        log("=== HEADLESS RENDER TEST BEGIN ===")
        log("进程无 Activity / 无 View / 无 Window")

        val mp4 = runCatching { prepareInput() }.getOrElse {
            log("FAIL 输入准备失败: ${it.message}")
            log("=== HEADLESS RENDER TEST END ===")
            return
        }
        log("输入 MP4: ${mp4.absolutePath} (${mp4.length()} bytes)")

        runCatching { testConverter(mp4) }
            .onFailure { log("A 抛异常: ${it::class.java.simpleName}: ${it.message}") }
        runCatching { testExportEngine(mp4) }
            .onFailure { log("B 抛异常: ${it::class.java.simpleName}: ${it.message}") }
        runCatching { testEditor(mp4) }
            .onFailure { log("C 抛异常: ${it::class.java.simpleName}: ${it.message}") }

        log("=== HEADLESS RENDER TEST END ===")
    }

    /** Run the migrated PlanExecutor's SDK path against an explicit local MP4. */
    private fun runNativeAdapter(
        inputPaths: List<String>,
        operation: String,
        layout: String,
        layoutContext: JSONObject?,
        imageParams: JSONObject,
        photoTimestampUs: Long,
        outputName: String?,
        userAuthorizedPublish: Boolean,
        agentQuery: String?
    ) {
        val inputs = inputPaths.map(::File)
        val missing = inputs.firstOrNull { !it.isFile }
        if (missing != null) {
            log("FAIL 输入不存在: ${missing.absolutePath}")
            stopSelf()
            return
        }
        val outputDir = File(filesDir, "probe-native").apply { mkdirs() }
        log("=== NATIVE ADAPTER TEST BEGIN ===")
        log("操作: $operation / 布局: $layout")
        inputs.forEach { log("输入 MP4: ${it.absolutePath} (${it.length()} bytes)") }
        runCatching {
            if (operation == "dynamic_video_timeline_probe") {
                require(BuildConfig.DEBUG) { "dynamic timeline probe is debug-only" }
                require(inputs.size == 1) { "dynamic timeline probe requires one Live Photo" }
                val decoded = requireNotNull(
                    LivePhotoSdkAdapter(this).decode(
                        inputs.single(),
                        File(outputDir, "dynamic-timeline-decoded")
                    )
                ) { "dynamic timeline input is not a decodable Live Photo" }
                val timeline = AndroidVideoFrameTimelineDecoder.decode(
                    decoded.video,
                    AtomicBoolean(false)
                )
                val coverIndex = timeline.nearestFrameIndex(decoded.photoTimestampUs)
                val coverPtsUs = timeline.frames[coverIndex].presentationTimeUs
                val coverDeltaUs = kotlin.math.abs(coverPtsUs - decoded.photoTimestampUs)
                val medianIntervalUs = timeline.medianPositiveFrameIntervalUs()
                val allowedCoverDeltaUs = maxOf(100_000L, (medianIntervalUs ?: 0L) * 2L)
                require(coverDeltaUs <= allowedCoverDeltaUs) {
                    "photo timestamp is not represented by the decoded frame timeline"
                }
                val trackPlan = AndroidBidirectionalTrackPlan.from(
                    timeline,
                    decoded.photoTimestampUs
                )
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", "PASS_DYNAMIC_DECODED_FRAME_TIMELINE")
                    .put("source", inputs.single().absolutePath)
                    .put("mime_type", timeline.mimeType)
                    .put("width", timeline.width)
                    .put("height", timeline.height)
                    .put("rotation_degrees", timeline.rotationDegrees)
                    .put("container_duration_us", timeline.containerDurationUs)
                    .put("nominal_frame_rate", timeline.nominalFrameRate ?: JSONObject.NULL)
                    .put("decoded_frame_count", timeline.frameCount)
                    .put("expected_frame_count_source", "MediaCodec output until EOS")
                    .put("frame_count_assumed", false)
                    .put("first_presentation_time_us", timeline.firstPresentationTimeUs)
                    .put("last_presentation_time_us", timeline.lastPresentationTimeUs)
                    .put("median_positive_frame_interval_us", medianIntervalUs ?: JSONObject.NULL)
                    .put("presentation_timestamps_us", org.json.JSONArray(
                        timeline.frames.map { it.presentationTimeUs }
                    ))
                    .put("photo_timestamp_us", decoded.photoTimestampUs)
                    .put("photo_frame_index", coverIndex)
                    .put("photo_frame_presentation_time_us", coverPtsUs)
                    .put("photo_timestamp_delta_us", coverDeltaUs)
                    .put("forward_sequence_frame_count", trackPlan.forward.size)
                    .put("backward_sequence_frame_count", trackPlan.backward.size)
                    .put("conditioning_frame_reused_between_directions", true)
                    .put("media_modified", false)
                    .put("published", false)
                File(outputDir, "dynamic-video-timeline-evidence.json")
                    .writeText(evidence.toString(2) + "\n")
                log(
                    "PASS dynamic timeline: frames=${timeline.frameCount} " +
                        "pts=${timeline.firstPresentationTimeUs}..${timeline.lastPresentationTimeUs} " +
                        "cover_frame=$coverIndex"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "dynamic_video_frame_input_probe") {
                require(BuildConfig.DEBUG) { "dynamic frame input probe is debug-only" }
                require(inputs.size == 2) {
                    "dynamic frame input probe requires Live Photo and expected float input"
                }
                val decoded = requireNotNull(
                    LivePhotoSdkAdapter(this).decode(
                        inputs[0],
                        File(outputDir, "dynamic-frame-input-decoded")
                    )
                ) { "dynamic frame input is not a decodable Live Photo" }
                val timeline = AndroidVideoFrameTimelineDecoder.decode(
                    decoded.video,
                    AtomicBoolean(false)
                )
                val frameIndex = imageParams.optInt("frame_index", 0)
                require(frameIndex in 0 until timeline.frameCount)
                val frame = AndroidVideoFrameBatchReader(decoded.video, timeline).use { reader ->
                    reader.read(frameIndex, 1).single()
                }
                val actual = try {
                    AndroidEdgeTamQnnSegmenter.preprocessImage(frame.bitmap)
                } finally {
                    frame.bitmap.recycle()
                }
                val expected = AndroidOwlV2QnnLocator.readFloats(inputs[1], actual.size)
                var maximumAbsoluteError = 0.0
                var squaredError = 0.0
                for (index in actual.indices) {
                    val difference = actual[index].toDouble() - expected[index]
                    maximumAbsoluteError = maxOf(maximumAbsoluteError, kotlin.math.abs(difference))
                    squaredError += difference * difference
                }
                val mse = squaredError / actual.size
                val psnr = if (mse == 0.0) Double.POSITIVE_INFINITY
                    else 10.0 * kotlin.math.log10(1.0 / mse)
                val actualFile = File(outputDir, "dynamic-frame-input.raw")
                AndroidOwlV2QnnLocator.writeFloats(actualFile, actual)
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", "PASS_DYNAMIC_DECODED_FRAME_MODEL_INPUT")
                    .put("decoded_frame_count", timeline.frameCount)
                    .put("frame_index", frame.frameIndex)
                    .put("presentation_time_us", frame.presentationTimeUs)
                    .put("actual_sha256", fileSha256(actualFile))
                    .put("expected_sha256", fileSha256(inputs[1]))
                    .put("maximum_absolute_error", maximumAbsoluteError)
                    .put("mse", mse)
                    .put("psnr_db", psnr)
                    .put("minimum_psnr_db", 40.0)
                    .put("media_modified", false)
                File(outputDir, "dynamic-video-frame-input-evidence.json")
                    .writeText(evidence.toString(2) + "\n")
                require(psnr >= 40.0) { "Android decoded frame input PSNR is too low: $psnr" }
                log(
                    "PASS dynamic frame model input: frame=$frameIndex " +
                        "pts=${frame.presentationTimeUs} psnr=$psnr"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "qnn_dynamic_frame_mask_probe") {
                require(BuildConfig.DEBUG && BuildConfig.LOCAL_TRACKED_PREVIEW) {
                    "dynamic frame mask probe requires the opt-in debug build"
                }
                require(inputs.size == 4) {
                    "dynamic frame mask probe requires Live Photo, reference input, points and mask"
                }
                val decoded = requireNotNull(
                    LivePhotoSdkAdapter(this).decode(
                        inputs[0],
                        File(outputDir, "dynamic-frame-mask-decoded")
                    )
                ) { "dynamic frame mask input is not a decodable Live Photo" }
                val timeline = AndroidVideoFrameTimelineDecoder.decode(
                    decoded.video,
                    AtomicBoolean(false)
                )
                val frameIndex = imageParams.optInt("frame_index", 0)
                require(frameIndex in 0 until timeline.frameCount)
                val frame = AndroidVideoFrameBatchReader(decoded.video, timeline).use { reader ->
                    reader.read(frameIndex, 1).single()
                }
                val sourceWidth = frame.bitmap.width
                val sourceHeight = frame.bitmap.height
                val actualInput = try {
                    AndroidEdgeTamQnnSegmenter.preprocessImage(frame.bitmap)
                } finally {
                    frame.bitmap.recycle()
                }
                val inputFile = File(outputDir, "qnn-dynamic-frame-input.raw")
                AndroidOwlV2QnnLocator.writeFloats(inputFile, actualInput)
                val expectedInput = AndroidOwlV2QnnLocator.readFloats(inputs[1], actualInput.size)
                var inputSquaredError = 0.0
                for (index in actualInput.indices) {
                    val difference = actualInput[index].toDouble() - expectedInput[index]
                    inputSquaredError += difference * difference
                }
                val inputMse = inputSquaredError / actualInput.size
                val inputPsnr = if (inputMse == 0.0) Double.POSITIVE_INFINITY
                    else 10.0 * kotlin.math.log10(1.0 / inputMse)
                val temporalRunner = AndroidEdgeTamTemporalQnnRunner(this)
                val result = temporalRunner.processRawFrame(
                    sequenceIndex = 0,
                    imageInput = inputFile,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    initialPromptCoordinates = AndroidOwlV2QnnLocator.readFloats(inputs[2], 4),
                    state = temporalRunner.createState(),
                    cancelled = AtomicBoolean(false),
                    produceMemory = false,
                    progress = { message -> log(message) }
                )
                val expectedMask = BitmapFactory.decodeFile(inputs[3].absolutePath)
                    ?: error("expected dynamic frame mask is missing")
                require(expectedMask.width == sourceWidth && expectedMask.height == sourceHeight)
                val expectedPixels = IntArray(sourceWidth * sourceHeight)
                expectedMask.getPixels(
                    expectedPixels, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight
                )
                expectedMask.recycle()
                var intersection = 0
                var union = 0
                for (index in expectedPixels.indices) {
                    val actual = (result.maskValues[index].toInt() and 0xff) > 0
                    val expected = (expectedPixels[index] ushr 16 and 0xff) > 0
                    if (actual && expected) intersection += 1
                    if (actual || expected) union += 1
                }
                val maskIou = if (union == 0) 1.0 else intersection.toDouble() / union
                require(inputPsnr >= 40.0 && maskIou >= 0.95) {
                    "dynamic decoded frame failed downstream mask gate: psnr=$inputPsnr iou=$maskIou"
                }
                val maskFile = File(outputDir, "qnn-dynamic-frame-mask.raw").apply {
                    writeBytes(result.maskValues)
                }
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", "PASS_DYNAMIC_DECODE_TO_EDGETAM_MASK")
                    .put("decoded_frame_count", timeline.frameCount)
                    .put("frame_index", frame.frameIndex)
                    .put("presentation_time_us", frame.presentationTimeUs)
                    .put("input_psnr_db", inputPsnr)
                    .put("minimum_input_psnr_db", 40.0)
                    .put("mask_iou_vs_prior_vivo", maskIou)
                    .put("minimum_mask_iou", 0.95)
                    .put("mask_sha256", fileSha256(maskFile))
                    .put("model_object_present", result.modelObjectPresent)
                    .put("qairt_exact", AndroidQnnContextProcessRunner.QAIRT_EXACT)
                    .put("media_modified", false)
                File(outputDir, "qnn-dynamic-frame-mask-evidence.json")
                    .writeText(evidence.toString(2) + "\n")
                log(
                    "PASS dynamic decode -> EdgeTAM mask: frame=$frameIndex " +
                        "input_psnr=$inputPsnr mask_iou=$maskIou"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "qnn_dynamic_temporal_prefix_probe") {
                require(BuildConfig.DEBUG && BuildConfig.LOCAL_TRACKED_PREVIEW) {
                    "dynamic temporal prefix probe requires the opt-in debug build"
                }
                require(inputs.size == 2) {
                    "dynamic temporal prefix probe requires Live Photo and initial points"
                }
                val decoded = requireNotNull(
                    LivePhotoSdkAdapter(this).decode(
                        inputs[0],
                        File(outputDir, "dynamic-temporal-prefix-decoded")
                    )
                ) { "dynamic temporal prefix input is not a decodable Live Photo" }
                val fullTimeline = AndroidVideoFrameTimelineDecoder.decode(
                    decoded.video,
                    AtomicBoolean(false)
                )
                val requestedFrames = imageParams.optInt("frame_count", 3)
                    .coerceIn(2, fullTimeline.frameCount)
                val prefixTimeline = AndroidDecodedVideoTimeline(
                    fullTimeline.mimeType,
                    fullTimeline.width,
                    fullTimeline.height,
                    fullTimeline.rotationDegrees,
                    fullTimeline.frames[requestedFrames - 1].presentationTimeUs,
                    fullTimeline.nominalFrameRate,
                    fullTimeline.frames.take(requestedFrames)
                )
                val anchorFrameIndex = imageParams.optInt("anchor_frame_index", 0)
                    .coerceIn(0, prefixTimeline.frameCount - 1)
                val trackDirectory = File(outputDir, "dynamic-temporal-prefix-track").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val temporalRunner = AndroidEdgeTamTemporalQnnRunner(this)
                val tracked = AndroidTimelineEdgeTamInputMaterializer(
                    decoded.video,
                    prefixTimeline
                ).use { materializer ->
                    AndroidEdgeTamBidirectionalTracker(temporalRunner).track(
                        timeline = prefixTimeline,
                        photoTimestampUs = prefixTimeline.frames[anchorFrameIndex].presentationTimeUs,
                        sourceWidth = prefixTimeline.width,
                        sourceHeight = prefixTimeline.height,
                        initialPromptCoordinates = AndroidOwlV2QnnLocator.readFloats(inputs[1], 4),
                        outputDirectory = trackDirectory,
                        cancelled = AtomicBoolean(false),
                        materializer = materializer,
                        progress = { message -> log(message) }
                    )
                }
                var previousVisiblePixels: Int? = null
                val rows = org.json.JSONArray()
                tracked.frames.forEach { frame ->
                    val observation = TrackObservationValidator.validate(
                        TrackObservationInput(
                            frame.frameIndex,
                            frame.presentationTimeUs / 1_000L,
                            frame.modelObjectPresent,
                            frame.rawMaskPixels,
                            candidateCount = 1
                        ),
                        previousVisiblePixels,
                        TrackValidationPolicy("dynamic-prefix-qnn245-v1", 0.10)
                    )
                    require(observation.temporallyCovered) {
                        "dynamic temporal coverage failed at frame ${frame.frameIndex}"
                    }
                    if (observation.status == TrackObservationStatus.VISIBLE_VALIDATED) {
                        previousVisiblePixels = observation.rawMaskPixels
                    }
                    rows.put(JSONObject()
                        .put("frame_index", frame.frameIndex)
                        .put("presentation_time_us", frame.presentationTimeUs)
                        .put("status", observation.status.name)
                        .put("raw_mask_pixels", frame.rawMaskPixels)
                        .put("mask_sha256", fileSha256(frame.maskFile))
                        .put("evidence_fingerprint", frame.evidenceFingerprint))
                }
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", "PASS_DYNAMIC_DECODED_TEMPORAL_QNN_PREFIX")
                    .put("source_decoded_frame_count", fullTimeline.frameCount)
                    .put("processed_frame_count", tracked.frames.size)
                    .put("expected_frame_count", prefixTimeline.frameCount)
                    .put("expected_frame_count_source", "decoded prefix timeline")
                    .put("anchor_frame_index", tracked.anchorFrameIndex)
                    .put("all_frames_temporally_covered", true)
                    .put("frames", rows)
                    .put("qairt_exact", AndroidQnnContextProcessRunner.QAIRT_EXACT)
                    .put("media_modified", false)
                File(outputDir, "qnn-dynamic-temporal-prefix-evidence.json")
                    .writeText(evidence.toString(2) + "\n")
                log(
                    "PASS dynamic temporal QNN prefix: ${tracked.frames.size}/" +
                        "${prefixTimeline.frameCount} frames covered"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "persistent_tint_timeline_probe") {
                require(BuildConfig.DEBUG) { "persistent tint timeline probe is debug-only" }
                require(inputs.size >= 3) {
                    "persistent tint timeline probe requires Live Photo and at least two masks"
                }
                val decoded = requireNotNull(
                    LivePhotoSdkAdapter(this).decode(
                        inputs[0],
                        File(outputDir, "persistent-tint-decoded")
                    )
                ) { "persistent tint input is not a decodable Live Photo" }
                val timeline = AndroidVideoFrameTimelineDecoder.decode(
                    decoded.video,
                    AtomicBoolean(false)
                )
                val masks = inputs.drop(1)
                require(masks.size == timeline.frameCount) {
                    "mask count ${masks.size} does not match decoded frame count ${timeline.frameCount}"
                }
                val visualWidth = if (timeline.rotationDegrees in setOf(90, 270)) timeline.height
                    else timeline.width
                val visualHeight = if (timeline.rotationDegrees in setOf(90, 270)) timeline.width
                    else timeline.height
                val change = LocalDeterministicChange.ColorTint("#008000", 1.0)
                var totalOutsideChanged = 0
                var totalInsideChanged = 0
                var maximumChannelError = 0
                val rows = org.json.JSONArray()
                val startedAt = android.os.SystemClock.elapsedRealtime()
                AndroidVideoFrameBatchReader(decoded.video, timeline).use { reader ->
                    AndroidPersistentTintRenderer(
                        visualWidth,
                        visualHeight,
                        change.colorHex,
                        change.intensity
                    ).use { renderer ->
                        timeline.frames.forEach { time ->
                            val source = reader.read(time.frameIndex, 1).single().bitmap
                            val mask = masks[time.frameIndex].readBytes()
                            require(mask.size == visualWidth * visualHeight)
                            val rendered = renderer.render(source, mask)
                            val sourcePixels = IntArray(mask.size)
                            val renderedPixels = IntArray(mask.size)
                            source.getPixels(
                                sourcePixels, 0, visualWidth, 0, 0, visualWidth, visualHeight
                            )
                            rendered.getPixels(
                                renderedPixels, 0, visualWidth, 0, 0, visualWidth, visualHeight
                            )
                            val oracle = LocalMaskChangeReferenceRenderer.renderArgb(
                                visualWidth,
                                visualHeight,
                                sourcePixels,
                                mask,
                                change
                            )
                            var outsideChanged = 0
                            var insideChanged = 0
                            var frameMaximumError = 0
                            for (index in sourcePixels.indices) {
                                if ((mask[index].toInt() and 0xff) == 0) {
                                    if (renderedPixels[index] != sourcePixels[index]) outsideChanged += 1
                                } else if (renderedPixels[index] != sourcePixels[index]) {
                                    insideChanged += 1
                                }
                                val renderedPixel = renderedPixels[index]
                                val oraclePixel = oracle[index]
                                frameMaximumError = maxOf(
                                    frameMaximumError,
                                    kotlin.math.abs((renderedPixel and 0xff) - (oraclePixel and 0xff)),
                                    kotlin.math.abs(
                                        (renderedPixel ushr 8 and 0xff) -
                                            (oraclePixel ushr 8 and 0xff)
                                    ),
                                    kotlin.math.abs(
                                        (renderedPixel ushr 16 and 0xff) -
                                            (oraclePixel ushr 16 and 0xff)
                                    ),
                                    kotlin.math.abs(
                                        (renderedPixel ushr 24 and 0xff) -
                                            (oraclePixel ushr 24 and 0xff)
                                    )
                                )
                            }
                            require(outsideChanged == 0 && frameMaximumError <= 1) {
                                "persistent GLES differential failed at frame ${time.frameIndex}"
                            }
                            totalOutsideChanged += outsideChanged
                            totalInsideChanged += insideChanged
                            maximumChannelError = maxOf(maximumChannelError, frameMaximumError)
                            rows.put(JSONObject()
                                .put("frame_index", time.frameIndex)
                                .put("presentation_time_us", time.presentationTimeUs)
                                .put("outside_mask_changed_pixels", outsideChanged)
                                .put("inside_mask_changed_pixels", insideChanged)
                                .put("maximum_channel_error", frameMaximumError))
                            if (time.frameIndex == timeline.nearestFrameIndex(decoded.photoTimestampUs)) {
                                FileOutputStream(File(
                                    outputDir,
                                    "persistent-tint-cover-preview.png"
                                )).use { stream ->
                                    require(rendered.compress(Bitmap.CompressFormat.PNG, 100, stream))
                                }
                            }
                            source.recycle()
                            rendered.recycle()
                        }
                        require(renderer.renderedFrameCount == timeline.frameCount)
                    }
                }
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", "PASS_PERSISTENT_GLES_TINT_TIMELINE")
                    .put("decoded_frame_count", timeline.frameCount)
                    .put("rendered_frame_count", timeline.frameCount)
                    .put("egl_context_count", 1)
                    .put("shader_program_count", 1)
                    .put("total_outside_mask_changed_pixels", totalOutsideChanged)
                    .put("total_inside_mask_changed_pixels", totalInsideChanged)
                    .put("maximum_channel_error_vs_cpu_oracle", maximumChannelError)
                    .put("wall_elapsed_ms", android.os.SystemClock.elapsedRealtime() - startedAt)
                    .put("frames", rows)
                    .put("media_modified", false)
                File(outputDir, "persistent-tint-timeline-evidence.json")
                    .writeText(evidence.toString(2) + "\n")
                log(
                    "PASS persistent GLES tint: frames=${timeline.frameCount} " +
                        "outside_changed=$totalOutsideChanged max_error=$maximumChannelError"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "persistent_tint_encode_probe") {
                require(BuildConfig.DEBUG) { "persistent tint encode probe is debug-only" }
                require(inputs.size >= 3) {
                    "persistent tint encode probe requires Live Photo and frame masks"
                }
                val decoded = requireNotNull(
                    LivePhotoSdkAdapter(this).decode(
                        inputs[0],
                        File(outputDir, "persistent-tint-encode-decoded")
                    )
                ) { "persistent tint encode input is not a decodable Live Photo" }
                val timeline = AndroidVideoFrameTimelineDecoder.decode(
                    decoded.video,
                    AtomicBoolean(false)
                )
                val masks = inputs.drop(1)
                require(masks.size == timeline.frameCount)
                val encodedVideo = File(outputDir, "persistent-tint-encoded.mp4")
                val encodeResult = AndroidMaskedVideoEncoder().encodeTint(
                    decoded.video,
                    timeline,
                    masks,
                    encodedVideo,
                    "#008000",
                    1.0,
                    AtomicBoolean(false)
                )
                val outputTimeline = AndroidVideoFrameTimelineDecoder.decode(
                    encodedVideo,
                    AtomicBoolean(false)
                )
                require(outputTimeline.frameCount == timeline.frameCount)
                require(outputTimeline.frames.map { it.presentationTimeUs } ==
                    timeline.frames.map { it.presentationTimeUs }) {
                    "encoded video presentation timeline changed"
                }
                val sourceAudio = AndroidMediaTrackInspector.fingerprint(decoded.video, "audio/")
                val outputAudio = AndroidMediaTrackInspector.fingerprint(encodedVideo, "audio/")
                require((sourceAudio == null && outputAudio == null) ||
                    (sourceAudio != null && outputAudio != null &&
                        sourceAudio.sampleCount == outputAudio.sampleCount &&
                        sourceAudio.sampleDataSha256 == outputAudio.sampleDataSha256 &&
                        sourceAudio.timelineSha256 == outputAudio.timelineSha256)) {
                    "audio samples changed during masked video encode"
                }
                val anchorIndex = timeline.nearestFrameIndex(decoded.photoTimestampUs)
                val sourceAnchor = AndroidVideoFrameBatchReader(decoded.video, timeline).use { reader ->
                    reader.read(anchorIndex, 1).single().bitmap
                }
                val expectedAnchor = AndroidPersistentTintRenderer(
                    sourceAnchor.width,
                    sourceAnchor.height,
                    "#008000",
                    1.0
                ).use { renderer ->
                    renderer.render(sourceAnchor, masks[anchorIndex].readBytes())
                }
                val encodedAnchor = AndroidVideoFrameBatchReader(encodedVideo, outputTimeline).use { reader ->
                    reader.read(anchorIndex, 1).single().bitmap
                }
                val expectedPixels = IntArray(expectedAnchor.width * expectedAnchor.height)
                val encodedPixels = IntArray(expectedPixels.size)
                expectedAnchor.getPixels(
                    expectedPixels, 0, expectedAnchor.width, 0, 0,
                    expectedAnchor.width, expectedAnchor.height
                )
                encodedAnchor.getPixels(
                    encodedPixels, 0, encodedAnchor.width, 0, 0,
                    encodedAnchor.width, encodedAnchor.height
                )
                var directSquaredError = 0.0
                var flippedSquaredError = 0.0
                for (y in 0 until expectedAnchor.height) {
                    for (x in 0 until expectedAnchor.width) {
                        val index = y * expectedAnchor.width + x
                        val flippedIndex = (expectedAnchor.height - 1 - y) * expectedAnchor.width + x
                        var shift = 0
                        while (shift <= 16) {
                            val expected = expectedPixels[index] ushr shift and 0xff
                            val direct = encodedPixels[index] ushr shift and 0xff
                            val flipped = encodedPixels[flippedIndex] ushr shift and 0xff
                            directSquaredError += (direct - expected) * (direct - expected).toDouble()
                            flippedSquaredError += (flipped - expected) * (flipped - expected).toDouble()
                            shift += 8
                        }
                    }
                }
                val sampleCount = expectedPixels.size * 3.0
                val directMse = directSquaredError / sampleCount
                val flippedMse = flippedSquaredError / sampleCount
                val directPsnr = if (directMse == 0.0) Double.POSITIVE_INFINITY
                    else 10.0 * kotlin.math.log10(255.0 * 255.0 / directMse)
                val flippedPsnr = if (flippedMse == 0.0) Double.POSITIVE_INFINITY
                    else 10.0 * kotlin.math.log10(255.0 * 255.0 / flippedMse)
                require(directPsnr >= 25.0 && directPsnr > flippedPsnr) {
                    "encoded tint frame failed orientation/quality gate: $directPsnr vs $flippedPsnr"
                }
                val cover = File(outputDir, "persistent-tint-cover.jpg")
                FileOutputStream(cover).use { stream ->
                    require(expectedAnchor.compress(Bitmap.CompressFormat.JPEG, 95, stream))
                }
                sourceAnchor.recycle()
                expectedAnchor.recycle()
                encodedAnchor.recycle()
                val packed = LivePhotoSdkAdapter(this).pack(
                    cover,
                    encodedVideo,
                    File(outputDir, "persistent-tint-livephoto-pack"),
                    decoded.photoTimestampUs
                )
                val verified = requireNotNull(
                    LivePhotoSdkAdapter(this).decode(
                        packed,
                        File(outputDir, "persistent-tint-livephoto-verify")
                    )
                ) { "encoded tint Live Photo cannot be decoded" }
                require(verified.photoTimestampUs == decoded.photoTimestampUs)
                val verifiedTimeline = AndroidVideoFrameTimelineDecoder.decode(
                    verified.video,
                    AtomicBoolean(false)
                )
                require(verifiedTimeline.frames.map { it.presentationTimeUs } ==
                    timeline.frames.map { it.presentationTimeUs })
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", "PASS_PERSISTENT_GLES_MEDIACODEC_LIVEPHOTO")
                    .put("source_frame_count", timeline.frameCount)
                    .put("encoded_frame_count", encodeResult.videoFrameCount)
                    .put("presentation_timestamps_exact", true)
                    .put("audio_present", sourceAudio != null)
                    .put("audio_sample_count", encodeResult.audioSampleCount)
                    .put("audio_sample_data_exact", sourceAudio?.sampleDataSha256 ==
                        outputAudio?.sampleDataSha256)
                    .put("audio_timeline_exact", sourceAudio?.timelineSha256 ==
                        outputAudio?.timelineSha256)
                    .put("encoder_name", encodeResult.encoderName)
                    .put("encode_wall_ms", encodeResult.wallElapsedMs)
                    .put("anchor_frame_index", anchorIndex)
                    .put("encoded_anchor_psnr_db", directPsnr)
                    .put("vertically_flipped_psnr_db", flippedPsnr)
                    .put("photo_timestamp_us", verified.photoTimestampUs)
                    .put("packed_livephoto_sha256", fileSha256(packed))
                    .put("source_media_modified", false)
                    .put("published", false)
                File(outputDir, "persistent-tint-encode-evidence.json")
                    .writeText(evidence.toString(2) + "\n")
                log(
                    "PASS persistent GLES -> MediaCodec -> LivePhoto: " +
                        "frames=${encodeResult.videoFrameCount} audio=${encodeResult.audioSampleCount} " +
                        "psnr=$directPsnr"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "edgetam_temporal_state_probe") {
                require(BuildConfig.DEBUG) { "temporal state probe is debug-only" }
                require(inputs.size == 1 && inputs.single().name == "manifest.json") {
                    "temporal state probe requires the dense fixture manifest"
                }
                val fixtureRoot = inputs.single().parentFile?.parentFile
                    ?: error("fixture root is missing")
                val runsRoot = File(fixtureRoot, "runs/fixed-bank-validated")
                val frameDirectories = runsRoot.listFiles { file ->
                    file.isDirectory && file.name.startsWith("frame-")
                }?.sortedBy { it.name }.orEmpty()
                require(frameDirectories.size >= 2) { "temporal fixture has too few frames" }
                val state = AndroidEdgeTamTemporalState(ByteArray(
                    AndroidEdgeTamTemporalState.OBJECT_POINTER_BYTES
                ))
                val rows = org.json.JSONArray()
                var comparedTransitions = 0
                frameDirectories.forEachIndexed { sequenceIndex, frameDirectory ->
                    if (sequenceIndex > 0) {
                        val actual = state.inputsForNextFrame()
                        val generated = File(frameDirectory, "generated")
                        val expectedMemory = File(generated, "attention_maskmem_features.raw")
                        val expectedPointers = File(generated, "attention_object_pointers.raw")
                        require(expectedMemory.isFile && expectedPointers.isFile)
                        require(actual.maskMemoryValues.contentEquals(expectedMemory.readBytes())) {
                            "mask memory packing differs at $sequenceIndex"
                        }
                        require(actual.objectPointerValues.contentEquals(expectedPointers.readBytes())) {
                            "object pointer packing differs at $sequenceIndex"
                        }
                        rows.put(JSONObject()
                            .put("sequence_index", sequenceIndex)
                            .put("spatial_memory_count", actual.spatialMemoryCount)
                            .put("object_pointer_count", actual.objectPointerCount)
                            .put("context_file", actual.contextFileName)
                            .put("mask_memory_sha256", fileSha256(expectedMemory))
                            .put("object_pointers_sha256", fileSha256(expectedPointers)))
                        comparedTransitions += 1
                    }
                    if (sequenceIndex < frameDirectories.lastIndex) {
                        val memory = File(
                            frameDirectory,
                            "memory/Result_0/maskmem_features.raw"
                        )
                        val pointer = File(frameDirectory, "decoder/Result_0/obj_ptr.raw")
                        val objectLogit = File(
                            frameDirectory,
                            "decoder/Result_0/object_score_logits.raw"
                        )
                        require(memory.isFile && pointer.isFile && objectLogit.isFile) {
                            "state outputs are missing for sequence $sequenceIndex"
                        }
                        val modelObjectPresent = java.nio.ByteBuffer.wrap(objectLogit.readBytes())
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .float > 0f
                        state.append(
                            sequenceIndex,
                            memory.readBytes(),
                            pointer.readBytes(),
                            modelObjectPresent
                        )
                    }
                }
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", "PASS_ANDROID_EDGETAM_TEMPORAL_STATE_PACKING")
                    .put("fixture_frame_count", frameDirectories.size)
                    .put("compared_transitions", comparedTransitions)
                    .put("all_transitions_bit_exact", true)
                    .put("state_count_source", "processed frame outputs")
                    .put("maximum_spatial_memories", AndroidEdgeTamTemporalState.MAX_SPATIAL_MEMORIES)
                    .put("maximum_object_pointers", AndroidEdgeTamTemporalState.MAX_OBJECT_POINTERS)
                    .put("transitions", rows)
                    .put("media_modified", false)
                File(outputDir, "edgetam-temporal-state-evidence.json")
                    .writeText(evidence.toString(2) + "\n")
                log(
                    "PASS EdgeTAM temporal state: frames=${frameDirectories.size} " +
                        "transitions=$comparedTransitions bit_exact=true"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "qnn_edgetam_temporal_fixture_probe") {
                require(BuildConfig.DEBUG && BuildConfig.LOCAL_TRACKED_PREVIEW) {
                    "temporal QNN fixture probe requires the opt-in debug build"
                }
                require(inputs.size == 1 && inputs.single().name == "manifest.json") {
                    "temporal QNN fixture probe requires the dense fixture manifest"
                }
                val fixtureRoot = inputs.single().parentFile?.parentFile
                    ?: error("fixture root is missing")
                val inputRoot = File(fixtureRoot, "frame-inputs")
                val expectedRoot = File(fixtureRoot, "runs/fixed-bank-validated")
                val inputFrames = inputRoot.listFiles { file ->
                    file.isDirectory && file.name.startsWith("frame-")
                }?.sortedBy { it.name }.orEmpty()
                val requestedFrames = imageParams.optInt("max_frames", inputFrames.size)
                    .coerceIn(2, inputFrames.size)
                val frames = inputFrames.take(requestedFrames)
                val temporalRunner = AndroidEdgeTamTemporalQnnRunner(this)
                val state = temporalRunner.createState()
                val rows = org.json.JSONArray()
                frames.forEachIndexed { sequenceIndex, frameInput ->
                    val source = BitmapFactory.decodeFile(
                        File(frameInput, "source.png").absolutePath
                    ) ?: error("fixture source frame is missing")
                    val width = source.width
                    val height = source.height
                    source.recycle()
                    val initialPoints = if (sequenceIndex == 0) {
                        AndroidOwlV2QnnLocator.readFloats(
                            File(frameInput, "unnorm_coords.raw"),
                            4
                        )
                    } else null
                    val result = temporalRunner.processRawFrame(
                        sequenceIndex = sequenceIndex,
                        imageInput = File(frameInput, "image.raw"),
                        sourceWidth = width,
                        sourceHeight = height,
                        initialPromptCoordinates = initialPoints,
                        state = state,
                        cancelled = AtomicBoolean(false),
                        produceMemory = sequenceIndex < frames.lastIndex,
                        progress = { message -> log(message) }
                    )
                    val expectedRawMask = File(
                        expectedRoot,
                        "${frameInput.name}/decoder/Result_0/masks.raw"
                    )
                    require(expectedRawMask.isFile) { "expected decoder mask is missing" }
                    val expectedRawSha = fileSha256(expectedRawMask)
                    require(result.decoderMaskRawSha256 == expectedRawSha) {
                        "decoder mask differs from prior vivo run at $sequenceIndex: " +
                            "actual=${result.decoderMaskRawSha256} expected=$expectedRawSha"
                    }
                    rows.put(JSONObject()
                        .put("sequence_index", sequenceIndex)
                        .put("fixture_frame", frameInput.name)
                        .put("spatial_memory_count", result.spatialMemoryCount)
                        .put("object_pointer_count", result.objectPointerCount)
                        .put("model_object_present", result.modelObjectPresent)
                        .put("raw_mask_pixels", result.rawMaskPixels)
                        .put("decoder_mask_raw_sha256", result.decoderMaskRawSha256)
                        .put("decoder_mask_bit_exact_vs_prior_vivo", true)
                        .put("score", result.score.toDouble())
                        .put("object_score_logit", result.objectScoreLogit.toDouble())
                        .put("encoder_ms", result.encoderMs)
                        .put("attention_ms", result.attentionMs ?: JSONObject.NULL)
                        .put("decoder_ms", result.decoderMs)
                        .put("memory_encoder_ms", result.memoryEncoderMs ?: JSONObject.NULL)
                        .put("evidence_fingerprint", result.evidenceFingerprint))
                }
                val residentContextsAtCompletion = if (BuildConfig.PERSISTENT_QNN) {
                    AndroidQnnPersistentNativeBridge.loadedContextCount().also {
                        require(it in 1..4) { "persistent QNN context LRU exceeded its bound: $it" }
                    }
                } else {
                    0
                }
                val powerVoteActiveAtCompletion = BuildConfig.PERSISTENT_QNN &&
                    AndroidQnnPersistentNativeBridge.isPowerVoteActive()
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", "PASS_ANDROID_APP_UID_EDGETAM_TEMPORAL_QNN")
                    .put("processed_frame_count", frames.size)
                    .put("frame_count_source", "fixture directory discovery")
                    .put("all_decoder_masks_bit_exact_vs_prior_vivo", true)
                    .put("qairt_exact", AndroidQnnContextProcessRunner.QAIRT_EXACT)
                    .put(
                        "execution_bridge",
                        if (BuildConfig.PERSISTENT_QNN) "persistent-qairt245-jni-context-lru4"
                        else "packaged-qnn-net-run-child-process"
                    )
                    .put("persistent_jni_context", BuildConfig.PERSISTENT_QNN)
                    .put("maximum_resident_contexts", if (BuildConfig.PERSISTENT_QNN) 4 else 0)
                    .put("resident_contexts_at_completion", residentContextsAtCompletion)
                    .put("burst_power_vote_active_at_completion", powerVoteActiveAtCompletion)
                    .put("fixed_context_selected_by_state_count", true)
                    .put("frames", rows)
                    .put("media_modified", false)
                    .put("published", false)
                AndroidQnnContextRunnerFactory.releasePersistentContexts()
                if (BuildConfig.PERSISTENT_QNN) {
                    require(AndroidQnnPersistentNativeBridge.loadedContextCount() == 0)
                    require(!AndroidQnnPersistentNativeBridge.isPowerVoteActive())
                }
                evidence
                    .put(
                        "resident_contexts_after_release",
                        if (BuildConfig.PERSISTENT_QNN) {
                            AndroidQnnPersistentNativeBridge.loadedContextCount()
                        } else 0
                    )
                    .put(
                        "burst_power_vote_active_after_release",
                        BuildConfig.PERSISTENT_QNN &&
                            AndroidQnnPersistentNativeBridge.isPowerVoteActive()
                    )
                File(outputDir, "qnn-edgetam-temporal-fixture-evidence.json")
                    .writeText(evidence.toString(2) + "\n")
                log(
                    "PASS app-UID EdgeTAM temporal QNN: frames=${frames.size} " +
                        "decoder_masks_bit_exact=true"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "qnn_edgetam_encoder_fixture_probe") {
                require(BuildConfig.DEBUG && BuildConfig.LOCAL_TRACKED_PREVIEW) {
                    "QNN fixture probe requires the opt-in local tracked-preview debug build"
                }
                require(inputs.size == 5) {
                    "QNN fixture probe requires context, image, coordinates, labels and DSP skel"
                }
                val contextSource = inputs[0]
                val image = inputs[1]
                val coordinates = inputs[2]
                val labels = inputs[3]
                val skelSource = inputs[4]
                require(fileSha256(contextSource) == EDGETAM_ENCODER_CONTEXT_SHA256)
                require(fileSha256(image) == EDGETAM_ENCODER_IMAGE_SHA256)
                require(fileSha256(coordinates) == EDGETAM_ENCODER_COORDINATES_SHA256)
                require(fileSha256(labels) == EDGETAM_ENCODER_LABELS_SHA256)
                require(fileSha256(skelSource) == QAIRT_245_V81_SKEL_SHA256)
                val modelRoot = File(filesDir, "qnn-edgetam-encoder-fixture-pack")
                modelRoot.deleteRecursively()
                val contextFile = File(modelRoot, "contexts/encoder_o3.bin")
                val skelFile = File(modelRoot, "runtime/dsp/libQnnHtpV81Skel.so")
                copyProbeFile(contextSource, contextFile)
                copyProbeFile(skelSource, skelFile)
                val contract = AndroidQnnContextContract(
                    componentId = "edgetam_encoder_fixture",
                    contextFile = contextFile,
                    contextSha256 = EDGETAM_ENCODER_CONTEXT_SHA256,
                    inputs = listOf(
                        AndroidQnnTensorContract("image", 12_582_912),
                        AndroidQnnTensorContract("unnorm_coords", 16),
                        AndroidQnnTensorContract("labels", 8)
                    ),
                    outputs = listOf(
                        AndroidQnnTensorContract("high_res_features1", 8_388_608),
                        AndroidQnnTensorContract("high_res_features2", 4_194_304),
                        AndroidQnnTensorContract("sparse_embedding", 3_072),
                        AndroidQnnTensorContract("pix_feat", 4_194_304),
                        AndroidQnnTensorContract("image_embeddings", 4_194_304)
                    )
                )
                AndroidQnnContextRunnerFactory.create(this, modelRoot).execute(
                    contract,
                    mapOf(
                        "image" to image,
                        "unnorm_coords" to coordinates,
                        "labels" to labels
                    ),
                    AtomicBoolean(false)
                ).use { result ->
                    val actualHashes = result.outputs.mapValues { fileSha256(it.value) }
                    val expectedHashes = mapOf(
                        "high_res_features1" to
                            "95d32f904faf3380a5478082e84ad7b0cebb0a920889061c0f475687eb77619c",
                        "high_res_features2" to
                            "9610fdc645d7b75583fb6cd6259dff757df474463e09882e893034c4850720a1",
                        "sparse_embedding" to
                            "5bbbcb641acb7968fbe77028d12fbdb29813eda6a2faaa91de3c8ad8a8d29b2d",
                        "pix_feat" to
                            "4a4585aa393663b2d4f5f673fd08ff9e3bf74d61083b54ecd65e539a5867850a",
                        "image_embeddings" to
                            "36f2b47be05b2c808055978485345232f0c4c67f8e25ed1d9b4d446a2f3e041d"
                    )
                    require(actualHashes == expectedHashes) {
                        "app-owned QNN outputs differ from validated vivo tensors"
                    }
                    var warmElapsedMs: Long? = null
                    var warmHashes: Map<String, String>? = null
                    var inFlightCancelCaught = false
                    var cancelObservedGraphDispatch = false
                    var nativeGraphAbortIssued = false
                    var residentContextsAfterCancel: Int? = null
                    var recoveryElapsedMs: Long? = null
                    if (BuildConfig.PERSISTENT_QNN) {
                        AndroidQnnContextRunnerFactory.create(this, modelRoot).execute(
                            contract,
                            mapOf(
                                "image" to image,
                                "unnorm_coords" to coordinates,
                                "labels" to labels
                            ),
                            AtomicBoolean(false)
                        ).use { warm ->
                            warmElapsedMs = warm.elapsedMs
                            warmHashes = warm.outputs.mapValues { fileSha256(it.value) }
                        }
                        require(warmHashes == expectedHashes) {
                            "reused persistent QNN context changed output tensors"
                        }
                        val cancelSignal = AtomicBoolean(false)
                        val cancelThread = Thread({
                            val deadline = System.nanoTime() +
                                java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                            while (System.nanoTime() < deadline &&
                                !AndroidQnnPersistentNativeBridge.isGraphInFlight()
                            ) {
                                Thread.sleep(1)
                            }
                            cancelObservedGraphDispatch =
                                AndroidQnnPersistentNativeBridge.isGraphInFlight()
                            if (cancelObservedGraphDispatch) {
                                nativeGraphAbortIssued =
                                    AndroidQnnPersistentNativeBridge.cancelGraphInFlight()
                            }
                            cancelSignal.set(true)
                        }, "persistent-qnn-fixture-cancel").apply { start() }
                        try {
                            AndroidQnnContextRunnerFactory.create(this, modelRoot).execute(
                                contract,
                                mapOf(
                                    "image" to image,
                                    "unnorm_coords" to coordinates,
                                    "labels" to labels
                                ),
                                cancelSignal
                            ).use { error("warm QNN execution completed after cancellation") }
                        } catch (_: InterruptedException) {
                            inFlightCancelCaught = true
                        } finally {
                            cancelThread.join(5_000)
                        }
                        require(inFlightCancelCaught && cancelSignal.get() &&
                            cancelObservedGraphDispatch && nativeGraphAbortIssued
                        ) {
                            "persistent QNN graph execution did not honor cancellation"
                        }
                        residentContextsAfterCancel =
                            AndroidQnnPersistentNativeBridge.loadedContextCount()
                        require(residentContextsAfterCancel == 0) {
                            "cancelled persistent context was retained"
                        }
                        AndroidQnnContextRunnerFactory.create(this, modelRoot).execute(
                            contract,
                            mapOf(
                                "image" to image,
                                "unnorm_coords" to coordinates,
                                "labels" to labels
                            ),
                            AtomicBoolean(false)
                        ).use { recovery ->
                            recoveryElapsedMs = recovery.elapsedMs
                            require(recovery.outputs.mapValues { fileSha256(it.value) } == expectedHashes) {
                                "persistent QNN context did not recover bit-exactly after cancellation"
                            }
                        }
                    }
                    val persistentLoadedContexts = if (BuildConfig.PERSISTENT_QNN) {
                        AndroidQnnPersistentNativeBridge.loadedContextCount()
                    } else {
                        0
                    }
                    val evidence = JSONObject()
                        .put("schema_version", 1)
                        .put("status", "PASS_ANDROID_APP_UID_QNN245_CONTEXT")
                        .put("component", result.componentId)
                        .put(
                            "execution_bridge",
                            if (BuildConfig.PERSISTENT_QNN) "persistent-qairt245-jni-context"
                            else "packaged-qnn-net-run-child-process"
                        )
                        .put("qairt_exact", AndroidQnnContextProcessRunner.QAIRT_EXACT)
                        .put("context_sha256", EDGETAM_ENCODER_CONTEXT_SHA256)
                        .put("dsp_skel_sha256", QAIRT_245_V81_SKEL_SHA256)
                        .put("elapsed_ms", result.elapsedMs)
                        .put("cold_elapsed_ms", result.elapsedMs)
                        .put("warm_elapsed_ms", warmElapsedMs ?: JSONObject.NULL)
                        .put("context_reused", warmElapsedMs != null)
                        .put("in_flight_cancel_caught", inFlightCancelCaught)
                        .put("cancel_observed_graph_dispatch", cancelObservedGraphDispatch)
                        .put("native_graph_abort_issued", nativeGraphAbortIssued)
                        .put(
                            "resident_contexts_after_cancel",
                            residentContextsAfterCancel ?: JSONObject.NULL
                        )
                        .put("recovery_elapsed_ms", recoveryElapsedMs ?: JSONObject.NULL)
                        .put("recovered_after_cancel", recoveryElapsedMs != null)
                        .put("output_sha256", JSONObject(actualHashes))
                        .put("outputs_bit_exact_vs_validated_vivo", true)
                        .put("app_uid_owned", true)
                        .put("persistent_jni_context", BuildConfig.PERSISTENT_QNN)
                        .put("resident_contexts_after_execute", persistentLoadedContexts)
                        .put("media_modified", false)
                        .put("published", false)
                    File(outputDir, "qnn-edgetam-encoder-fixture-evidence.json")
                        .writeText(evidence.toString(2) + "\n")
                    log(
                        "PASS app-UID QNN 2.45 context: component=${result.componentId} " +
                            "elapsed=${result.elapsedMs}ms bit_exact=true"
                    )
                }
                AndroidQnnContextRunnerFactory.releasePersistentContexts()
                if (BuildConfig.PERSISTENT_QNN) {
                    require(AndroidQnnPersistentNativeBridge.loadedContextCount() == 0) {
                        "persistent QNN contexts were not released after the probe"
                    }
                }
                modelRoot.deleteRecursively()
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "qnn_owlv2_locator_probe") {
                require(BuildConfig.DEBUG && BuildConfig.LOCAL_TRACKED_PREVIEW) {
                    "OWLv2 locator probe requires the opt-in local tracked-preview debug build"
                }
                require(inputs.size == 1 && !agentQuery.isNullOrBlank()) {
                    "OWLv2 locator probe requires one Live Photo and an ASCII canonical query"
                }
                val asset = prepareGoalProbeAsset(inputs.single(), outputDir, "owlv2-locator-decoded")
                val bitmap = requireNotNull(BitmapFactory.decodeFile(asset.imageFile!!.absolutePath))
                val run = try {
                    AndroidOwlV2QnnLocator(this).locate(
                        canonicalQuery = agentQuery,
                        sourceText = null,
                        stateFingerprint = AgentStateFingerprint.of(AgentRuntimeState(
                            sessionId = null,
                            turnId = "turn-owlv2-locator-probe",
                            originalQuery = agentQuery,
                            sourceAssets = listOf(asset)
                        )),
                        bitmap = bitmap,
                        cancelled = AtomicBoolean(false),
                        progress = { message -> log(message) }
                    )
                } finally {
                    bitmap.recycle()
                }
                val result = run.result
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", "PASS_ANDROID_APP_UID_OWLV2_LOCATOR")
                    .put("canonical_query", result.canonicalQuery)
                    .put("disposition", result.disposition.name)
                    .put("candidate_count", result.candidates.size)
                    .put("candidate_scores", org.json.JSONArray(
                        result.candidates.map { it.score.toDouble() }
                    ))
                    .put("internal_candidates", org.json.JSONArray(
                        result.candidates.map { candidate ->
                            JSONObject()
                                .put("raw_index", candidate.rawIndex)
                                .put("label", candidate.label)
                                .put("score", candidate.score.toDouble())
                                .put("box_xyxy", org.json.JSONArray(listOf(
                                    candidate.box.left,
                                    candidate.box.top,
                                    candidate.box.right,
                                    candidate.box.bottom
                                )))
                        }
                    ))
                    .put("evidence_fingerprint", result.evidenceFingerprint)
                    .put("preprocessing_ms", run.preprocessingMs)
                    .put("vision_execution_ms", run.visionExecutionMs)
                    .put("text_execution_ms", run.textExecutionMs)
                    .put("qairt_exact", AndroidQnnContextProcessRunner.QAIRT_EXACT)
                    .put("internal_boxes_diagnostic_only", true)
                    .put("boxes_model_visible", false)
                    .put("content_mutation_allowed", false)
                    .put("media_modified", false)
                    .put("published", false)
                File(outputDir, "qnn-owlv2-locator-evidence.json")
                    .writeText(evidence.toString(2) + "\n")
                log(
                    "PASS app-UID OWLv2 locator: disposition=${result.disposition} " +
                        "candidates=${result.candidates.size} " +
                        "vision=${run.visionExecutionMs}ms text=${run.textExecutionMs}ms"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "qnn_language_mask_probe") {
                require(BuildConfig.DEBUG && BuildConfig.LOCAL_TRACKED_PREVIEW) {
                    "language mask probe requires the opt-in local tracked-preview debug build"
                }
                require(inputs.size == 1 && !agentQuery.isNullOrBlank()) {
                    "language mask probe requires one Live Photo and an ASCII canonical query"
                }
                val asset = prepareGoalProbeAsset(inputs.single(), outputDir, "language-mask-decoded")
                val bitmap = requireNotNull(BitmapFactory.decodeFile(asset.imageFile!!.absolutePath))
                val locatorRun = AndroidOwlV2QnnLocator(this).locate(
                    canonicalQuery = agentQuery,
                    sourceText = null,
                    stateFingerprint = AgentStateFingerprint.of(AgentRuntimeState(
                        sessionId = null,
                        turnId = "turn-language-mask-probe",
                        originalQuery = agentQuery,
                        sourceAssets = listOf(asset)
                    )),
                    bitmap = bitmap,
                    cancelled = AtomicBoolean(false),
                    progress = { message -> log(message) }
                )
                require(locatorRun.result.disposition ==
                    OwlV2GroundingDisposition.CANDIDATE_SET_READY) {
                    "language target is not unique: ${locatorRun.result.disposition} " +
                        "(${locatorRun.result.candidates.size} candidates)"
                }
                val mask = try {
                    AndroidEdgeTamQnnSegmenter(this).segment(
                        bitmap,
                        locatorRun.result,
                        AtomicBoolean(false),
                        progress = { message -> log(message) },
                        diagnosticImageInputCopy = File(
                            outputDir,
                            "qnn-language-mask-image-input.raw"
                        )
                    )
                } finally {
                    bitmap.recycle()
                }
                require(mask.qualityAccepted) {
                    "EdgeTAM mask failed coverage/leakage gate"
                }
                val maskFile = File(outputDir, "qnn-language-mask.raw")
                maskFile.writeBytes(mask.maskValues)
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", "PASS_ANDROID_LANGUAGE_TO_EDGETAM_MASK")
                    .put("canonical_query", locatorRun.result.canonicalQuery)
                    .put("locator_candidate_count", locatorRun.result.candidates.size)
                    .put("locator_evidence_fingerprint", locatorRun.result.evidenceFingerprint)
                    .put("mask_evidence_fingerprint", mask.evidenceFingerprint)
                    .put("mask_raw_sha256", fileSha256(maskFile))
                    .put("image_input_sha256", fileSha256(File(
                        outputDir,
                        "qnn-language-mask-image-input.raw"
                    )))
                    .put("mask_width", mask.width)
                    .put("mask_height", mask.height)
                    .put("selected_pixels", mask.selectedPixels)
                    .put("coverage", mask.coverage)
                    .put("outside_expanded_box_fraction", mask.outsideExpandedBoxFraction)
                    .put("score", mask.score.toDouble())
                    .put("object_score_logit", mask.objectScoreLogit.toDouble())
                    .put("quality_accepted", mask.qualityAccepted)
                    .put("timing_ms", JSONObject()
                        .put("owlv2_preprocess", locatorRun.preprocessingMs)
                        .put("owlv2_vision", locatorRun.visionExecutionMs)
                        .put("owlv2_text", locatorRun.textExecutionMs)
                        .put("edgetam_preprocess", mask.preprocessingMs)
                        .put("edgetam_encoder", mask.encoderExecutionMs)
                        .put("edgetam_decoder", mask.decoderExecutionMs))
                    .put("qairt_exact", AndroidQnnContextProcessRunner.QAIRT_EXACT)
                    .put("mask_track_materialized", false)
                    .put("next_gate", "EdgeTAM temporal propagation")
                    .put("media_modified", false)
                    .put("published", false)
                File(outputDir, "qnn-language-mask-evidence.json")
                    .writeText(evidence.toString(2) + "\n")
                log(
                    "PASS language -> EdgeTAM mask: selected=${mask.selectedPixels} " +
                        "coverage=${mask.coverage} leakage=${mask.outsideExpandedBoxFraction}"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "agent_real_full_timeline_preview_probe") {
                require(BuildConfig.DEBUG && BuildConfig.LOCAL_TRACKED_PREVIEW) {
                    "full timeline preview probe requires the opt-in debug build"
                }
                require(inputs.size == 1 && !agentQuery.isNullOrBlank()) {
                    "full timeline preview probe requires one Live Photo and agent_query"
                }
                val reviewAction = imageParams.optString("review_action", "cancel")
                require(reviewAction in setOf("cancel", "confirm_undo")) {
                    "review_action must be cancel or confirm_undo"
                }
                val sourceShaBefore = fileSha256(inputs.single())
                val galleryRowsBefore = countAgentPublishedImages()
                val asset = prepareGoalProbeAsset(
                    inputs.single(),
                    outputDir,
                    "real-full-timeline-decoded"
                )
                val compileStatuses = mutableListOf<String>()
                val graphActions = mutableListOf<String>()
                var interaction: AndroidLocalPreviewInteraction? = null
                var terminalState: AgentRuntimeState? = null
                var finalPlan: MobilePlan? = null
                var finalMessage = ""
                var errorMessage: String? = null
                var expectedFrames = 0
                var observedFrames = 0
                val backend = AndroidQnnLocalObservationBackend(
                    this,
                    AndroidFullTimelineMaskTracker(this)
                )
                MobileAgentLoop(this, backend).run(
                    query = agentQuery,
                    uris = emptyList(),
                    layoutContext = null,
                    cancelled = AtomicBoolean(false),
                    listener = object : MobileAgentRunner.Listener {
                        override fun onProgress(message: String) {
                            log(message)
                        }
                        override fun onRuntimeGoal(goal: AgentGoalIr, result: AgentCompileResult) {
                            compileStatuses += AgentCompileResultJson.toJson(result).getString("status")
                        }
                        override fun onRuntimeContentGraph(snapshot: AgentContentGraphSnapshot) {
                            snapshot.graph.nodes.values.firstOrNull {
                                it.kind == ContentNodeKind.MASK_TRACK
                            }?.attributes?.let { attributes ->
                                expectedFrames = attributes["expected_frame_count"]?.toIntOrNull() ?: 0
                                observedFrames = attributes["observed_frame_count"]?.toIntOrNull() ?: 0
                            }
                        }
                        override fun onRuntimeGraph(compilation: AgentDagCompilation) {
                            graphActions += compilation.graph.nodes.values.map { it.actionId }
                        }
                        override fun onLocalPreview(value: AndroidLocalPreviewInteraction) {
                            interaction = value
                        }
                        override fun onRuntimeTerminal(state: AgentRuntimeState, message: String) {
                            terminalState = state
                        }
                        override fun onPlan(plan: MobilePlan) {
                            finalPlan = plan
                        }
                        override fun onResult(result: MobileToolResult) = Unit
                        override fun onFinished(uri: Uri?, message: String) {
                            require(uri == null)
                            finalMessage = message
                        }
                        override fun onError(message: String) {
                            errorMessage = message
                        }
                    },
                    preparedAssets = listOf(asset),
                    dagShadow = true
                )
                val previewInteraction = requireNotNull(interaction)
                val preview = previewInteraction.snapshot()
                val previewFile = File(outputDir, "agent-real-full-timeline-preview.png")
                FileOutputStream(previewFile).use { stream ->
                    require(preview.previewBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
                val packedPreview = requireNotNull(preview.packedLivePhotoFile) {
                    "full timeline preview did not produce a packed Live Photo"
                }
                val packedPreviewCopy = packedPreview.copyTo(
                    File(outputDir, "agent-real-full-timeline-preview.jpg"),
                    overwrite = true
                )
                val pendingReviewState = preview.lifecycle.state
                val publishRejectedBeforeReview = runCatching {
                    AndroidConfirmedLivePhotoPublisher(this).publish(preview)
                }.exceptionOrNull() is IllegalArgumentException
                require(publishRejectedBeforeReview) {
                    "pending preview unexpectedly crossed the publish gate"
                }
                var acceptedReviewState: LocalPreviewReviewState? = null
                var publishReadyAfterExplicitReview = false
                val terminalReviewState = when (reviewAction) {
                    "confirm_undo" -> {
                        val accepted = previewInteraction.confirm()
                        acceptedReviewState = accepted.lifecycle.state
                        require(accepted.lifecycle.state ==
                            LocalPreviewReviewState.ACCEPTED_FOR_REVIEW)
                        require(!accepted.lifecycle.writebackAuthorized)
                        require(packedPreview.isFile)
                        publishReadyAfterExplicitReview =
                            AndroidLocalPreviewPublishPolicy.requirePublishable(
                                cacheDir,
                                accepted.lifecycle,
                                accepted.packedLivePhotoFile,
                                accepted.packedLivePhotoSha256,
                                accepted.timelineFrameCount,
                                accepted.certificateFingerprint
                            ) == packedPreview.canonicalFile
                        previewInteraction.undo().lifecycle.state
                    }
                    else -> previewInteraction.cancel().lifecycle.state
                }
                previewInteraction.release()
                val packedPreviewCleaned = !packedPreview.exists()
                val sourceShaAfter = fileSha256(inputs.single())
                val galleryRowsAfter = countAgentPublishedImages()
                val lifecyclePassed = when (reviewAction) {
                    "confirm_undo" -> acceptedReviewState ==
                        LocalPreviewReviewState.ACCEPTED_FOR_REVIEW &&
                        terminalReviewState == LocalPreviewReviewState.UNDONE &&
                        publishReadyAfterExplicitReview
                    else -> terminalReviewState == LocalPreviewReviewState.CANCELLED
                }
                val passed = terminalState?.status == AgentRunStatus.WAITING_USER &&
                    finalPlan?.intent == "local_content_preview_review" &&
                    compileStatuses == listOf("need_observation", "ready") &&
                    graphActions == listOf(LocalDeterministicChangePreviewAction.ACTION_ID) &&
                    expectedFrames > 1 && observedFrames == expectedFrames &&
                    preview.timelineFrameCount == expectedFrames &&
                    packedPreviewCopy.isFile && errorMessage == null && lifecyclePassed &&
                    packedPreviewCleaned && sourceShaBefore == sourceShaAfter &&
                    galleryRowsBefore == galleryRowsAfter
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", if (passed) {
                        "PASS_AGENT_REAL_FULL_DYNAMIC_TIMELINE_PREVIEW"
                    } else {
                        "FAIL_AGENT_REAL_FULL_DYNAMIC_TIMELINE_PREVIEW"
                    })
                    .put("query", agentQuery)
                    .put("expected_frame_count", expectedFrames)
                    .put("observed_frame_count", observedFrames)
                    .put("frame_count_source", "MediaCodec output until EOS")
                    .put("compile_statuses", org.json.JSONArray(compileStatuses))
                    .put("graph_actions", org.json.JSONArray(graphActions))
                    .put("terminal_status", terminalState?.status?.name)
                    .put("plan_intent", finalPlan?.intent)
                    .put("final_message", finalMessage)
                    .put("review_state", preview.lifecycle.state.name)
                    .put("renderer_backend", preview.rendererBackendId)
                    .put("preview_frame_index", preview.frameIndex)
                    .put("preview_timestamp_ms", preview.timestampMs)
                    .put("preview_sha256", fileSha256(previewFile))
                    .put("packed_livephoto_sha256", fileSha256(packedPreviewCopy))
                    .put("packed_livephoto_private_preview", true)
                    .put("review_action", reviewAction)
                    .put("pending_review_state", pendingReviewState.name)
                    .put(
                        "accepted_review_state",
                        acceptedReviewState?.name ?: JSONObject.NULL
                    )
                    .put("terminal_review_state", terminalReviewState.name)
                    .put("writeback_authorized", false)
                    .put("publish_rejected_before_review", publishRejectedBeforeReview)
                    .put("publish_ready_after_explicit_review", publishReadyAfterExplicitReview)
                    .put("packed_artifact_content_bound", preview.packedLivePhotoSha256 != null)
                    .put("publish_requires_second_user_authorization", true)
                    .put("packed_preview_cleaned_after_release", packedPreviewCleaned)
                    .put("source_sha256_before", sourceShaBefore)
                    .put("source_sha256_after", sourceShaAfter)
                    .put("gallery_rows_before", galleryRowsBefore)
                    .put("gallery_rows_after", galleryRowsAfter)
                    .put("error_message", errorMessage ?: JSONObject.NULL)
                    .put("source_media_modified", false)
                    .put("published", false)
                File(outputDir, "agent-real-full-timeline-preview-evidence.json")
                    .writeText(evidence.toString(2) + "\n")
                require(passed) { "full dynamic timeline preview contract failed" }
                log(
                    "PASS Agent full dynamic timeline preview: $observedFrames/$expectedFrames " +
                        "backend=${preview.rendererBackendId}"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "agent_full_timeline_cancel_probe") {
                require(BuildConfig.DEBUG && BuildConfig.LOCAL_TRACKED_PREVIEW) {
                    "full timeline cancel probe requires the opt-in debug build"
                }
                require(inputs.size == 1 && !agentQuery.isNullOrBlank()) {
                    "full timeline cancel probe requires one Live Photo and agent_query"
                }
                val asset = prepareGoalProbeAsset(
                    inputs.single(),
                    outputDir,
                    "full-timeline-cancel-decoded"
                )
                val cancelledSignal = AtomicBoolean(false)
                val cancellationArmed = AtomicBoolean(false)
                var cancellationThread: Thread? = null
                var terminalStatus: AgentRunStatus? = null
                var terminalMessage = ""
                var interactionProduced = false
                var errorMessage: String? = null
                val startedAt = android.os.SystemClock.elapsedRealtime()
                MobileAgentLoop(
                    this,
                    AndroidQnnLocalObservationBackend(
                        this,
                        AndroidFullTimelineMaskTracker(this)
                    )
                ).run(
                    query = agentQuery,
                    uris = emptyList(),
                    layoutContext = null,
                    cancelled = cancelledSignal,
                    listener = object : MobileAgentRunner.Listener {
                        override fun onProgress(message: String) {
                            log(message)
                            if (message.contains("EdgeTAM frame") && message.contains("encoder") &&
                                cancellationArmed.compareAndSet(false, true)
                            ) {
                                cancellationThread = Thread({
                                    Thread.sleep(100)
                                    cancelledSignal.set(true)
                                }, "qnn-cancel-probe").apply { start() }
                            }
                        }
                        override fun onLocalPreview(value: AndroidLocalPreviewInteraction) {
                            interactionProduced = true
                            releaseLocalPreviewForProbe(value)
                        }
                        override fun onRuntimeTerminal(state: AgentRuntimeState, message: String) {
                            terminalStatus = state.status
                            terminalMessage = message
                        }
                        override fun onPlan(plan: MobilePlan) = Unit
                        override fun onResult(result: MobileToolResult) = Unit
                        override fun onFinished(uri: Uri?, message: String) = Unit
                        override fun onError(message: String) {
                            errorMessage = message
                        }
                    },
                    preparedAssets = listOf(asset),
                    dagShadow = true
                )
                cancellationThread?.join(5_000)
                val trackRoots = File(cacheDir, "edgetam-full-tracks")
                    .listFiles().orEmpty().filter(File::exists)
                val qnnRoots = File(cacheDir, "qnn-context-runs")
                    .listFiles().orEmpty().filter(File::exists)
                val passed = cancellationArmed.get() && cancelledSignal.get() &&
                    terminalStatus == AgentRunStatus.CANCELLED && !interactionProduced &&
                    errorMessage == null && trackRoots.isEmpty() && qnnRoots.isEmpty()
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", if (passed) {
                        "PASS_AGENT_FULL_TIMELINE_INFLIGHT_CANCEL"
                    } else {
                        "FAIL_AGENT_FULL_TIMELINE_INFLIGHT_CANCEL"
                    })
                    .put("cancellation_armed", cancellationArmed.get())
                    .put("cancelled_signal", cancelledSignal.get())
                    .put("terminal_status", terminalStatus?.name ?: JSONObject.NULL)
                    .put("terminal_message", terminalMessage)
                    .put("interaction_produced", interactionProduced)
                    .put("error_message", errorMessage ?: JSONObject.NULL)
                    .put("remaining_track_roots", trackRoots.size)
                    .put("remaining_qnn_roots", qnnRoots.size)
                    .put("wall_elapsed_ms", android.os.SystemClock.elapsedRealtime() - startedAt)
                    .put("source_media_modified", false)
                    .put("published", false)
                File(outputDir, "agent-full-timeline-cancel-evidence.json")
                    .writeText(evidence.toString(2) + "\n")
                require(passed) { "full timeline cancellation contract failed" }
                log("PASS full timeline in-flight cancellation")
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "agent_real_keyframe_preview_probe") {
                require(BuildConfig.DEBUG && BuildConfig.LOCAL_TRACKED_PREVIEW) {
                    "real key-frame preview probe requires the opt-in debug build"
                }
                require(inputs.size == 1 && !agentQuery.isNullOrBlank()) {
                    "real key-frame preview probe requires one Live Photo and agent_query"
                }
                val asset = prepareGoalProbeAsset(inputs.single(), outputDir, "real-keyframe-decoded")
                var observedMask: AndroidEdgeTamInitialMask? = null
                val backend = AndroidQnnLocalObservationBackend(
                    this,
                    AndroidEdgeTamKeyFrameTracker(this) { mask -> observedMask = mask }
                )
                val compileStatuses = mutableListOf<String>()
                val graphActions = mutableListOf<String>()
                var interaction: AndroidLocalPreviewInteraction? = null
                var terminalState: AgentRuntimeState? = null
                var finalPlan: MobilePlan? = null
                var finalMessage = ""
                var errorMessage: String? = null
                MobileAgentLoop(this, backend).run(
                    query = agentQuery,
                    uris = emptyList(),
                    layoutContext = null,
                    cancelled = AtomicBoolean(false),
                    listener = object : MobileAgentRunner.Listener {
                        override fun onProgress(message: String) {
                            log(message)
                        }
                        override fun onRuntimeGoal(goal: AgentGoalIr, result: AgentCompileResult) {
                            compileStatuses += AgentCompileResultJson.toJson(result).getString("status")
                        }
                        override fun onRuntimeGraph(compilation: AgentDagCompilation) {
                            graphActions += compilation.graph.nodes.values.map { it.actionId }
                        }
                        override fun onLocalPreview(value: AndroidLocalPreviewInteraction) {
                            interaction = value
                        }
                        override fun onRuntimeTerminal(state: AgentRuntimeState, message: String) {
                            terminalState = state
                        }
                        override fun onPlan(plan: MobilePlan) {
                            finalPlan = plan
                        }
                        override fun onResult(result: MobileToolResult) = Unit
                        override fun onFinished(uri: Uri?, message: String) {
                            require(uri == null)
                            finalMessage = message
                        }
                        override fun onError(message: String) {
                            errorMessage = message
                        }
                    },
                    preparedAssets = listOf(asset),
                    dagShadow = true
                )
                val mask = requireNotNull(observedMask)
                val previewInteraction = requireNotNull(interaction)
                val preview = previewInteraction.snapshot()
                val sourcePixels = IntArray(mask.width * mask.height)
                val previewPixels = IntArray(sourcePixels.size)
                preview.sourceBitmap.getPixels(sourcePixels, 0, mask.width, 0, 0,
                    mask.width, mask.height)
                preview.previewBitmap.getPixels(previewPixels, 0, mask.width, 0, 0,
                    mask.width, mask.height)
                var outsideChanged = 0
                var insideChanged = 0
                for (index in sourcePixels.indices) {
                    if ((mask.maskValues[index].toInt() and 0xff) == 0) {
                        if (sourcePixels[index] != previewPixels[index]) outsideChanged += 1
                    } else if (sourcePixels[index] != previewPixels[index]) {
                        insideChanged += 1
                    }
                }
                val previewFile = File(outputDir, "agent-real-keyframe-preview.png")
                FileOutputStream(previewFile).use { stream ->
                    require(preview.previewBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
                val passed = terminalState?.status == AgentRunStatus.WAITING_USER &&
                    finalPlan?.intent == "local_content_preview_review" &&
                    compileStatuses == listOf("need_observation", "ready") &&
                    graphActions == listOf(LocalDeterministicChangePreviewAction.ACTION_ID) &&
                    mask.qualityAccepted && outsideChanged == 0 && insideChanged > 0 &&
                    errorMessage == null
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", if (passed) {
                        "PASS_AGENT_REAL_QNN_KEYFRAME_PREVIEW"
                    } else {
                        "FAIL_AGENT_REAL_QNN_KEYFRAME_PREVIEW"
                    })
                    .put("query", agentQuery)
                    .put("observation_backend", "owlv2-edgetam-qairt245-app-uid")
                    .put("scope", "KEY_FRAME_ONLY")
                    .put("temporal_mask_track_complete", false)
                    .put("compile_statuses", org.json.JSONArray(compileStatuses))
                    .put("graph_actions", org.json.JSONArray(graphActions))
                    .put("terminal_status", terminalState?.status?.name)
                    .put("plan_intent", finalPlan?.intent)
                    .put("final_message", finalMessage)
                    .put("review_state", preview.lifecycle.state.name)
                    .put("renderer_backend", preview.rendererBackendId)
                    .put("mask_evidence_fingerprint", mask.evidenceFingerprint)
                    .put("mask_selected_pixels", mask.selectedPixels)
                    .put("mask_coverage", mask.coverage)
                    .put("mask_outside_box_fraction", mask.outsideExpandedBoxFraction)
                    .put("outside_mask_changed_pixels", outsideChanged)
                    .put("inside_mask_changed_pixels", insideChanged)
                    .put("preview_sha256", fileSha256(previewFile))
                    .put("error_message", errorMessage ?: JSONObject.NULL)
                    .put("source_media_modified", false)
                    .put("published", false)
                    .put("next_gate", "all-frame EdgeTAM temporal propagation")
                File(outputDir, "agent-real-keyframe-preview-evidence.json")
                    .writeText(evidence.toString(2) + "\n")
                previewInteraction.cancel()
                previewInteraction.release()
                if (!preview.sourceBitmap.isRecycled) preview.sourceBitmap.recycle()
                require(passed) { "real QNN key-frame preview contract failed" }
                log(
                    "PASS Agent real QNN key-frame preview: backend=${preview.rendererBackendId} " +
                        "outside_changed=$outsideChanged inside_changed=$insideChanged"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "agent_local_fixture_preview_probe") {
                require(BuildConfig.DEBUG && BuildConfig.LOCAL_TRACKED_PREVIEW) {
                    "agent_local_fixture_preview_probe requires the opt-in debug build"
                }
                require(inputs.size == 2 && !agentQuery.isNullOrBlank()) {
                    "fixture preview probe requires Live Photo, mask and agent_query"
                }
                val asset = prepareGoalProbeAsset(inputs[0], outputDir, "fixture-loop-decoded")
                val sourceBitmap = requireNotNull(BitmapFactory.decodeFile(asset.imageFile!!.absolutePath))
                val maskBitmap = requireNotNull(BitmapFactory.decodeFile(inputs[1].absolutePath))
                require(sourceBitmap.width == maskBitmap.width && sourceBitmap.height == maskBitmap.height)
                val maskPixels = IntArray(maskBitmap.width * maskBitmap.height)
                maskBitmap.getPixels(
                    maskPixels, 0, maskBitmap.width, 0, 0, maskBitmap.width, maskBitmap.height
                )
                val maskValues = ByteArray(maskPixels.size) { index ->
                    (maskPixels[index] ushr 16 and 0xff).toByte()
                }
                maskBitmap.recycle()
                val fixtureBackend = AgentLocalObservationBackend {
                        goal, state, content, _, _ ->
                    val operationRow = goal.operations.single()
                    val semantic = operationRow.target as AgentGoalTarget.Semantic
                    val sourceAsset = state.sourceAssets.single()
                    val objectTrack = when (val grounded =
                        AgentShadowObservationBridge.materializeObjectTrack(
                            content,
                            ShadowTargetGroundingObservation(
                                operationId = operationRow.operationId,
                                semanticTarget = semantic.description,
                                sourceAssetId = sourceAsset.assetId,
                                candidateCount = 1,
                                observedAtMs = 0,
                                locatorEvidenceFingerprint = fileSha256(inputs[1])
                            )
                        )) {
                        is ShadowObjectTrackResult.Materialized -> grounded.track
                        is ShadowObjectTrackResult.NeedUser ->
                            return@AgentLocalObservationBackend AgentLocalObservationResolution.NeedUser(
                                "fixture unexpectedly ambiguous"
                            )
                        is ShadowObjectTrackResult.NeedObservation ->
                            return@AgentLocalObservationBackend AgentLocalObservationResolution.Pending(
                                emptyList(),
                                grounded.reason
                            )
                    }
                    val maskTrack = AgentShadowObservationBridge.materializeMaskTrack(
                        objectTrack,
                        expectedFrameCount = 1,
                        frames = listOf(ShadowMaskFrame.create(
                            frameIndex = 0,
                            timestampMs = 0,
                            width = sourceBitmap.width,
                            height = sourceBitmap.height,
                            maskValues = maskValues
                        )),
                        validationPolicy = TrackValidationPolicy("fixture-candidate-v1", 0.10)
                    )
                    val materialization = AgentShadowObservationBridge.finalizeForCompiler(
                        goal,
                        maskTrack
                    )
                    AgentLocalObservationResolution.Ready(
                        materialization,
                        AndroidShadowSourceFrameProvider { assetId, frameIndex, timestampMs ->
                            AndroidShadowSourceFrame(
                                assetId,
                                frameIndex,
                                timestampMs,
                                sourceBitmap
                            )
                        }
                    )
                }
                val compileStatuses = mutableListOf<String>()
                val graphActions = mutableListOf<String>()
                var interaction: AndroidLocalPreviewInteraction? = null
                var terminalState: AgentRuntimeState? = null
                var finalPlan: MobilePlan? = null
                var finalMessage = ""
                var errorMessage: String? = null
                MobileAgentLoop(this, fixtureBackend).run(
                    query = agentQuery,
                    uris = emptyList(),
                    layoutContext = null,
                    cancelled = AtomicBoolean(false),
                    listener = object : MobileAgentRunner.Listener {
                        override fun onProgress(message: String) = Unit
                        override fun onRuntimeGoal(goal: AgentGoalIr, result: AgentCompileResult) {
                            compileStatuses += AgentCompileResultJson.toJson(result).getString("status")
                        }
                        override fun onRuntimeGraph(compilation: AgentDagCompilation) {
                            graphActions += compilation.graph.nodes.values.map { it.actionId }
                        }
                        override fun onLocalPreview(value: AndroidLocalPreviewInteraction) {
                            interaction = value
                        }
                        override fun onRuntimeTerminal(state: AgentRuntimeState, message: String) {
                            terminalState = state
                        }
                        override fun onPlan(plan: MobilePlan) {
                            finalPlan = plan
                        }
                        override fun onResult(result: MobileToolResult) = Unit
                        override fun onFinished(uri: Uri?, message: String) {
                            require(uri == null)
                            finalMessage = message
                        }
                        override fun onError(message: String) {
                            errorMessage = message
                        }
                    },
                    preparedAssets = listOf(asset),
                    dagShadow = true
                )
                val previewInteraction = requireNotNull(interaction)
                val preview = previewInteraction.snapshot()
                val source = IntArray(sourceBitmap.width * sourceBitmap.height)
                val rendered = IntArray(source.size)
                sourceBitmap.getPixels(source, 0, sourceBitmap.width, 0, 0,
                    sourceBitmap.width, sourceBitmap.height)
                preview.previewBitmap.getPixels(rendered, 0, sourceBitmap.width, 0, 0,
                    sourceBitmap.width, sourceBitmap.height)
                var outsideChanged = 0
                var insideChanged = 0
                for (index in source.indices) {
                    if ((maskValues[index].toInt() and 0xff) == 0) {
                        if (source[index] != rendered[index]) outsideChanged += 1
                    } else if (source[index] != rendered[index]) {
                        insideChanged += 1
                    }
                }
                val passed = terminalState?.status == AgentRunStatus.WAITING_USER &&
                    finalPlan?.intent == "local_content_preview_review" &&
                    compileStatuses == listOf("need_observation", "ready") &&
                    graphActions == listOf(LocalDeterministicChangePreviewAction.ACTION_ID) &&
                    outsideChanged == 0 && insideChanged > 0 && errorMessage == null
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", if (passed) {
                        "PASS_AGENT_LOCAL_FIXTURE_PREVIEW"
                    } else {
                        "FAIL_AGENT_LOCAL_FIXTURE_PREVIEW"
                    })
                    .put("query", agentQuery)
                    .put("observation_backend", "validated-mask-debug-fixture")
                    .put("compile_statuses", org.json.JSONArray(compileStatuses))
                    .put("graph_actions", org.json.JSONArray(graphActions))
                    .put("terminal_status", terminalState?.status?.name)
                    .put("plan_intent", finalPlan?.intent)
                    .put("final_message", finalMessage)
                    .put("review_state", preview.lifecycle.state.name)
                    .put("renderer_backend", preview.rendererBackendId)
                    .put("outside_mask_changed_pixels", outsideChanged)
                    .put("inside_mask_changed_pixels", insideChanged)
                    .put("error_message", errorMessage ?: JSONObject.NULL)
                    .put("source_media_modified", false)
                    .put("published", false)
                File(outputDir, "agent-local-fixture-preview-evidence.json")
                    .writeText(evidence.toString(2) + "\n")
                previewInteraction.cancel()
                previewInteraction.release()
                sourceBitmap.recycle()
                require(passed) { "agent local fixture preview contract failed" }
                log(
                    "PASS Agent fixture preview: actions=$graphActions backend=${preview.rendererBackendId} " +
                        "outside_changed=$outsideChanged"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "agent_local_loop_probe" ||
                operation == "agent_nonlocal_fallthrough_probe"
            ) {
                require(BuildConfig.DEBUG && BuildConfig.LOCAL_TRACKED_PREVIEW) {
                    "agent_local_loop_probe requires the opt-in local tracked-preview debug build"
                }
                require(inputs.size == 1 && !agentQuery.isNullOrBlank()) {
                    "agent_local_loop_probe requires one Live Photo and agent_query"
                }
                val asset = prepareGoalProbeAsset(inputs.single(), outputDir, "agent-loop-decoded")
                var goalPayload: JSONObject? = null
                var terminalState: AgentRuntimeState? = null
                var terminalMessage = ""
                var finalMessage = ""
                var finalUri: Uri? = null
                var errorMessage: String? = null
                var toolCallCount = 0
                var toolResultCount = 0
                val progressRows = mutableListOf<String>()
                val brainRows = mutableListOf<JSONObject>()
                var finalPlan: MobilePlan? = null
                MobileAgentLoop(this).run(
                    query = agentQuery,
                    uris = emptyList(),
                    layoutContext = null,
                    cancelled = AtomicBoolean(false),
                    listener = object : MobileAgentRunner.Listener {
                        override fun onProgress(message: String) {
                            progressRows += message
                        }

                        override fun onRuntimeGoal(goal: AgentGoalIr, result: AgentCompileResult) {
                            goalPayload = AgentCompileResultJson.toJson(result)
                                .put("operation_kinds", org.json.JSONArray(
                                    goal.operations.map { it.kind.name }
                                ))
                        }

                        override fun onRuntimeBrainTrace(stage: String, trace: BrainTrace) {
                            brainRows += JSONObject()
                                .put("stage", stage)
                                .put("parsed", trace.parsed)
                                .put("raw_output", trace.rawOutput)
                        }

                        override fun onRuntimeTerminal(
                            runtimeState: AgentRuntimeState,
                            message: String
                        ) {
                            terminalState = runtimeState
                            terminalMessage = message
                        }

                        override fun onToolCall(
                            index: Int,
                            total: Int,
                            call: MobileToolCall
                        ) {
                            toolCallCount += 1
                        }

                        override fun onResult(result: MobileToolResult) {
                            toolResultCount += 1
                        }

                        override fun onPlan(plan: MobilePlan) {
                            finalPlan = plan
                        }

                        override fun onFinished(uri: Uri?, message: String) {
                            finalUri = uri
                            finalMessage = message
                        }

                        override fun onError(message: String) {
                            errorMessage = message
                        }
                    },
                    preparedAssets = listOf(asset),
                    sessionId = null,
                    selectedAssetIds = emptyList(),
                    dagShadow = true
                )
                val terminal = requireNotNull(terminalState) { "agent loop emitted no terminal state" }
                val nonLocalProbe = operation == "agent_nonlocal_fallthrough_probe"
                val passed = if (nonLocalProbe) {
                    terminal.status == AgentRunStatus.FINISHED &&
                        goalPayload == null && toolCallCount == 0 && toolResultCount == 0 &&
                        finalUri == null && errorMessage == null && finalPlan?.intent == "chat" &&
                        finalMessage.isNotBlank()
                } else {
                    terminal.status == AgentRunStatus.WAITING_OBSERVATION &&
                        goalPayload?.optString("status") == "need_observation" &&
                        toolCallCount == 0 && toolResultCount == 0 &&
                        finalUri == null && errorMessage == null &&
                        finalPlan?.intent == "local_content_observation"
                }
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", if (passed) {
                        if (nonLocalProbe) "PASS_AGENT_NONLOCAL_FALLTHROUGH"
                        else "PASS_AGENT_LOCAL_INTERACTION_SUSPEND"
                    } else {
                        if (nonLocalProbe) "FAIL_AGENT_NONLOCAL_FALLTHROUGH"
                        else "FAIL_AGENT_LOCAL_INTERACTION_SUSPEND"
                    })
                    .put("query", agentQuery)
                    .put("goal", goalPayload)
                    .put("terminal_status", terminal.status.name)
                    .put("terminal_message", terminalMessage)
                    .put("final_message", finalMessage)
                    .put("plan_intent", finalPlan?.intent)
                    .put("tool_call_count", toolCallCount)
                    .put("tool_result_count", toolResultCount)
                    .put("error_message", errorMessage ?: JSONObject.NULL)
                    .put("progress", org.json.JSONArray(progressRows))
                    .put("brain_traces", org.json.JSONArray(brainRows))
                    .put("global_style_fallback", false)
                    .put("media_modified", false)
                    .put("published", false)
                File(outputDir, if (nonLocalProbe) {
                    "agent-nonlocal-fallthrough-evidence.json"
                } else {
                    "agent-local-loop-evidence.json"
                })
                    .writeText(evidence.toString(2) + "\n")
                require(passed) {
                    if (nonLocalProbe) "agent loop non-local fallthrough contract failed"
                    else "agent loop local suspension contract failed"
                }
                log(
                    "PASS Agent ${if (nonLocalProbe) "non-local fallthrough" else "local loop"}: " +
                        "status=${terminal.status} " +
                        "next=${goalPayload?.optJSONArray("requirements")?.optJSONObject(0)?.optString("kind")} " +
                        "tool_calls=$toolCallCount"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "goal_ir_probe") {
                require(BuildConfig.DEBUG && BuildConfig.LOCAL_TRACKED_PREVIEW) {
                    "goal_ir_probe requires the opt-in local tracked-preview debug build"
                }
                require(inputs.size == 1 && !agentQuery.isNullOrBlank()) {
                    "goal_ir_probe requires one Live Photo and agent_query"
                }
                val asset = prepareGoalProbeAsset(inputs.single(), outputDir, "goal-ir-decoded")
                val state = AgentRuntimeState(
                    sessionId = null,
                    turnId = "turn-goal-ir-probe",
                    originalQuery = agentQuery,
                    sourceAssets = listOf(asset)
                )
                val planner = GenieTextPlanner(this)
                var planning = planner.tryGoalIr(agentQuery, state)?.let { result ->
                    if (!result.localPreviewApplicable) result else {
                        val validated = AgentLocalGoalValidator.apply(result.codecResult)
                        result.copy(
                            codecResult = validated,
                            trace = result.trace.copy(parsed = validated.valid)
                        )
                    }
                }
                if (planning == null ||
                    (planning.localPreviewApplicable && !planning.codecResult.valid)
                ) {
                    val correction = planning?.codecResult?.diagnostics.orEmpty()
                        .joinToString("; ") { "${it.path}: ${it.message}" }
                        .ifBlank { "上一输出不是可解析的plan_live_edit JSON" }
                    planning = planner.tryGoalIr(agentQuery, state, correction)?.let { result ->
                        if (!result.localPreviewApplicable) result else {
                            val validated = AgentLocalGoalValidator.apply(result.codecResult)
                            result.copy(
                                codecResult = validated,
                                trace = result.trace.copy(parsed = validated.valid)
                            )
                        }
                    }
                }
                if (planning == null) {
                    val trace = planner.lastTrace
                    val failureEvidence = JSONObject()
                        .put("schema_version", 1)
                        .put("status", "FAIL_QWEN_LOCAL_CHANGE_GOAL_IR")
                        .put("query", agentQuery)
                        .put("raw_output", trace?.rawOutput.orEmpty())
                        .put("diagnostics", org.json.JSONArray().put(JSONObject()
                            .put("code", "missing_json")
                            .put("path", "$")
                            .put("message", "Qwen did not return a JSON object")))
                        .put("media_modified", false)
                        .put("published", false)
                    File(outputDir, "goal-ir-evidence.json")
                        .writeText(failureEvidence.toString(2) + "\n")
                    log("FAIL Goal IR raw=${trace?.rawOutput.orEmpty().takeLast(2000)}")
                    error("Qwen did not return Goal IR")
                }
                val completed = requireNotNull(planning)
                if (!completed.localPreviewApplicable) {
                    val evidence = JSONObject()
                        .put("schema_version", 1)
                        .put("status", "PASS_SKIP_LOCAL_PREVIEW")
                        .put("query", agentQuery)
                        .put("raw_output", completed.trace.rawOutput)
                        .put("parsed", completed.trace.parsed)
                        .put("media_modified", false)
                        .put("published", false)
                    File(outputDir, "goal-ir-evidence.json")
                        .writeText(evidence.toString(2) + "\n")
                    log("PASS Goal IR eligibility: skip_local_preview")
                    log("=== NATIVE ADAPTER TEST END ===")
                    stopSelf()
                    return
                }
                if (!completed.codecResult.valid || completed.codecResult.goal == null) {
                    val failureEvidence = JSONObject()
                        .put("schema_version", 1)
                        .put("status", "FAIL_QWEN_LOCAL_CHANGE_GOAL_IR")
                        .put("query", agentQuery)
                        .put("raw_output", completed.trace.rawOutput)
                        .put("diagnostics", org.json.JSONArray(
                            completed.codecResult.diagnostics.map { diagnostic ->
                                JSONObject()
                                    .put("code", diagnostic.code)
                                    .put("path", diagnostic.path)
                                    .put("message", diagnostic.message)
                            }
                        ))
                        .put("media_modified", false)
                        .put("published", false)
                    File(outputDir, "goal-ir-evidence.json")
                        .writeText(failureEvidence.toString(2) + "\n")
                    log("FAIL Goal IR raw=${completed.trace.rawOutput.takeLast(2000)}")
                    error(completed.codecResult.diagnostics.joinToString("; ") { it.message })
                }
                val goal = requireNotNull(completed.codecResult.goal)
                val operationRow = goal.operations.single()
                require(operationRow.kind == AgentGoalOperationKind.CHANGE_CONTENT)
                val target = operationRow.target as? AgentGoalTarget.Semantic
                    ?: error("Goal IR did not preserve a semantic target")
                require(target.description.all { it.isWhitespace() || it.code in 0x20..0x7e }) {
                    "canonical target is not ASCII"
                }
                val change = requireNotNull(LocalDeterministicChange.fromOperation(operationRow)) {
                    "Goal IR did not produce a supported typed local effect"
                }
                val content = AgentContentGraphFactory.fromState(state)
                val preflight = AgentGoalCompiler.preflight(goal, state, content)
                require(preflight is AgentCompileResult.NeedObservation)
                require(preflight.requirements.single().kind == AgentDataKind.OBJECT_TRACK)
                val evidence = JSONObject()
                    .put("schema_version", 1)
                    .put("status", "PASS_QWEN_LOCAL_CHANGE_GOAL_IR")
                    .put("query", agentQuery)
                    .put("model_trace", JSONObject()
                        .put("source", completed.trace.source)
                        .put("elapsed_ms", completed.trace.elapsedMs)
                        .put("parsed", completed.trace.parsed)
                        .put("raw_output", completed.trace.rawOutput))
                    .put("goal_id", goal.goalId)
                    .put("operation_kind", operationRow.kind.name)
                    .put("canonical_target", target.description)
                    .put("target_source_text", target.sourceText ?: JSONObject.NULL)
                    .put("effect", change.parameters())
                    .put("preflight", AgentCompileResultJson.toJson(preflight))
                    .put("media_modified", false)
                    .put("published", false)
                val targetFile = File(outputDir, "goal-ir-evidence.json")
                targetFile.writeText(evidence.toString(2) + "\n")
                log(
                    "PASS Goal IR: kind=${operationRow.kind} target=${target.description} " +
                        "effect=${change.effectKind} next=OBJECT_TRACK"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "publish_livephoto") {
                require(BuildConfig.DEBUG && userAuthorizedPublish) {
                    "diagnostic gallery publishing requires a debug build and explicit user authorization"
                }
                require(inputs.size == 1) { "publish_livephoto requires one packed Live Photo" }
                val packed = inputs.single()
                require(packed.extension.equals("jpg", true) || packed.extension.equals("jpeg", true)) {
                    "publish_livephoto requires a JPEG container"
                }
                val adapter = LivePhotoSdkAdapter(this)
                require(adapter.decode(packed, File(outputDir, "publish-decode-probe")) != null) {
                    "refusing to publish a Live Photo that cannot be decoded"
                }
                val displayName = outputName?.takeIf { it.isNotBlank() }
                    ?: "tracked-tint-${System.currentTimeMillis()}.jpg"
                val published = publishLivePhoto(packed, displayName)
                log(
                    "PASS Gallery publish: uri=$published name=$displayName " +
                        "bytes=${packed.length()}"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "livephoto_pack") {
                require(inputs.size >= 2) { "livephoto_pack requires image,video" }
                require(photoTimestampUs >= 0) { "photo timestamp cannot be negative" }
                val adapter = LivePhotoSdkAdapter(this)
                val packed = adapter.pack(inputs[0], inputs[1], outputDir, photoTimestampUs)
                val decoded = adapter.decode(packed, File(outputDir, "decoded-probe"))
                require(decoded != null) {
                    "packed output could not be decoded"
                }
                require(decoded.photoTimestampUs == photoTimestampUs) {
                    "photo timestamp changed: expected=$photoTimestampUs actual=${decoded.photoTimestampUs}"
                }
                log(
                    "PASS LivePhotoSDK pack/decode: ${packed.absolutePath} " +
                        "(${packed.length()} bytes) timestamp_us=${decoded.photoTimestampUs}"
                )
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            if (operation == "brighten" || operation == "sharpen" ||
                operation == "denoise" || operation == "color_tone" ||
                operation == "portrait_beauty" ||
                (operation == "video_filter" && inputs[0].extension.lowercase() in setOf("jpg", "jpeg", "png"))) {
                val result = VivoImageSdkExecutor.execute(
                    this, "headless-image-probe", operation, inputs[0].absolutePath,
                    imageParams, outputDir
                ) { percent, message -> log("进度 $percent% $message") }
                log("PASS image adapter: ${result.output.absolutePath} (${result.output.length()} bytes)")
                log("=== NATIVE ADAPTER TEST END ===")
                stopSelf()
                return
            }
            val result = VivoSdkExecutor.execute(
                this,
                "headless-native-probe",
                operation,
                inputs.map { it.absolutePath },
                0L,
                1000L,
                layout,
                1080,
                1920,
                outputDir,
                layoutContext
            ) { percent, message -> log("进度 $percent% $message") }
            log("PASS native adapter: ${result.output.absolutePath} (${result.output.length()} bytes)")
        }.onFailure { error ->
            log("FAIL native adapter: ${error::class.java.simpleName}: ${error.message}")
        }
        log("=== NATIVE ADAPTER TEST END ===")
        stopSelf()
    }

    private fun prepareGoalProbeAsset(
        packed: File,
        outputDir: File,
        directoryName: String
    ): PreparedAsset {
        val decoded = requireNotNull(
            LivePhotoSdkAdapter(this).decode(packed, File(outputDir, directoryName))
        ) { "input is not a decodable Live Photo" }
        return PreparedAsset(
            assetId = "goal-ir-probe-live",
            sourceUri = Uri.parse("content://com.vivo.lpa/goal-ir-probe"),
            sourceFile = packed,
            imageFile = decoded.image,
            videoFile = decoded.video,
            metadata = JSONObject()
                .put("media_format", "livephoto")
                .put("duration_ms", videoDurationMs(decoded.video))
                .put("photo_timestamp_us", decoded.photoTimestampUs),
            semantic = JSONObject().put("ready", true)
        )
    }

    private fun releaseLocalPreviewForProbe(interaction: AndroidLocalPreviewInteraction) {
        runCatching {
            when (interaction.snapshot().lifecycle.state) {
                LocalPreviewReviewState.PENDING_REVIEW -> interaction.cancel()
                LocalPreviewReviewState.ACCEPTED_FOR_REVIEW -> interaction.undo()
                LocalPreviewReviewState.CANCELLED,
                LocalPreviewReviewState.UNDONE -> Unit
            }
            interaction.release()
        }
    }

    private fun videoDurationMs(video: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } finally {
            retriever.release()
        }
    }

    private fun publishLivePhoto(source: File, displayName: String): Uri {
        require(Regex("[A-Za-z0-9._-]+\\.(jpg|jpeg)", RegexOption.IGNORE_CASE).matches(displayName)) {
            "unsafe output name"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put("live_photo", "motionphoto00010000000000000")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, MobileAgentRunner.OUTPUT_IMAGE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("unable to create MediaStore row")
        try {
            FileInputStream(source).use { input ->
                resolver.openOutputStream(uri)?.use { output -> input.copyTo(output, 128 * 1024) }
                    ?: error("unable to open MediaStore output")
            }
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            resolver.update(uri, ContentValues().apply {
                put("live_photo", "motionphoto00010000000000000")
            }, null, null)
            val verified = resolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.RELATIVE_PATH,
                    MediaStore.Images.Media.IS_PENDING,
                    "live_photo"
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val name = cursor.getString(0)
                val size = cursor.getLong(1)
                val path = cursor.getString(2)
                val pending = cursor.getInt(3)
                val marker = cursor.getString(4).orEmpty()
                name == displayName && size == source.length() &&
                    path == MobileAgentRunner.OUTPUT_IMAGE_PATH + "/" &&
                    pending == 0 && marker.isNotBlank()
            } == true
            require(verified) { "published MediaStore row failed verification" }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun countAgentPublishedImages(): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        return contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(MobileAgentRunner.OUTPUT_IMAGE_PATH + "/"),
            null
        )?.use { it.count } ?: 0
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun copyProbeFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
        }
    }


    /**
     * 从相册取一张 live photo，拆出其中的 MP4 段作为测试输入。
     *
     * vivo live photo 是 JPEG 与 MP4 的字节拼接：JPEG 以 FFD9 (EOI) 结束，
     * 紧随其后是 MP4。MP4 的 box 结构为 [4字节长度][4字节类型]，
     * 首个 box 类型为 "ftyp"，所以 MP4 起点 = "ftyp" 出现位置 - 4。
     */
    private fun prepareInput(): File {
        val src = File("/sdcard/DCIM/Camera/115.jpg")
        require(src.exists()) { "找不到 ${src.absolutePath}" }

        val bytes = src.readBytes()
        val ftyp = indexOf(bytes, "ftyp".toByteArray())
        require(ftyp >= 4) { "未找到 ftyp，这不是 live photo（或格式不同）" }
        val mp4Start = ftyp - 4

        // 校验：MP4 前两字节应当正好是 JPEG 的 EOI 标记，确认是拼接结构
        val eoiOk = mp4Start >= 2 &&
            bytes[mp4Start - 2] == 0xFF.toByte() && bytes[mp4Start - 1] == 0xD9.toByte()
        log("拆包: 总长=${bytes.size} mp4起点=$mp4Start JPEG_EOI紧邻=$eoiOk")

        val out = File(filesDir, "in.mp4")
        out.writeBytes(bytes.copyOfRange(mp4Start, bytes.size))
        return out
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** 通路 A: VMVideoConverter —— 构造不需要 Context，纯文件到文件 */
    private fun testConverter(input: File) {
        log("")
        log("[A] VMVideoConverter (无 Context / 无 View)")

        val state = VMVideoConverter.getMediaFileState(input.absolutePath)
        log("    getMediaFileState = $state  ${constName(VMVideoConverter::class.java, state)}")

        val dst = File(filesDir, "out_converter.mp4").also { it.delete() }
        val cfg = VMConverterConfig.createDefaultConfig()
        log("    config = $cfg")

        val latch = CountDownLatch(1)
        var err: Int? = null
        var done = false
        var lastProgress = -1

        val conv = VMVideoConverter(cfg)
        val rc = conv.startVideoConvert(
            input.absolutePath, dst.absolutePath,
            object : VMConverterListener {
                override fun onCompletion() {
                    done = true; latch.countDown()
                }

                override fun onError(code: Int) {
                    err = code; latch.countDown()
                }

                override fun onProgressChanged(cur: Int, total: Int) {
                    // 只在进度跨 25% 时打点，避免刷屏
                    val p = if (total > 0) cur * 100 / total else 0
                    if (p / 25 != lastProgress / 25) log("    progress $p%")
                    lastProgress = p
                }
            }
        )
        log("    startVideoConvert 返回 = $rc")

        val fired = latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)
        report("A", fired, done, err, dst)
    }

    /** 通路 B: VideoExportEngine —— 无参构造，同样是纯文件到文件 */
    private fun testExportEngine(input: File) {
        log("")
        log("[B] VideoExportEngine (无 Context / 无 View)")

        val dst = File(filesDir, "out_engine.mp4").also { it.delete() }
        val latch = CountDownLatch(1)
        var err: Int? = null
        var done = false

        val engine = VideoExportEngine()
        log("    默认 config = ${engine.videoExportConfig}")

        val rc = engine.start(
            input.absolutePath, dst.absolutePath,
            object : VideoExportEngine.ExportListener {
                override fun onCompletion(c: VideoConverter?) {
                    done = true; latch.countDown()
                }

                override fun onError(code: Int) {
                    err = code; latch.countDown()
                }

                override fun onProgressChanged(a: Int, b: Int, c: Long) = Unit
            }
        )
        log("    start 返回 = $rc")

        val fired = latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)
        report("B", fired, done, err, dst)
    }

    /**
     * [C] VMEditor 全时间线编辑通路。
     *
     * 与 A/B 的本质区别：init() 强制要求一个 VMEditorView。这里我们构造 View
     * 但**从不 addView 到任何窗口**，用来回答「完整编辑能力能否离屏使用」。
     * 若失败，说明裁剪/滤镜/转场必须依附可见 UI，Agent 架构就得改。
     */
    private fun testEditor(input: File) {
        log("")
        log("[C] VMEditor + 未挂载 VMEditorView（无 Activity / 无窗口）")

        val dst = File(filesDir, "out_editor.mp4")
        dst.delete()

        // View 构造与 SDK init 都必须在主线程 Looper 上
        val main = Handler(Looper.getMainLooper())
        val editor = VMEditor()
        var initRet = Int.MIN_VALUE
        val initLatch = CountDownLatch(1)

        main.post {
            runCatching {
                val view = VMEditorView(this)          // 只 new，不挂载
                val clip = VMClip().apply {
                    path = input.absolutePath
                    type = VMClip.Type.values().first { it.name.contains("VIDEO", true) }
                    startTimeMs = 0
                    durationMs = 1000               // 只取前 1s，验证裁剪确实生效
                }
                val res = VMResource().apply { vmClips = listOf(clip) }
                initRet = editor.init(this, res, view)
            }.onFailure {
                log("    init 抛异常: ${it::class.java.simpleName}: ${it.message}")
            }
            initLatch.countDown()
        }
        initLatch.await(30, TimeUnit.SECONDS)
        log("    init 返回 = $initRet")
        if (initRet != 0) {
            log("    C 判定: VMEditor 无法离屏初始化 -> 完整编辑通路需要 UI")
            main.post { runCatching { editor.release() } }
            return
        }

        var fired = false
        var done = false
        var err: Int? = null
        val latch = CountDownLatch(1)
        editor.setExportListener(object : VMExportListener {
            // 注意：第一个布尔是 isError 而不是 success。
            // 依据是 SDK 内 VideoConverter$1.onEncodingDone 的日志字符串 "onEncodingDone iserror"。
            // 误读成 success 会把正常导出判成失败。
            override fun onEncodingDone(isError: Boolean, code: Int) {
                fired = true
                if (isError || code != 0) err = code else done = true
                latch.countDown()
            }

            override fun onEncodingProgress(cur: Int, total: Int) = Unit
        })

        val cfg = VMExportEncodeConfig.createDefaultConfig()
        log("    config = $cfg")
        val ret = editor.startExport(dst.absolutePath, cfg)
        log("    startExport 返回 = $ret")
        latch.await(TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        report("C", fired, done, err, dst)
        main.post { runCatching { editor.release() } }
    }

    private fun report(tag: String, fired: Boolean, done: Boolean, err: Int?, dst: File) {
        when {
            !fired -> log("    $tag 结果: 超时 ${TIMEOUT_SEC}s 无回调")
            err != null -> log("    $tag 结果: onError($err)")
            done -> log("    $tag 结果: onCompletion")
        }
        // 有回调不等于产物可用，必须验证输出确实是个 MP4
        if (dst.exists() && dst.length() > 0) {
            val head = ByteArray(12)
            val count = dst.inputStream().use { input ->
                var offset = 0
                while (offset < head.size) {
                    val read = input.read(head, offset, head.size - offset)
                    if (read <= 0) break
                    offset += read
                }
                offset
            }
            val isMp4 = count == 12 && String(head, 4, 4) == "ftyp"
            log("    $tag 产物: ${dst.length()} bytes, ftyp=$isMp4")
            log("    $tag 判定: ${if (isMp4 && done) "无头渲染可行" else "产物异常"}")
        } else {
            log("    $tag 产物: 不存在 -> 无头渲染不可行")
        }
    }

    /** 把返回码翻译成 SDK 里的常量名，便于判读 */
    private fun constName(cls: Class<*>, value: Int): String =
        cls.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .mapNotNull { f ->
                runCatching { f.isAccessible = true; f.getInt(null) }
                    .getOrNull()?.let { if (it == value) f.name else null }
            }
            .joinToString("/")
            .ifEmpty { "(未知常量)" }
}
