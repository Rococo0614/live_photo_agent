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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        val coverPath = intent?.getStringExtra("cover_path")
        val personPath = intent?.getStringExtra("person_path")
        Thread({ runProbes(coverPath, personPath) }, "lpa-probe2").start()
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

    private fun runProbes(coverPath: String?, personPath: String?) {
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
        val fgLatch = java.util.concurrent.CountDownLatch(1)
        main.post {
            runCatching { probeFAndG(cover, mask) }
                .onFailure { log("F/G 抛异常: ${it::class.java.simpleName}: ${it.message}") }
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
