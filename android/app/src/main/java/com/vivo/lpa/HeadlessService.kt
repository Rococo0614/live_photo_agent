package com.vivo.lpa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 8+ 禁止后台起普通 Service（adb 起也会被判 "app is in background"），
        // 因此必须立刻转前台。通知本身与验证无关，只为拿到前台豁免。
        promoteToForeground()
        // 不能占用主线程：SDK 回调依赖主线程 Looper 派发，阻塞会死锁
        Thread({ runAll() }, "lpa-headless").start()
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
            val head = dst.inputStream().use { it.readNBytes(12) }
            val isMp4 = head.size == 12 && String(head, 4, 4) == "ftyp"
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
