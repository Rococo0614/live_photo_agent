package com.vivo.lpa

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Rigid short-timeline propagation of one real generated anchor; not a video diffusion model. */
class AndroidFullTimelineGenerationPreviewRenderer(
    private val context: Context,
    private val sourceAsset: PreparedAsset,
    private val timeline: AndroidDecodedVideoTimeline,
    private val photoTimestampUs: Long,
    private val cancelled: AtomicBoolean,
    private val progress: (String) -> Unit
) : AndroidGenerationTimelinePreviewRenderer {
    override fun render(
        materialization: AgentShadowObservationMaterialization,
        change: LocalGenerativeChange,
        generatedAnchor: Bitmap
    ): AndroidEncodedTimelinePreview? {
        val video = sourceAsset.videoFile ?: return null
        require(materialization.maskTrack.expectedFrameCount == timeline.frameCount)
        val root = File(
            context.cacheDir,
            "full-generation-previews/${materialization.certificate.certificateId}-${System.nanoTime()}"
        ).apply { mkdirs() }
        try {
            val maskRoot = File(root, "generation-masks").apply { mkdirs() }
            val trajectoryBounds = mutableListOf<AndroidMaskBounds>()
            val maskFiles = timeline.frames.map { frame ->
                val sourceMask = requireNotNull(materialization.maskTrack.frame(frame.frameIndex))
                    .copyMaskValues()
                val cleanedMask = AndroidGenerationRegionPolicy.retainSignificantComponents(
                    sourceMask,
                    generatedAnchor.width,
                    generatedAnchor.height
                )
                val expanded = AndroidGenerationRegionPolicy.dilateBinaryMask(
                    cleanedMask,
                    generatedAnchor.width,
                    generatedAnchor.height,
                    LocalMaskedGenerationShadowCompiler.GENERATION_MASK_PADDING_PX
                )
                val dominant = AndroidGenerationRegionPolicy.retainLargestComponent(
                    cleanedMask,
                    generatedAnchor.width,
                    generatedAnchor.height
                )
                val dominantExpanded = AndroidGenerationRegionPolicy.dilateBinaryMask(
                    dominant,
                    generatedAnchor.width,
                    generatedAnchor.height,
                    LocalMaskedGenerationShadowCompiler.GENERATION_MASK_PADDING_PX
                )
                trajectoryBounds += requireNotNull(AndroidGenerationRegionPolicy.bounds(
                    dominantExpanded,
                    generatedAnchor.width,
                    generatedAnchor.height
                ))
                val propagationEnvelope = AndroidGenerationRegionPolicy.rectangularEnvelope(
                    expanded,
                    generatedAnchor.width,
                    generatedAnchor.height
                )
                val featheredEnvelope = AndroidGenerationRegionPolicy.featherInsideMask(
                    propagationEnvelope,
                    generatedAnchor.width,
                    generatedAnchor.height,
                    radius = 4
                )
                File(maskRoot, "mask-${frame.frameIndex.toString().padStart(6, '0')}.raw").apply {
                    writeBytes(featheredEnvelope)
                }
            }
            val anchorIndex = materialization.maskTrack.preferredPreviewFrameIndex
                ?: timeline.nearestFrameIndex(photoTimestampUs)
            progress("正在将真实生成锚帧传播到短动态时间线…")
            val encodedVideo = File(root, "generated-rigid-motion.mp4")
            val encoded = AndroidMaskedVideoEncoder().encodeGeneratedPatch(
                video,
                timeline,
                maskFiles,
                encodedVideo,
                generatedAnchor,
                anchorIndex,
                cancelled,
                trajectoryBounds
            )
            require(encoded.videoFrameCount == timeline.frameCount)
            val sourceAudio = AndroidMediaTrackInspector.fingerprint(video, "audio/")
            val outputAudio = AndroidMediaTrackInspector.fingerprint(encodedVideo, "audio/")
            require((sourceAudio == null && outputAudio == null) ||
                (sourceAudio != null && outputAudio != null &&
                    sourceAudio.sampleCount == outputAudio.sampleCount &&
                    sourceAudio.sampleDataSha256 == outputAudio.sampleDataSha256 &&
                    sourceAudio.timelineSha256 == outputAudio.timelineSha256)) {
                "generated timeline audio changed"
            }
            val cover = File(root, "generated-cover.jpg")
            FileOutputStream(cover).use { stream ->
                require(generatedAnchor.compress(Bitmap.CompressFormat.JPEG, 95, stream))
            }
            progress("正在打包不可发布的动态生成私有预览…")
            val packed = LivePhotoSdkAdapter(context).pack(
                cover,
                encodedVideo,
                File(root, "packed"),
                photoTimestampUs
            )
            val verified = requireNotNull(
                LivePhotoSdkAdapter(context).decode(packed, File(root, "verified"))
            ) { "generated private Live Photo cannot be decoded" }
            require(verified.photoTimestampUs == photoTimestampUs)
            val verifiedTimeline = AndroidVideoFrameTimelineDecoder.decode(verified.video, cancelled)
            require(verifiedTimeline.frames.map { it.presentationTimeUs } ==
                timeline.frames.map { it.presentationTimeUs }) {
                "generated private Live Photo timeline changed"
            }
            val verifiedAudio = AndroidMediaTrackInspector.fingerprint(verified.video, "audio/")
            require((sourceAudio == null && verifiedAudio == null) ||
                (sourceAudio != null && verifiedAudio != null &&
                    sourceAudio.sampleCount == verifiedAudio.sampleCount &&
                    sourceAudio.sampleDataSha256 == verifiedAudio.sampleDataSha256 &&
                    sourceAudio.timelineSha256 == verifiedAudio.timelineSha256)) {
                "generated private Live Photo audio changed"
            }
            maskRoot.deleteRecursively()
            File(root, "verified").deleteRecursively()
            return AndroidEncodedTimelinePreview(
                packedLivePhoto = packed,
                motionVideo = encodedVideo,
                cleanupRoot = root,
                frameCount = timeline.frameCount,
                photoTimestampUs = photoTimestampUs
            )
        } catch (failure: Throwable) {
            root.deleteRecursively()
            throw failure
        }
    }
}
