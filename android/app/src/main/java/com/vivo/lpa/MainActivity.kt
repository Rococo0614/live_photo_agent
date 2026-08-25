package com.vivo.lpa

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.lang.reflect.Modifier

/**
 * SDK 可用性探针。
 *
 * 目的（里程碑 1）：
 *  1. 验证两个 vivo AAR 的类能否在普通(非系统签名)应用中加载
 *  2. 验证 native .so 能否 dlopen
 *  3. 把关键类的公开方法签名 dump 到 logcat —— 替代缺失的 javap，
 *     为后续把 20 个 ToolName 映射到真实 SDK 调用提供依据
 *
 * 结果同时输出到屏幕和 logcat(TAG=LPA_PROBE)，便于 Linux 端通过
 *   adb logcat -s LPA_PROBE
 * 无头采集。
 */
class MainActivity : AppCompatActivity() {

    private val sb = StringBuilder()

    private fun out(line: String) {
        sb.append(line).append('\n')
        Log.i(TAG, line)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tv = TextView(this).apply {
            textSize = 10f
            setPadding(16, 16, 16, 16)
            setTextIsSelectable(true)
        }
        setContentView(ScrollView(this).apply { addView(tv) })

        out("=== LPA SDK PROBE BEGIN ===")
        probeDevice()
        probeClasses()
        probeNativeLibs()
        probeApiSurface()
        out("=== LPA SDK PROBE END ===")

        tv.text = sb.toString()
    }

    private fun probeDevice() {
        out("")
        out("[1] 设备信息")
        out("  model    = ${Build.MODEL}")
        out("  device   = ${Build.DEVICE}")
        out("  board    = ${Build.BOARD}")
        out("  soc      = ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "n/a"}")
        out("  android  = ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        out("  abis     = ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    /** 关键类可加载性。类名全部来自 AAR classes.jar 实测，不是猜的。 */
    private fun probeClasses() {
        out("")
        out("[2] 关键类加载")
        var ok = 0
        for (cn in PROBE_CLASSES) {
            val r = runCatching { Class.forName(cn, false, classLoader) }
            if (r.isSuccess) {
                ok++
                out("  OK   $cn")
            } else {
                out("  FAIL $cn  <- ${r.exceptionOrNull()?.javaClass?.simpleName}")
            }
        }
        out("  小结: $ok/${PROBE_CLASSES.size} 可加载")
    }

    /** native .so 能否 dlopen。名字来自 AAR jni/arm64-v8a 实测。 */
    private fun probeNativeLibs() {
        out("")
        out("[3] native 库加载")
        var ok = 0
        for (lib in PROBE_LIBS) {
            val r = runCatching { System.loadLibrary(lib) }
            if (r.isSuccess) {
                ok++
                out("  OK   lib$lib.so")
            } else {
                val e = r.exceptionOrNull()
                out("  FAIL lib$lib.so  <- ${e?.message?.take(160)}")
            }
        }
        out("  小结: $ok/${PROBE_LIBS.size} 可加载")
    }

    /**
     * dump 公开 API。这是本探针最有价值的部分：
     * 我们没有 JDK 的 javap，只能靠运行时反射拿到真实签名。
     */
    private fun probeApiSurface() {
        out("")
        out("[4] 公开 API 签名 dump")
        for (cn in DUMP_CLASSES + DUMP_WITH_NESTED) {
            val cls = runCatching { Class.forName(cn, false, classLoader) }.getOrNull()
            if (cls == null) {
                out("")
                out("  --- $cn : 不可加载，跳过 ---")
                continue
            }
            dumpOne(cls, cn)
            // listener 类的回调方法通常定义在嵌套接口里，必须一并 dump
            if (cn in DUMP_WITH_NESTED) {
                cls.declaredClasses.forEach { nested ->
                    dumpOne(nested, "  ↳ nested ${nested.simpleName}")
                }
            }
        }
    }

    private fun dumpOne(cls: Class<*>, title: String) {
            out("")
            out("  --- $title ---")

            cls.declaredConstructors
                .filter { Modifier.isPublic(it.modifiers) }
                .forEach { c -> out("    <init>(${c.parameterTypes.joinToString { s(it) }})") }

            cls.declaredFields
                .filter { Modifier.isPublic(it.modifiers) }
                .forEach { f -> out("    field ${s(f.type)} ${f.name}") }

            cls.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) }
                .sortedBy { it.name }
                .forEach { m ->
                    val st = if (Modifier.isStatic(m.modifiers)) "static " else ""
                    out("    $st${s(m.returnType)} ${m.name}(${m.parameterTypes.joinToString { s(it) }})")
                }
    }

    /** 类型名简写，去掉包前缀让 logcat 可读 */
    private fun s(c: Class<*>): String =
        c.simpleName.ifEmpty { c.name.substringAfterLast('.') }

