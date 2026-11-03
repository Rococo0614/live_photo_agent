package com.vivo.lpa

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only key-frame adapter proving the real locator/mask can drive the region tool.
 * It intentionally certifies one preview frame only and must not be described as temporal tracking.
 */
class AndroidEdgeTamKeyFrameTracker(
    private val context: Context,
    private val onMaskReady: (AndroidEdgeTamInitialMask) -> Unit = {}
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
        require(BuildConfig.DEBUG) { "key-frame-only certificate adapter is debug-only" }
        val mask = AndroidEdgeTamQnnSegmenter(context).segment(
            sourceBitmap,
            grounding,
            cancelled,
            progress
        )
        onMaskReady(mask)
        if (!mask.qualityAccepted) {
            return AgentLocalObservationResolution.Pending(
                listOf(AgentObservationRequirement(
                    requirementId = "observe-${goal.operations.single().operationId}-mask-track",
                    operationId = goal.operations.single().operationId,
                    kind = AgentDataKind.MASK_TRACK,
                    description = "EdgeTAM key-frame mask failed deterministic quality gates"
                )),
                "EdgeTAM mask未通过覆盖率或box外泄漏门禁"
            )
        }
        val maskTrack = AgentShadowObservationBridge.materializeMaskTrack(
            objectTrack = objectTrack,
            expectedFrameCount = 1,
            frames = listOf(ShadowMaskFrame.create(
                frameIndex = 0,
                timestampMs = 0,
                width = mask.width,
                height = mask.height,
                maskValues = mask.maskValues
            )),
            validationPolicy = TrackValidationPolicy(
                policyId = "android-qnn245-key-frame-preview-v1",
                minimumVisibleAreaRatio = 0.10
            )
        )
        val materialization = AgentShadowObservationBridge.finalizeForCompiler(goal, maskTrack)
        val sourceCopy = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("failed to preserve the key-frame preview source")
        return AgentLocalObservationResolution.Ready(
            materialization,
            AndroidShadowSourceFrameProvider { assetId, frameIndex, timestampMs ->
                if (assetId != objectTrack.sourceAssetId || frameIndex != 0 || timestampMs != 0L) {
                    null
                } else {
                    AndroidShadowSourceFrame(
                        assetId,
                        frameIndex,
                        timestampMs,
                        sourceCopy,
                        ownedByPreview = true
                    )
                }
            }
        )
    }
}
