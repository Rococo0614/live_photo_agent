package com.vivo.lpa

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Full decoded-timeline tracker. Kept injectable until lifecycle/thermal gates pass. */
class AndroidFullTimelineMaskTracker(
    private val context: Context
) : AndroidSemanticMaskTracker {
    override fun track(
        goal: AgentGoalIr,
        state: AgentRuntimeState,
        objectTrack: ShadowObjectTrackMaterialization,
        grounding: OwlV2GroundingResult,
        sourceBitmap: Bitmap,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): AgentLocalObservationResolution {
        val sourceAsset = state.sourceAssets.singleOrNull {
            it.assetId == objectTrack.sourceAssetId
        } ?: return pending(goal, "目标素材不再位于当前Agent状态中")
        val video = sourceAsset.videoFile
            ?: return pending(goal, "Live Photo运动轨尚未解码")
        val photoTimestampUs = sourceAsset.metadata.optLong("photo_timestamp_us", -1L)
        if (photoTimestampUs < 0) {
            return pending(goal, "Live Photo封面时间戳缺失，不能选择时序condition frame")
        }
        progress("正在解码完整帧时间线…")
        val timeline = AndroidVideoFrameTimelineDecoder.decode(video, cancelled)
        val visualWidth = if (timeline.rotationDegrees in setOf(90, 270)) timeline.height
            else timeline.width
        val visualHeight = if (timeline.rotationDegrees in setOf(90, 270)) timeline.width
            else timeline.height
        val prompt = AndroidEdgeTamQnnSegmenter.promptPoints(
            grounding.candidates.single().box,
            sourceBitmap.width,
            sourceBitmap.height
        )
        val root = File(
            context.cacheDir,
            "edgetam-full-tracks/${goal.goalId}-${System.nanoTime()}"
        ).apply { mkdirs() }
        val temporalRunner = AndroidEdgeTamTemporalQnnRunner(context)
        val tracked = try {
            AndroidTimelineEdgeTamInputMaterializer(video, timeline).use { materializer ->
                AndroidEdgeTamBidirectionalTracker(temporalRunner).track(
                    timeline,
                    photoTimestampUs,
                    visualWidth,
                    visualHeight,
                    prompt,
                    root,
                    cancelled,
                    materializer,
                    progress
                )
            }
        } catch (failure: Throwable) {
            root.deleteRecursively()
            throw failure
        } finally {
            // Full-timeline tracking is the persistence scope. Do not retain DSP contexts while a
            // private preview is waiting for review.
            AndroidQnnContextRunnerFactory.releasePersistentContexts()
        }
        val frames = try {
            tracked.frames.map { frame ->
                ShadowMaskFrame.createBinaryPacked(
                    frameIndex = frame.frameIndex,
                    timestampMs = frame.presentationTimeUs / 1_000L,
                    width = visualWidth,
                    height = visualHeight,
                    maskValues = frame.maskFile.readBytes(),
                    modelObjectPresent = frame.modelObjectPresent
                )
            }
        } finally {
            root.deleteRecursively()
        }
        val maskTrack = AgentShadowObservationBridge.materializeMaskTrack(
            objectTrack,
            expectedFrameCount = timeline.frameCount,
            frames = frames,
            validationPolicy = TrackValidationPolicy(
                "android-qnn245-dynamic-timeline-v1",
                minimumVisibleAreaRatio = 0.10
            ),
            preferredPreviewFrameIndex = tracked.anchorFrameIndex
        )
        if (maskTrack.observations.any { !it.temporallyCovered }) {
            return pending(
                goal,
                "EdgeTAM仅覆盖${maskTrack.observations.count { it.temporallyCovered }}/" +
                    "${timeline.frameCount}个实际解码帧"
            )
        }
        val materialization = AgentShadowObservationBridge.finalizeForCompiler(goal, maskTrack)
        return AgentLocalObservationResolution.Ready(
            materialization,
            AndroidShadowSourceFrameProvider { assetId, frameIndex, timestampMs ->
                if (assetId != sourceAsset.assetId || frameIndex !in 0 until timeline.frameCount ||
                    timestampMs != timeline.frames[frameIndex].presentationTimeUs / 1_000L
                ) {
                    null
                } else {
                    val decoded = AndroidVideoFrameBatchReader(video, timeline).use { reader ->
                        reader.read(frameIndex, 1).single()
                    }
                    AndroidShadowSourceFrame(
                        assetId,
                        frameIndex,
                        timestampMs,
                        decoded.bitmap,
                        ownedByPreview = true
                    )
                }
            },
            AndroidFullTimelinePreviewRenderer(
                context,
                sourceAsset,
                timeline,
                photoTimestampUs,
                cancelled,
                progress
            )
        )
    }

    private fun pending(goal: AgentGoalIr, reason: String) =
        AgentLocalObservationResolution.Pending(
            listOf(AgentObservationRequirement(
                requirementId = "observe-${goal.operations.single().operationId}-dynamic-mask-track",
                operationId = goal.operations.single().operationId,
                kind = AgentDataKind.MASK_TRACK,
                description = reason
            )),
            reason
        )
}