    companion object {
        private const val TAG = "LPA_PROBE"

        private val PROBE_CLASSES = listOf(
            // videoeditorsdk 新版 editor.api
            "com.vivo.videoeditorsdk.editor.api.VMEditor",
            "com.vivo.videoeditorsdk.editor.api.IVMEditor",
            "com.vivo.videoeditorsdk.editor.api.VMPlayer",
            "com.vivo.videoeditorsdk.editor.api.VMEffectEditor",
            "com.vivo.videoeditorsdk.editor.api.VMAudioEditor",
            "com.vivo.videoeditorsdk.editor.api.VMVideoFramePicker",
            "com.vivo.videoeditorsdk.editor.api.VMThumbnailsGenerator",
            "com.vivo.videoeditorsdk.editor.api.VMVideoConverter",
            "com.vivo.videoeditorsdk.editor.api.VMMusicExtractor",
            "com.vivo.videoeditorsdk.editor.api.entity.input.VMClip",
            "com.vivo.videoeditorsdk.editor.api.entity.input.VMFilterData",
            "com.vivo.videoeditorsdk.editor.api.entity.input.TextStickerExData",
            "com.vivo.videoeditorsdk.editor.api.entity.input.ClipAudioParams",
            "com.vivo.videoeditorsdk.editor.api.entity.output.VMExportEncodeConfig",
            "com.vivo.videoeditorsdk.editor.api.entity.listener.VMExportListener",
            // videoeditorsdk 旧版/底层
            "com.vivo.videoeditorsdk.videoeditor.VideoExportEngine",
            "com.vivo.videoeditorsdk.videoeditor.VideoEditorConfig",
            "com.vivo.videoeditorsdk.videoeditor.VELoader",
            "com.vivo.videoeditorsdk.base.SpeedZoneCurve",
            "com.vivo.videoeditorsdk.render.MatteRender",
            "com.vivo.videoeditorsdk.media.ExportParam",
            // mediaeffectsdk —— live photo 专用通路
            "com.vivo.mediaeffectsdk.motioneffect.camera.VMLivePhotoEffectEngine",
            "com.vivo.mediaeffectsdk.motioneffect.core.render.gpufilters.livephoto.GPUMattingFilter",
            "com.vivo.mediaeffectsdk.motioneffect.core.render.gpufilters.livephoto.GPULineMattingFilter2",
            "com.vivo.mediaeffectsdk.motioneffect.core.render.gpufilters.livephoto.GPUImageOverlayFilter",
            "com.vivo.mediaeffectsdk.motioneffect.core.render.gpufilters.livephoto.GPUStickerMattingFilter",
            "com.vivo.mediaeffectsdk.motioneffect.core.render.gpufilters.livephoto.BodyStrokeParams"
        )

        private val PROBE_LIBS = listOf(
            // videoeditorsdk
            "VideoEditorSDK_jni",
            "VideoEditorSDK_Dependency",
            "GLHardwardRender_Jni",
            "vivo_media_jni",
            "vcp_local",
            "vsfpa",
            // mediaeffectsdk
            "mediaeffectsdk",
            "media_effect",
            "ImageProcess",
            "mesEglImageRender",
            "mesDebug"
        )

        /** 这几个类的签名最关键：决定 trim/export/matting 怎么调 */
        private val DUMP_CLASSES = listOf(
            "com.vivo.videoeditorsdk.editor.api.VMEditor",
            "com.vivo.videoeditorsdk.editor.api.entity.input.VMClip",
            "com.vivo.videoeditorsdk.editor.api.entity.output.VMExportEncodeConfig",
            "com.vivo.videoeditorsdk.editor.api.VMVideoFramePicker",
            "com.vivo.videoeditorsdk.videoeditor.VideoEditorConfig",
            "com.vivo.mediaeffectsdk.motioneffect.camera.VMLivePhotoEffectEngine",
            // 以下用于判定「离屏渲染」可行性：
            // VMEditor.init 需要 VMEditorView，若 converter/export 通路不依赖 View，
            // 就存在无头执行的可能
            "com.vivo.videoeditorsdk.editor.api.VMVideoConverter",
            "com.vivo.videoeditorsdk.editor.api.entity.output.VMConverterConfig",
            "com.vivo.videoeditorsdk.editor.api.entity.listener.VMConverterListener",
            "com.vivo.videoeditorsdk.editor.api.view.VMEditorView",
            "com.vivo.videoeditorsdk.editor.api.entity.input.VMResource",
            "com.vivo.videoeditorsdk.videoeditor.VideoExportEngine",
            "com.vivo.videoeditorsdk.videoeditor.VideoConverter",
            "com.vivo.videoeditorsdk.videoeditor.VELoader",
            "com.vivo.videoeditorsdk.media.ExportParam",
            // 无头通路所需的回调与配置签名
            "com.vivo.videoeditorsdk.videoeditor.VideoExportConfig",
            "com.vivo.videoeditorsdk.videoeditor.OnProgressListener",
            "com.vivo.videoeditorsdk.videoeditor.ErrorCode"
        )

        /** 需要连内部类一起 dump 的（listener 多为嵌套接口） */
        private val DUMP_WITH_NESTED = listOf(
            "com.vivo.videoeditorsdk.editor.api.entity.listener.VMConverterListener",
            "com.vivo.videoeditorsdk.editor.api.entity.listener.VMExportListener",
            "com.vivo.videoeditorsdk.videoeditor.VideoExportEngine",
            "com.vivo.videoeditorsdk.editor.api.entity.input.VMClip"
        )
    }
}
