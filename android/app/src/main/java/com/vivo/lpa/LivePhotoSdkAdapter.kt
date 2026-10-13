package com.vivo.lpa

import android.content.Context
import android.content.ContextWrapper
import com.vivo.external_livephoto.LivePhoto
import com.vivo.external_livephoto.VivoLivePhoto
import com.vivo.external_livephoto.VivoLivePhotoFactory
import com.vivo.external_livephoto.utils.DecodeConfig
import com.vivo.mediaeffectsdk.motioneffect.camera.util.LiveXdrAssetUtil
import android.net.Uri
import java.io.File
import org.json.JSONObject

/** Thin adapter around the vendor LivePhotoSDK, not a reimplementation. */
class LivePhotoSdkAdapter(context: Context) {
    private val appContext = context.applicationContext

    fun unpack(input: File, outputDir: File): Pair<File, File>? {
        return decode(input, outputDir)?.let { it.image to it.video }
    }

    fun decode(input: File, outputDir: File): DecodedLivePhoto? {
        if (!input.isFile) return null
        return runCatching {
            val sdk = create()
            if (!sdk.isLivePhoto(input.absolutePath)) return null
            outputDir.mkdirs()
            val config = DecodeConfig().apply { isHdr2sdrEnable = false }
            val decoded = sdk.decode(input.absolutePath, outputDir.absolutePath, "decoded", config)
                ?: return null
            val image = File(decoded.imagePath)
            val video = File(decoded.videoPath)
            if (!image.isFile || !video.isFile) return null
            DecodedLivePhoto(image, video, decoded.photoTimeStamp)
        }.getOrNull()
    }

    fun pack(image: File, video: File, outputDir: File, timestampUs: Long = 1_500_000L): File {
        require(image.isFile) { "LivePhotoSDK pack image is missing: ${image.absolutePath}" }
        require(video.isFile) { "LivePhotoSDK pack video is missing: ${video.absolutePath}" }
        outputDir.mkdirs()
        val name = "live-result-${System.currentTimeMillis()}"
        // The vendor muxer maps the caller package to VMotionPhotoSource. The
        // native camera uses source 1; the Agent package is intentionally not
        // renamed, so only the SDK context is aliased for this encode call.
        val sdk = create(CameraSourceContext(appContext))
        val code = sdk.encode(outputDir.absolutePath, name, image.absolutePath, video.absolutePath, timestampUs)
        val output = outputDir.listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith(name) && it.extension.equals("jpg", true) }
            ?: File(outputDir, "$name.jpg")
        check(code == 0 && output.isFile && output.length() > 0) {
            "LivePhotoSDK encode failed code=$code output=${output.absolutePath}"
        }
        // The SDK can reject a valid VCamera Motion Photo while it is still in
        // the app-private cache. Gallery recognition is verified after publish.
        return output
    }

    fun deviceSupported(): Boolean = runCatching { create().isDeviceSupportLivePhoto() }.getOrDefault(false)

    fun isLivePhoto(uri: Uri): Boolean = runCatching { create().isLivePhoto(uri) }.getOrDefault(false)

    fun xdrAssets(): JSONObject = JSONObject()
        .put("product_name", LiveXdrAssetUtil.getProductName())
        .put("p3_to_2020", LiveXdrAssetUtil.getP3to2020LutPath(appContext))
        .put("2020_to_p3", LiveXdrAssetUtil.get2020ToP3LutPath(appContext))
        .put("watermark", LiveXdrAssetUtil.getWMLutNameLutPath(appContext))

    private fun create(): VivoLivePhoto = VivoLivePhotoFactory.create(appContext)

    private fun create(context: Context): VivoLivePhoto = VivoLivePhotoFactory.create(context)

    private class CameraSourceContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = "com.android.camera"
    }

    data class DecodedLivePhoto(
        val image: File,
        val video: File,
        val photoTimestampUs: Long
    )
}
