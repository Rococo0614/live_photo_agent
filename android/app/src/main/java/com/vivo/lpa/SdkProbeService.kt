package com.vivo.lpa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.IBinder
import android.util.Log
import com.vivo.imageprocess.ImageProcessOffscreenRender
import com.vivo.imageprocess.ImageProcessRenderEngine
import com.vivo.mediaeffectsdk.motioneffect.api.VMEditMotionEffectEngine
import com.vivo.mediaeffectsdk.motioneffect.api.entity.input.VMInputTexture
import com.vivo.mediaeffectsdk.motioneffect.base.input.VMTextStickerData
import com.vivo.mediaeffectsdk.motioneffect.core.render.gpufilters.livephoto.GPUMattingFilter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Headless feasibility probe for the not-yet-wired mediaeffectsdk capabilities.
 *
 * Four paths, each answering one architecture question before any of them is
 * allowed into the agent tool chain:
 *  D ImageProcessOffscreenRender —— bitmap-to-bitmap effect route, no window
 *  E processSegmentPoints         —— can a person mask be produced on device
 *  F VMEditMotionEffectEngine     —— edit-engine init + texture round trip on a
 *                                     self-made EGL pbuffer (no VMEditorView)
 *  G GPUMattingFilter             —— mask+main composite on the same pbuffer
 *
 * Trigger: adb shell am start-foreground-service -n com.vivo.lpa/.SdkProbeService
 */
class SdkProbeService : Service() {

    companion object {
        private const val TAG = "LPA_PROBE2"
    }

