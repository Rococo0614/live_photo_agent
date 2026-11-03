package com.vivo.lpa

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Builds a private full Live Photo preview from an already-certified dynamic mask track. */
class AndroidFullTimelinePreviewRenderer(
    private val context: Context,
    private val sourceAsset: PreparedAsset,
    private val timeline: AndroidDecodedVideoTimeline,
    private val photoTimestampUs: Long,
    private val cancelled: AtomicBoolean,
    private val progress: (String) -> Unit
) : AndroidTimelinePreviewRenderer {
    override fun render(
        materialization: AgentShadowObservationMaterialization,
        change: LocalDeterministicChange
    ): AndroidEncodedTimelinePreview? {
        val tint = change as? LocalDeterministicChange.ColorTint ?: return null
        val video = sourceAsset.videoFile ?: return null
        require(materialization.maskTrack.expectedFrameCount == timeline.frameCount)
        val root = File(
            context.cacheDir,
            "full-timeline-previews/${materialization.certificate.certificateId}-${System.nanoTime()}"
        ).apply { mkdirs() }
        try {
            val maskRoot = File(root, "masks").apply { mkdirs() }
            val maskFiles = timeline.frames.map { frame ->
                File(maskRoot, "mask-${frame.frameIndex.toString().padStart(6, '0')}.raw").apply {
                    writeBytes(requireNotNull(materialization.maskTrack.frame(frame.frameIndex))
                        .copyMaskValues())
                }
            }
            progress("正在生成完整动态调色视频…")
            val encodedVideo = File(root, "tinted-motion.mp4")
            val encoded = AndroidMaskedVideoEncoder().encodeTint(
                video,
                timeline,
                maskFiles,
                encodedVideo,
                tint.colorHex,
                tint.intensity,
                cancelled
            )
            require(encoded.videoFrameCount == timeline.frameCount)
            val sourceAudio = AndroidMediaTrackInspector.fingerprint(video, "audio/")
            val encodedAudio = AndroidMediaTrackInspector.fingerprint(encodedVideo, "audio/")
            require((sourceAudio == null && encodedAudio == null) ||
                (sourceAudio != null && encodedAudio != null &&
                    sourceAudio.sampleCount == encodedAudio.sampleCount &&
                    sourceAudio.sampleDataSha256 == encodedAudio.sampleDataSha256 &&
                    sourceAudio.timelineSha256 == encodedAudio.timelineSha256)) {
                "full-timeline preview audio changed during encode"
            }
            val anchorIndex = materialization.maskTrack.preferredPreviewFrameIndex
                ?: timeline.nearestFrameIndex(photoTimestampUs)
            val sourceAnchor = AndroidVideoFrameBatchReader(video, timeline).use { reader ->
                reader.read(anchorIndex, 1).single().bitmap
            }
            val tintedAnchor = try {
                AndroidPersistentTintRenderer(
                    sourceAnchor.width,
                    sourceAnchor.height,
                    tint.colorHex,
                    tint.intensity
                ).use { renderer ->
                    renderer.render(sourceAnchor, maskFiles[anchorIndex].readBytes())
                }
            } finally {
                sourceAnchor.recycle()
            }
            val cover = File(root, "tinted-cover.jpg")
            try {
                FileOutputStream(cover).use { stream ->
                    require(tintedAnchor.compress(Bitmap.CompressFormat.JPEG, 95, stream))
                }
            } finally {
                tintedAnchor.recycle()
            }
            progress("正在打包临时Live Photo预览…")
            val packed = LivePhotoSdkAdapter(context).pack(
                cover,
                encodedVideo,
                File(root, "packed"),
                photoTimestampUs
            )
            val verified = requireNotNull(
                LivePhotoSdkAdapter(context).decode(packed, File(root, "verified"))
            ) { "temporary full-timeline Live Photo cannot be decoded" }
            require(verified.photoTimestampUs == photoTimestampUs)
            val verifiedTimeline = AndroidVideoFrameTimelineDecoder.decode(
                verified.video,
                cancelled
            )
            require(verifiedTimeline.frames.map { it.presentationTimeUs } ==
                timeline.frames.map { it.presentationTimeUs }) {
                "temporary Live Photo timeline changed during pack/decode"
            }
            val verifiedAudio = AndroidMediaTrackInspector.fingerprint(verified.video, "audio/")
            require((sourceAudio == null && verifiedAudio == null) ||
                (sourceAudio != null && verifiedAudio != null &&
                    sourceAudio.sampleCount == verifiedAudio.sampleCount &&
                    sourceAudio.sampleDataSha256 == verifiedAudio.sampleDataSha256 &&
                    sourceAudio.timelineSha256 == verifiedAudio.timelineSha256)) {
                "temporary Live Photo audio changed during pack/decode"
            }
            maskRoot.deleteRecursively()
            File(root, "verified").deleteRecursively()
            return AndroidEncodedTimelinePreview(
                packed,
                root,
                timeline.frameCount,
                photoTimestampUs
            )
        } catch (failure: Throwable) {
            root.deleteRecursively()
            throw failure
        }
    }
}