    private data class OwlV2InputProbePaths(
        val vocabulary: String,
        val merges: String,
        val pixelReference: String
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        val coverPath = intent?.getStringExtra("cover_path")
        val personPath = intent?.getStringExtra("person_path")
        val maskPath = intent?.getStringExtra("mask_path")
        val localPreviewOnly = intent?.getBooleanExtra("local_preview_only", false) == true
        val owlV2InputPaths = listOf(
            intent?.getStringExtra("owlv2_vocab_path"),
            intent?.getStringExtra("owlv2_merges_path"),
            intent?.getStringExtra("owlv2_pixel_reference_path")
        ).takeIf { values -> values.all { !it.isNullOrBlank() } }?.let { values ->
            OwlV2InputProbePaths(values[0]!!, values[1]!!, values[2]!!)
        }
        Thread(
            { runProbes(coverPath, personPath, maskPath, localPreviewOnly, owlV2InputPaths) },
            "lpa-probe2"
        ).start()
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        val channel = "lpa_probe2"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channel, "LPA Probe", NotificationManager.IMPORTANCE_LOW))
        startForeground(2, Notification.Builder(this, channel)
            .setContentTitle("LPA SDK 探针运行中")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build())
    }

    private fun log(message: String) = Log.i(TAG, message)

    private fun outDir(): File = File(filesDir, "probe2").apply { mkdirs() }

    private fun runProbes(
        coverPath: String?,
        personPath: String?,
        maskPath: String?,
        localPreviewOnly: Boolean,
        owlV2InputPaths: OwlV2InputProbePaths?
    ) {
        log("=== SDK PROBE BEGIN ===")
        val coverFile = resolveCover(coverPath)
        if (coverFile == null) {
            log("FAIL 找不到可用的封面图输入")
            log("=== SDK PROBE END ===")
            stopSelf()
            return
        }
        val cover = BitmapFactory.decodeFile(coverFile.absolutePath)
        if (cover == null) {
            log("FAIL 封面解码失败: ${coverFile.absolutePath}")
            log("=== SDK PROBE END ===")
            stopSelf()
            return
        }
        log("输入封面: ${coverFile.absolutePath} ${cover.width}x${cover.height}")
        if (localPreviewOnly) {
            val externalMask = maskPath?.let { path ->
                File(path).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }
            }
            val latch = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { probeH(cover, externalMask) }
                    .onFailure { log("H 抛异常: ${it::class.java.simpleName}: ${it.message}") }
                runCatching { probeI(cover, externalMask) }
                    .onFailure { log("I 抛异常: ${it::class.java.simpleName}: ${it.message}") }
                owlV2InputPaths?.let { paths ->
                    runCatching { probeJ(cover, paths) }
                        .onFailure { log("J 抛异常: ${it::class.java.simpleName}: ${it.message}") }
                }
                latch.countDown()
            }
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
            log("=== SDK PROBE END ===")
            stopSelf()
            return
        }

        // The videoeditorsdk VMEditor only inits on the main Looper (see
        // headless_render_findings.md). The image render and the edit engine may share that
        // constraint, so D and F run on the main Looper while E stays on this worker thread.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        runCatching { probeD(cover, main) }.onFailure { log("D 抛异常: ${it::class.java.simpleName}: ${it.message}") }
        // Segmentation is person-oriented: feed it a photo that actually contains people,
        // otherwise the probe would measure the wrong input rather than the SDK.
        val person = personPath?.let { path ->
            File(path).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        }
        val maskInput = person ?: cover
        log("E 输入: ${if (person != null) "person_path=$personPath" else "回退到封面图（无人像，分割可能为 0）"}")
        val mask = runCatching { probeE(maskInput) }
            .onFailure { log("E 抛异常: ${it::class.java.simpleName}: ${it.message}") }
            .getOrNull()
        val externalMask = maskPath?.let { path ->
            File(path).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        }
        val fgLatch = java.util.concurrent.CountDownLatch(1)
        main.post {
            runCatching { probeFAndG(cover, mask) }
                .onFailure { log("F/G 抛异常: ${it::class.java.simpleName}: ${it.message}") }
            runCatching { probeH(cover, externalMask ?: mask) }
                .onFailure { log("H 抛异常: ${it::class.java.simpleName}: ${it.message}") }
            runCatching { probeI(cover, externalMask ?: mask) }
                .onFailure { log("I 抛异常: ${it::class.java.simpleName}: ${it.message}") }
            owlV2InputPaths?.let { paths ->
                runCatching { probeJ(cover, paths) }
                    .onFailure { log("J 抛异常: ${it::class.java.simpleName}: ${it.message}") }
            }
            fgLatch.countDown()
        }
        fgLatch.await(60, java.util.concurrent.TimeUnit.SECONDS)

        log("=== SDK PROBE END ===")
        stopSelf()
    }

    /** Cover of any prepared asset in cache, else the intent-provided path. */
    private fun resolveCover(coverPath: String?): File? {
        coverPath?.let { path ->
            File(path).takeIf { it.isFile }?.let { return it }
        }
        val root = File(cacheDir, "agent-inputs")
        root.listFiles()?.sortedBy { it.name }?.forEach { assetDir ->
            File(assetDir, "cover.jpg").takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    /**
     * D: bitmap in, effect applied, bitmap out —— the headless image route.
     *
     * Calls are posted to [main] because the engine may only start its render thread from the
     * main Looper; the worker polls the target files instead of blocking on a latch, since the
     * save callback would also arrive on [main] and a latch there would deadlock itself.
     */
    private fun probeD(cover: Bitmap, main: android.os.Handler) {
        log("")
        log("[D] ImageProcessOffscreenRender (bitmap -> effect -> file, 主 Looper)")
        val render = ImageProcessOffscreenRender()
        val plain = File(outDir(), "d_plain.png").also { it.delete() }
        val effected = File(outDir(), "d_sharpen.png").also { it.delete() }
        main.post {
            runCatching {
                render.setRenderSource(cover, 0, 0, cover.width, cover.height)
                render.notifySaveImage(plain.absolutePath, null, 0f, 0f, 1f, 1f)
            }.onFailure { log("    D 直通调用抛异常: ${it.message}") }
        }
        val plainSaved = waitFile(plain, 20_000)
        log("    D 直通产物: ${plain.length()} bytes exists=${plain.isFile} 等到=$plainSaved")
        main.post {
            runCatching {
                render.setRenderSource(cover, 0, 0, cover.width, cover.height)
                render.setImageFilter(com.vivo.imageprocess.FilterType.FILTER_TYPE_SHARPEN, false, null, 0, 0, null, 0, 0, 1f)
                render.notifySetEffects()
                render.notifySaveImage(effected.absolutePath, null, 0f, 0f, 1f, 1f)
            }.onFailure { log("    D 滤镜调用抛异常: ${it.message}") }
        }
        val effectedSaved = waitFile(effected, 20_000)
        log("    D 滤镜产物: ${effected.length()} bytes exists=${effected.isFile} 等到=$effectedSaved")
        log("    D 判定: ${if (effected.isFile && effected.length() > 0) "离屏图像通路可行" else "离屏图像通路不可行"}")
    }

    private fun waitFile(target: File, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (target.isFile && target.length() > 0) return true
            Thread.sleep(200)
        }
        return target.isFile && target.length() > 0
    }

    /** E: native person segmentation —— the mask source matting depends on. */
    private fun probeE(cover: Bitmap): Bitmap? {
        log("")
        log("[E] analyze -> processSegmentPoints (native mask chain)")
        // Bare processSegmentPoints returned 0 boxes in ~1ms, which smells like a missing
        // analyze step rather than "no person": the engine exposes an analyzer-size query and
        // an analyze-data producer, so feed the segment call its analyze bitmap.
        val sizeParam = ImageProcessRenderEngine.AnalyzeSizeParam()
        ImageProcessRenderEngine.nativeGetAnalyzerSize(cover.width, cover.height, sizeParam)
        log("    E analyzer 尺寸: $sizeParam")
        val analyzer = if (sizeParam.width > 0 && sizeParam.height > 0) {
            Bitmap.createBitmap(sizeParam.width, sizeParam.height, Bitmap.Config.ARGB_8888)
        } else null
        if (analyzer != null) {
            val analyzeRet = ImageProcessRenderEngine.nativeProcessAnalyzeData(cover, analyzer)
            log("    E nativeProcessAnalyzeData 返回 = $analyzeRet")
        } else {
            log("    E analyzer 尺寸解析失败，退回裸调用")
        }
        val boxes = ImageProcessRenderEngine.processSegmentPoints(cover, analyzer, cover.width, cover.height)
        log("    E 分割框数量: ${boxes?.size ?: 0}")
        val withMask = boxes?.firstOrNull { it.partMask != null }
        if (withMask == null) {
            log("    E 判定: 无可用 partMask -> 分割不可用，matting 需外部 mask")
            return null
        }
        log("    E 首个框: id=${withMask.id} 占比=${withMask.proportion} mask=${withMask.partMask.width}x${withMask.partMask.height}")
        val maskFile = File(outDir(), "e_mask.png")
        withMask.partMask.compress(Bitmap.CompressFormat.PNG, 100, maskFile.outputStream())
        log("    E 判定: 分割可用，mask 已存 ${maskFile.absolutePath}")
        return withMask.partMask
    }

    /** F/G: edit engine + matting composite on one self-made EGL pbuffer. */
    private fun probeFAndG(cover: Bitmap, mask: Bitmap?) {
        log("")
        log("[F] VMEditMotionEffectEngine (init + text sticker + processTexture, 自建 EGL pbuffer)")
        val egl = EglPbuffer(cover.width, cover.height)
        if (!egl.ready) {
            log("    F 判定: EGL pbuffer 创建失败，GPU 通路无从验证")
            return
        }
        log("    EGL 就绪: ${cover.width}x${cover.height} pbuffer")
        val mainTex = egl.upload(cover)
        log("    主纹理 id=$mainTex")

        val engine = VMEditMotionEffectEngine()
        val initRet = engine.init(applicationContext)
        log("    engine.init 返回 = $initRet")
        if (initRet == 0) {
            engine.setOutputSize(cover.width, cover.height)
            // The AAR ships colour-cube LUTs; a filter is the cheapest effect that proves
            // the render graph actually runs on our pbuffer.
            val filterSet = runCatching { engine.setFilter(assets, "cube/p3to2020.bin") }
                .onFailure { log("    setFilter(cube) 失败: ${it.message}") }
                .getOrDefault(false)
            log("    setFilter(cube/p3to2020.bin) = $filterSet")
            val sticker = VMTextStickerData().apply { fillDefaultContents("LPA") }
            runCatching { engine.activeTextStickerView(sticker, true) }
                .onFailure { log("    文字贴纸激活失败: ${it.message}") }
            val input = VMInputTexture(mainTex, cover.width, cover.height, false, 0L)
            val processRet = engine.processTexture(input) { _, _ -> }
            log("    processTexture#1 返回 = $processRet dest=${input.desTextureId}")
            // The first call may only build the render graph; feed a second frame before
            // judging whether the engine renders at all.
            val input2 = VMInputTexture(mainTex, cover.width, cover.height, false, 33L)
            val processRet2 = engine.processTexture(input2) { _, _ -> }
            log("    processTexture#2 返回 = $processRet2 dest=${input2.desTextureId} contains=${input2.containsDesTexture()}")
            log("    活跃贴纸数 = ${engine.activeEditStickerList?.size ?: -1}")
            val dest = if (input2.containsDesTexture()) input2.desTextureId else input.desTextureId
            log("    输出纹理 id=$dest contains=${input2.containsDesTexture() || input.containsDesTexture()}")
            if (dest > 0) {
                val file = File(outDir(), "f_edit_engine.png")
                egl.readBack(dest, cover.width, cover.height, file)
                log("    F 产物: ${file.length()} bytes -> ${if (file.length() > 0) "编辑引擎离屏可行" else "回读为空"}")
            } else {
                log("    F 判定: 无输出纹理（文字贴纸可能未参与离屏渲染）")
            }
            engine.release()
        } else {
            log("    F 判定: 编辑引擎初始化失败 -> 需要可见 UI 或其他条件")
        }

        // A synthetic centre rectangle still exercises the composite shader: the question G
        // answers is whether the filter renders on our pbuffer at all, not whether a real
        // segmentation exists (that is E's job).
        val maskForG = mask ?: Bitmap.createBitmap(cover.width, cover.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.TRANSPARENT)
            canvas.drawRect(
                android.graphics.RectF(cover.width * 0.25f, cover.height * 0.25f, cover.width * 0.75f, cover.height * 0.75f),
                android.graphics.Paint().apply { color = android.graphics.Color.WHITE }
            )
        }
        run {
            log("")
            log("[G] GPUMattingFilter (main + ${if (mask != null) "真 mask" else "合成 mask"} 合成, 同一 pbuffer)")
            val matting = GPUMattingFilter()
            val mattingInit = matting.init()
            log("    matting.init = $mattingInit")
            if (mattingInit) {
                // process() NPE'd on a null VertexBufferObject; the base filter builds its
                // buffers in onInit(), which init() evidently does not call for this subclass.
                val onInitRet = runCatching { matting.onInit() }
                    .onFailure { log("    matting.onInit 抛异常: ${it.message}") }
                    .getOrDefault(false)
                log("    matting.onInit = $onInitRet")
                matting.onOutputSizeChanged(cover.width, cover.height)
                // The base GPUFilter builds its vertex buffers in the onDrawFrame path (the
                // pattern MotionTimelineEffect already proves headless), so prime it exactly
                // that way before the matting composite pass.
                val destOfPrime = matting.onDrawFrame(mainTex, buffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)),
                    buffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)))
                log("    onDrawFrame 预热返回纹理 id=$destOfPrime")
                val maskTex = egl.upload(maskForG)
                val dest = matting.process(mainTex, maskTex, 0, cover.width, cover.height)
                log("    process 返回纹理 id=$dest")
                if (dest > 0) {
                    val file = File(outDir(), "g_matting.png")
                    egl.readBack(dest, cover.width, cover.height, file)
                    log("    G 产物: ${file.length()} bytes -> ${if (file.length() > 0) "matting 合成离屏可行" else "回读为空"}")
                } else {
                    log("    G 判定: process 未产出纹理")
                }
                matting.release()
            }
        }
        egl.destroy()
    }

    /** H: preview-only mask-scoped GLES color and blur bridge with CPU-oracle differential. */
    private fun probeH(cover: Bitmap, suppliedMask: Bitmap?) {
        log("")
        log("[H] Local deterministic mask preview (GLES color/blur vs CPU oracle)")
        val mask = normalizeMask(cover, suppliedMask)
        val cases = JSONArray()
        cases.put(runPreviewDifferential(
            caseId = "real_full_frame_color",
            source = cover,
            mask = mask,
            change = LocalDeterministicChange.ColorTint("#22AA66", 0.65),
            outputName = "h_color_preview.png",
            referenceName = "h_color_cpu_reference.png"
        ))
        cases.put(runPreviewDifferential(
            caseId = "real_full_frame_blur_r6",
            source = cover,
            mask = mask,
            change = LocalDeterministicChange.Blur(6.0),
            outputName = "h_blur_preview.png",
            referenceName = "h_blur_cpu_reference.png"
        ))

        val (syntheticSource, syntheticMask) = asymmetricSoftMaskFixture()
        File(outDir(), "h_asymmetric_source.png").outputStream().use {
            syntheticSource.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        File(outDir(), "h_asymmetric_soft_mask.png").outputStream().use {
            syntheticMask.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        cases.put(runPreviewDifferential(
            "asymmetric_soft_color",
            syntheticSource,
            syntheticMask,
            LocalDeterministicChange.ColorTint("#D13F9A", 0.37),
            outputName = "h_asymmetric_color_preview.png",
            referenceName = "h_asymmetric_color_cpu_reference.png"
        ))
        listOf(
            Triple(0.5, 1, "r1_from_0_5"),
            Triple(6.4, 6, "r6_from_6_4"),
            Triple(63.6, 64, "r64_from_63_6")
        ).forEach { (radius, effectiveRadius, label) ->
            cases.put(runPreviewDifferential(
                "asymmetric_soft_blur_$label",
                syntheticSource,
                syntheticMask,
                LocalDeterministicChange.Blur(radius),
                outputName = "h_asymmetric_blur_${effectiveRadius}_preview.png",
                referenceName = "h_asymmetric_blur_${effectiveRadius}_cpu_reference.png"
            ))
        }
        syntheticSource.recycle()
        syntheticMask.recycle()

        val allPassed = (0 until cases.length()).all { cases.getJSONObject(it).getBoolean("passed") }
        val evidence = JSONObject()
            .put("schema_version", 1)
            .put("status", if (allPassed) "PASS_READ_ONLY_PREVIEW" else "FAIL")
            .put("preview_only", true)
            .put("writeback", false)
            .put("source_media_modified", false)
            .put("production_executor_registered", false)
            .put("cases", cases)
        val evidenceFile = File(outDir(), "h_local_preview_evidence.json")
        evidenceFile.writeText(evidence.toString(2) + "\n")
        log("    H status=${evidence.getString("status")} cases=${cases.length()} evidence=${evidenceFile.length()} bytes")
        check(allPassed) { "local deterministic preview differential failed" }
    }

    private fun runPreviewDifferential(
        caseId: String,
        source: Bitmap,
        mask: Bitmap,
        change: LocalDeterministicChange,
        outputName: String? = null,
        referenceName: String? = null
    ): JSONObject {
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        val cpuStarted = android.os.SystemClock.elapsedRealtimeNanos()
        val expected = AndroidLocalMaskPreviewRenderer.renderCpu(source, mask, change)
        val cpuElapsedMs =
            (android.os.SystemClock.elapsedRealtimeNanos() - cpuStarted) / 1_000_000.0
        val rendererStarted = android.os.SystemClock.elapsedRealtimeNanos()
        val actual = AndroidLocalMaskPreviewRenderer.render(source, mask, change)
        val rendererElapsedMs =
            (android.os.SystemClock.elapsedRealtimeNanos() - rendererStarted) / 1_000_000.0
        val differential = bitmapDifferential(source, mask, expected, actual.bitmap)
        outputName?.let { name ->
            File(outDir(), name).outputStream().use {
                actual.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        referenceName?.let { name ->
            File(outDir(), name).outputStream().use {
                expected.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val expectedBackend = when (change) {
            is LocalDeterministicChange.ColorTint ->
                AndroidLocalMaskPreviewRenderer.BACKEND_GLES_COLOR_TINT
            is LocalDeterministicChange.Blur ->
                AndroidLocalMaskPreviewRenderer.BACKEND_GLES_BOX_BLUR
        }
        val passed = actual.backendId == expectedBackend &&
            differential.outsideChanged == 0 &&
            differential.maxChannelDifference <= 1 &&
            differential.maxAlphaDifference == 0
        val probeElapsedMs =
            (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        log(
            "    H case=$caseId backend=${actual.backendId} outside_changed=${differential.outsideChanged} " +
                "max_rgb_diff=${differential.maxChannelDifference} " +
                "max_alpha_diff=${differential.maxAlphaDifference} " +
                "mean_abs=${"%.6f".format(differential.meanAbsoluteDifference)} " +
                "renderer_ms=${"%.2f".format(rendererElapsedMs)} pass=$passed"
        )
        val result = JSONObject()
            .put("case_id", caseId)
            .put("effect_kind", change.effectKind)
            .put("parameters", change.parameters())
            .put("width", source.width)
            .put("height", source.height)
            .put("backend", actual.backendId)
            .put("expected_backend", expectedBackend)
            .put("outside_mask_changed_pixels", differential.outsideChanged)
            .put("max_rgb_channel_difference_vs_cpu", differential.maxChannelDifference)
            .put("max_alpha_difference_vs_source", differential.maxAlphaDifference)
            .put("mean_absolute_rgb_difference_vs_cpu", differential.meanAbsoluteDifference)
            .put("differing_pixels_vs_cpu", differential.differingPixels)
            .put("cpu_reference_ms", cpuElapsedMs)
            .put("renderer_ms", rendererElapsedMs)
            .put("probe_elapsed_ms", probeElapsedMs)
            .put("passed", passed)
        actual.bitmap.recycle()
        expected.recycle()
        return result
    }

    private fun asymmetricSoftMaskFixture(): Pair<Bitmap, Bitmap> {
        val width = 97
        val height = 73
        val source = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val red = (x * 29 + y * 11 + 17) and 0xff
            val green = (x * 7 + y * 43 + 91) and 0xff
            val blue = (x * 53 + y * 3 + 37) and 0xff
            0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
        val mask = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val value = when {
                x == 0 && y in 2..31 -> (31 + y * 7).coerceAtMost(254)
                y == height - 1 && x in 48 until width -> 255
                x in 11..79 && y in 7..58 && (x * 3 + y * 5) % 11 < 7 ->
                    ((x * 23 + y * 31) % 254) + 1
                x in 83..94 && y in 1..9 -> 173
                else -> 0
            }
            0xff000000.toInt() or (value shl 16) or (value shl 8) or value
        }
        return Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888) to
            Bitmap.createBitmap(mask, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun normalizeMask(cover: Bitmap, supplied: Bitmap?): Bitmap {
        val source = supplied ?: Bitmap.createBitmap(
            cover.width,
            cover.height,
            Bitmap.Config.ARGB_8888
        ).also { bitmap ->
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawRect(
                android.graphics.RectF(
                    cover.width * 0.25f,
                    cover.height * 0.25f,
                    cover.width * 0.75f,
                    cover.height * 0.75f
                ),
                android.graphics.Paint().apply { color = android.graphics.Color.WHITE }
            )
        }
        return if (source.width == cover.width && source.height == cover.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, cover.width, cover.height, false)
        }
    }

    private data class BitmapDifferential(
        val outsideChanged: Int,
        val maxChannelDifference: Int,
        val maxAlphaDifference: Int,
        val meanAbsoluteDifference: Double,
        val differingPixels: Int
    )

    private fun bitmapDifferential(
        source: Bitmap,
        mask: Bitmap,
        expected: Bitmap,
        actual: Bitmap
    ): BitmapDifferential {
        val sourcePixels = pixels(source)
        val maskPixels = pixels(mask)
        val expectedPixels = pixels(expected)
        val actualPixels = pixels(actual)
        var outsideChanged = 0
        var maxDifference = 0
        var maxAlphaDifference = 0
        var totalDifference = 0L
        var channelCount = 0L
        var differingPixels = 0
        for (index in actualPixels.indices) {
            val masked = (maskPixels[index] ushr 16 and 0xff) > 0
            if (!masked && sourcePixels[index] != actualPixels[index]) outsideChanged += 1
            if (expectedPixels[index] != actualPixels[index]) differingPixels += 1
            maxAlphaDifference = maxOf(
                maxAlphaDifference,
                kotlin.math.abs(
                    (sourcePixels[index] ushr 24 and 0xff) -
                        (actualPixels[index] ushr 24 and 0xff)
                )
            )
            for (shift in intArrayOf(16, 8, 0)) {
                val expectedChannel = expectedPixels[index] ushr shift and 0xff
                val actualChannel = actualPixels[index] ushr shift and 0xff
                val difference = kotlin.math.abs(expectedChannel - actualChannel)
                maxDifference = maxOf(maxDifference, difference)
                totalDifference += difference
                channelCount += 1
            }
        }
        return BitmapDifferential(
            outsideChanged,
            maxDifference,
            maxAlphaDifference,
            totalDifference.toDouble() / channelCount,
            differingPixels
        )
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    /** I: typed observation materialization, DAG execution and review lifecycle, still no writeback. */
    private fun probeI(cover: Bitmap, suppliedMask: Bitmap?) {
        log("")
        log("[I] Android shadow observation bridge + temporary preview lifecycle")
        val sourceAssetId = "shadow-source-1"
        val targetText = "画面中的猫"
        val state = AgentRuntimeState(null, "turn-local-shadow-probe", "把画面中的猫调成绿色")
        val fingerprint = AgentStateFingerprint.of(state)
        val graph = AgentContentGraphFactory.fromDescriptors(
            graphId = "content-local-shadow-probe",
            version = 0,
            descriptors = listOf(ContentAssetDescriptor(
                sourceAssetId,
                "image",
                hasImageTrack = true,
                hasVideoTrack = false
            ))
        )
        val snapshot = AgentContentGraphSnapshot(
            graph,
            AgentContentGraphValidator.validate(graph),
            fingerprint
        )
        val operation = AgentGoalOperation(
            operationId = "change-cat-color",
            kind = AgentGoalOperationKind.CHANGE_CONTENT,
            scope = AgentGoalScope.CurrentAssets,
            target = AgentGoalTarget.Semantic(targetText),
            parameters = mapOf(
                "effect_kind" to AgentGoalArgument(
                    AgentGoalValue.Text("color_tint"), AgentValueProvenance.MODEL_INFERRED
                ),
                "color_hex" to AgentGoalArgument(
                    AgentGoalValue.Text("#22AA66"), AgentValueProvenance.MODEL_INFERRED
                ),
                "intensity" to AgentGoalArgument(
                    AgentGoalValue.Number(0.65), AgentValueProvenance.MODEL_INFERRED
                )
            )
        )
        val semanticGoal = AgentGoalIr(
            goalId = "goal-local-shadow-probe",
            userGoal = state.originalQuery,
            stateFingerprint = fingerprint,
            operations = listOf(operation)
        )
        val locatorResult = OwlV2GroundingPostprocessor.process(
            canonicalQuery = targetText,
            sourceText = semanticGoal.userGoal,
            stateFingerprint = fingerprint,
            imageWidth = cover.width,
            imageHeight = cover.height,
            boxesXyxy = floatArrayOf(100f, 100f, 500f, 500f),
            scores = floatArrayOf(0.9f),
            labels = intArrayOf(0)
        )
        val objectTrack = when (val result = AgentShadowObservationBridge.materializeObjectTrack(
            snapshot,
            locatorResult.toShadowObservation(operation.operationId, sourceAssetId, 0)
        )) {
            is ShadowObjectTrackResult.Materialized -> result.track
            else -> error("unique target did not materialize: $result")
        }
        val normalizedMask = normalizeMask(cover, suppliedMask)
        val maskPixels = pixels(normalizedMask)
        val maskValues = ByteArray(maskPixels.size) { index ->
            (maskPixels[index] ushr 16 and 0xff).toByte()
        }
        val maskTrack = AgentShadowObservationBridge.materializeMaskTrack(
            objectTrack,
            expectedFrameCount = 1,
            frames = listOf(ShadowMaskFrame.create(
                frameIndex = 0,
                timestampMs = 0,
                width = cover.width,
                height = cover.height,
                maskValues = maskValues
            )),
            validationPolicy = TrackValidationPolicy("candidate-v1", 0.10)
        )
        val materialized = AgentShadowObservationBridge.finalizeForCompiler(
            semanticGoal,
            maskTrack
        )
        val compileResult = AgentGoalCompiler.preflight(
            materialized.goal,
            state,
            materialized.snapshot,
            materialized.compilerContext
        ) as? AgentCompileResult.Ready ?: error("materialized preview did not compile")

        var logicalClock = 1_000L
        val store = AndroidTemporaryLocalPreviewStore { logicalClock++ }
        lateinit var interaction: AndroidLocalPreviewInteraction
        val sourceBefore = bitmapPixelSha256(cover)
        val execution = AgentDagExecutionEngine.runToTerminal(
            compileResult.compilation,
            AndroidLocalPreviewShadowExecutor(
                materialization = materialized,
                sourceFrames = AndroidShadowSourceFrameProvider { assetId, frameIndex, timestampMs ->
                    AndroidShadowSourceFrame(assetId, frameIndex, timestampMs, cover)
                },
                previewStore = store,
                currentStateFingerprint = { fingerprint },
                onPreviewReady = { interaction = it }
            )
        )
        check(execution.runState.status == AgentDagRunStatus.FINISHED)
        val outputArtifactId = (execution.values.values.flatten().single() as
            AgentDagRuntimeValue.Artifact).artifactId
        check(outputArtifactId == interaction.artifactId)
        val pending = interaction.snapshot()
        check(pending.rendererBackendId == AndroidLocalMaskPreviewRenderer.BACKEND_GLES_COLOR_TINT)
        File(outDir(), "i_shadow_bridge_preview.png").outputStream().use {
            pending.previewBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val accepted = interaction.confirm()
        val undone = interaction.undo()
        check(!accepted.lifecycle.writebackAuthorized && !undone.lifecycle.writebackAuthorized)
        interaction.release()
        check(store.activeArtifactIds().isEmpty())
        val sourceAfter = bitmapPixelSha256(cover)
        check(sourceBefore == sourceAfter)

        val forbiddenAttributes = materialized.snapshot.graph.nodes.values
            .flatMap { it.attributes.keys }
            .map(String::lowercase)
            .filter { it.contains("path") || it.contains("box") || it.contains("point") }
        check(forbiddenAttributes.isEmpty())
        val evidence = JSONObject()
            .put("schema_version", 1)
            .put("status", "PASS_READ_ONLY_SHADOW_BRIDGE")
            .put("user_target_input", "language_only")
            .put("locator_disposition", locatorResult.disposition.name)
            .put("locator_evidence_fingerprint", locatorResult.evidenceFingerprint)
            .put("object_track_materialized", true)
            .put("mask_track_materialized", true)
            .put("content_graph_version", materialized.snapshot.graph.version)
            .put("certificate_ready_for_preview", materialized.certificate.readyForPreview(fingerprint))
            .put("certificate_ready_for_mutation", materialized.certificate.readyForMutation(fingerprint))
            .put("certificate_fingerprint", materialized.certificate.evidenceFingerprint)
            .put("track_observation_statuses", JSONArray(
                materialized.certificate.observations.map { it.status.name }
            ))
            .put("dag_status", execution.runState.status.name)
            .put("executed_node_ids", JSONArray(execution.executedNodeIds))
            .put("artifact_id", outputArtifactId)
            .put("renderer_backend", pending.rendererBackendId)
            .put("review_lifecycle", JSONArray(listOf(
                pending.lifecycle.state.name,
                accepted.lifecycle.state.name,
                undone.lifecycle.state.name
            )))
            .put("writeback_authorized", false)
            .put("source_pixel_sha256_before", sourceBefore)
            .put("source_pixel_sha256_after", sourceAfter)
            .put("source_media_modified", false)
            .put("published", false)
            .put("production_executor_registered", false)
            .put("temporary_store_empty_after_undo", store.activeArtifactIds().isEmpty())
            .put("content_graph", AgentContentGraphJson.toJson(materialized.snapshot))
            .put("compiled_dag", AgentDagJson.toJson(compileResult.compilation))
        val evidenceFile = File(outDir(), "i_shadow_bridge_evidence.json")
        evidenceFile.writeText(evidence.toString(2) + "\n")
        log(
            "    I status=${evidence.getString("status")} graph_version=${materialized.snapshot.graph.version} " +
                "backend=${pending.rendererBackendId} lifecycle=" +
                "${pending.lifecycle.state}->${accepted.lifecycle.state}->${undone.lifecycle.state}"
        )
        log("    I source_unchanged=${sourceBefore == sourceAfter} evidence=${evidenceFile.length()} bytes")
    }

    private fun bitmapPixelSha256(bitmap: Bitmap): String {
        val buffer = ByteBuffer.allocate(bitmap.width * bitmap.height * 4)
        pixels(bitmap).forEach(buffer::putInt)
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(buffer.array())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** J: Android CLIP BPE and OWLv2 image input differential against fixed Python tensors. */
    private fun probeJ(cover: Bitmap, paths: OwlV2InputProbePaths) {
        log("")
        log("[J] OWLv2 Android tokenizer + 960 image preprocessing differential")
        val vocabulary = File(paths.vocabulary)
        val merges = File(paths.merges)
        val pixelReference = File(paths.pixelReference)
        require(vocabulary.isFile && merges.isFile && pixelReference.isFile)
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        val tokenizer = OwlV2ClipTokenizer.fromFiles(vocabulary, merges)
        val broad = tokenizer.encode("a cat")
        val precise = tokenizer.encode("an orange cat walking on green grass")
        val expectedBroad = intArrayOf(49406, 320, 2368, 49407) + IntArray(12)
        val expectedPrecise = intArrayOf(
            49406, 550, 4287, 2368, 3941, 525, 1901, 5922, 49407
        ) + IntArray(7)
        check(broad.inputIds.contentEquals(expectedBroad))
        check(precise.inputIds.contentEquals(expectedPrecise))

        val sourceBefore = bitmapPixelSha256(cover)
        val actual = OwlV2ImagePreprocessor.preprocess(cover)
        val reference = readFloatRaw(pixelReference, actual.size)
        var maximumAbsoluteError = 0.0
        var totalAbsoluteError = 0.0
        var squaredError = 0.0
        var peak = 1.0
        for (index in actual.indices) {
            val difference = kotlin.math.abs(actual[index].toDouble() - reference[index])
            maximumAbsoluteError = maxOf(maximumAbsoluteError, difference)
            totalAbsoluteError += difference
            squaredError += difference * difference
            peak = maxOf(peak, kotlin.math.abs(reference[index].toDouble()))
        }
        val meanAbsoluteError = totalAbsoluteError / actual.size
        val mse = squaredError / actual.size
        val psnr = if (mse == 0.0) Double.POSITIVE_INFINITY else
            20.0 * kotlin.math.log10(peak / kotlin.math.sqrt(mse))
        val sourceAfter = bitmapPixelSha256(cover)
        val passed = maximumAbsoluteError <= 5e-5 && sourceBefore == sourceAfter
        val elapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        val evidence = JSONObject()
            .put("schema_version", 1)
            .put("status", if (passed) "PASS_ANDROID_OWLV2_INPUTS" else "FAIL")
            .put("vocabulary_sha256", fileSha256(vocabulary))
            .put("merges_sha256", fileSha256(merges))
            .put("pixel_reference_sha256", fileSha256(pixelReference))
            .put("broad_input_ids", JSONArray(broad.inputIds.toList()))
            .put("precise_input_ids", JSONArray(precise.inputIds.toList()))
            .put("pixel_value_count", actual.size)
            .put("pixel_psnr_db", if (psnr.isFinite()) psnr else "inf")
            .put("pixel_maximum_absolute_error", maximumAbsoluteError)
            .put("pixel_mean_absolute_error", meanAbsoluteError)
            .put("source_pixel_sha256_before", sourceBefore)
            .put("source_pixel_sha256_after", sourceAfter)
            .put("source_media_modified", false)
            .put("elapsed_ms", elapsedMs)
        val target = File(outDir(), "j_owlv2_input_evidence.json")
        target.writeText(evidence.toString(2) + "\n")
        log(
            "    J status=${evidence.getString("status")} pixel_psnr=${"%.2f".format(psnr)} " +
                "max_abs=${"%.8f".format(maximumAbsoluteError)} elapsed_ms=${"%.2f".format(elapsedMs)}"
        )
        check(passed) { "Android OWLv2 input differential failed" }
    }

    private fun readFloatRaw(file: File, expectedCount: Int): FloatArray {
        require(file.length() == expectedCount.toLong() * 4)
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(expectedCount) { buffer.float }
    }

    private fun fileSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun buffer(values: FloatArray): java.nio.FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(values).also { it.position(0) }

    /** Minimal EGL14 pbuffer context so GPU filters have somewhere to render. */
    private class EglPbuffer(width: Int, height: Int) {
        var ready = false
            private set
        private var display: EGLDisplay? = null
        private var context: EGLContext? = null
        private var surface: EGLSurface? = null

        init {
            runCatching {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val version = IntArray(2)
                if (!EGL14.eglInitialize(display, version, 0, version, 1)) return@runCatching
                val configAttribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0)) return@runCatching
                val config = configs[0] ?: return@runCatching
                val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
                val surfaceAttribs = intArrayOf(
                    EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE
                )
                surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
                ready = EGL14.eglMakeCurrent(display, surface, surface, context)
            }
        }

        fun upload(bitmap: Bitmap): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE.toFloat())
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE.toFloat())
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            return textures[0]
        }

        fun readBack(textureId: Int, width: Int, height: Int, target: File) {
            val framebuffer = IntArray(1)
            GLES20.glGenFramebuffers(1, framebuffer, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, textureId, 0
            )
            val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            pixels.rewind()
            bitmap.copyPixelsFromBuffer(pixels)
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        fun destroy() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            surface?.let { EGL14.eglDestroySurface(display, it) }
            context?.let { EGL14.eglDestroyContext(display, it) }
            EGL14.eglTerminate(display)
        }
    }
}
