package com.vivo.lpa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

sealed interface AgentLocalObservationResolution {
    data class NeedUser(val prompt: String) : AgentLocalObservationResolution
    data class Pending(
        val requirements: List<AgentObservationRequirement>,
        val reason: String
    ) : AgentLocalObservationResolution
    data class Ready(
        val materialization: AgentShadowObservationMaterialization,
        val sourceFrames: AndroidShadowSourceFrameProvider,
        val timelinePreviewRenderer: AndroidTimelinePreviewRenderer? = null,
        val generationTimelinePreviewRenderer: AndroidGenerationTimelinePreviewRenderer? = null
    ) : AgentLocalObservationResolution
}

data class AndroidEncodedTimelinePreview(
    val packedLivePhoto: java.io.File,
    val cleanupRoot: java.io.File,
    val frameCount: Int,
    val photoTimestampUs: Long
)

fun interface AndroidTimelinePreviewRenderer {
    fun render(
        materialization: AgentShadowObservationMaterialization,
        change: LocalDeterministicChange
    ): AndroidEncodedTimelinePreview?
}

fun interface AndroidGenerationTimelinePreviewRenderer {
    fun render(
        materialization: AgentShadowObservationMaterialization,
        change: LocalGenerativeChange,
        generatedAnchor: Bitmap
    ): AndroidEncodedTimelinePreview?
}

/** Model/backend adapter; semantic choices remain in Goal IR and physical facts remain host-owned. */
fun interface AgentLocalObservationBackend {
    fun observe(
        goal: AgentGoalIr,
        state: AgentRuntimeState,
        content: AgentContentGraphSnapshot,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): AgentLocalObservationResolution
}

fun interface AndroidSemanticMaskTracker {
    fun track(
        goal: AgentGoalIr,
        state: AgentRuntimeState,
        objectTrack: ShadowObjectTrackMaterialization,
        grounding: OwlV2GroundingResult,
        sourceBitmap: Bitmap,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): AgentLocalObservationResolution
}

object UnavailableAndroidSemanticMaskTracker : AndroidSemanticMaskTracker {
    override fun track(
        goal: AgentGoalIr,
        state: AgentRuntimeState,
        objectTrack: ShadowObjectTrackMaterialization,
        grounding: OwlV2GroundingResult,
        sourceBitmap: Bitmap,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): AgentLocalObservationResolution.Pending = AgentLocalObservationResolution.Pending(
        requirements = listOf(AgentObservationRequirement(
            requirementId = "observe-${goal.operations.single().operationId}-mask-track",
            operationId = goal.operations.single().operationId,
            kind = AgentDataKind.MASK_TRACK,
            description = "Run the pinned QAIRT 2.45 EdgeTAM temporal mask tracker"
        )),
        reason = "语言目标已由 OWLv2 唯一定位；EdgeTAM Android 时序执行器尚未注册"
    )
}

/** Real app-owned OWLv2 observation stage followed by a separately injectable mask tracker. */
class AndroidQnnLocalObservationBackend(
    private val context: Context,
    private val tracker: AndroidSemanticMaskTracker = UnavailableAndroidSemanticMaskTracker
) : AgentLocalObservationBackend {
    override fun observe(
        goal: AgentGoalIr,
        state: AgentRuntimeState,
        content: AgentContentGraphSnapshot,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): AgentLocalObservationResolution {
        val operation = goal.operations.single()
        val semantic = operation.target as? AgentGoalTarget.Semantic
            ?: return AgentLocalObservationResolution.NeedUser("请用语言说明要修改的画面对象。")
        val assets = state.scopedAssets()
        if (assets.size != 1) {
            return AgentLocalObservationResolution.NeedUser("局部预览每次需要明确一张 Live Photo。")
        }
        val source = assets.single()
        if (source.imageFile == null && source.videoFile == null) {
            return pending(operation, AgentDataKind.OBJECT_TRACK, "Live Photo 定位帧尚未解码")
        }
        val missing = AndroidLocalTrackedModelPack.missingOwlV2Artifacts(context)
        if (missing.isNotEmpty()) {
            return pending(
                operation,
                AgentDataKind.OBJECT_TRACK,
                "OWLv2 QAIRT 2.45 模型包不完整：${missing.joinToString()}"
            )
        }
        val bitmap = groundingBitmap(source, cancelled, progress)
            ?: return pending(operation, AgentDataKind.OBJECT_TRACK, "无法读取定位帧")
        try {
            val locator = AndroidOwlV2QnnLocator(context).locate(
                canonicalQuery = semantic.description,
                sourceText = semantic.sourceText,
                stateFingerprint = content.stateFingerprint,
                bitmap = bitmap,
                cancelled = cancelled,
                progress = progress
            ).result
            when (locator.disposition) {
                OwlV2GroundingDisposition.NEED_OBSERVATION -> return pending(
                    operation,
                    AgentDataKind.OBJECT_TRACK,
                    "画面中未稳定观察到“${semantic.sourceText ?: semantic.description}”"
                )
                OwlV2GroundingDisposition.NEED_USER -> return AgentLocalObservationResolution.NeedUser(
                    "检测到 ${locator.candidates.size} 个可能的“${semantic.sourceText ?: "目标"}”，" +
                        "请用颜色、位置或动作补充描述。"
                )
                OwlV2GroundingDisposition.CANDIDATE_SET_READY -> Unit
            }
            val grounded = AgentShadowObservationBridge.materializeObjectTrack(
                content,
                locator.toShadowObservation(
                    operationId = operation.operationId,
                    sourceAssetId = source.assetId,
                    observedAtMs = SystemClock.elapsedRealtime()
                )
            )
            val objectTrack = when (grounded) {
                is ShadowObjectTrackResult.Materialized -> grounded.track
                is ShadowObjectTrackResult.NeedUser -> return AgentLocalObservationResolution.NeedUser(
                    "检测到 ${grounded.candidateCount} 个可能目标，请继续用语言补充描述。"
                )
                is ShadowObjectTrackResult.NeedObservation -> return pending(
                    operation,
                    AgentDataKind.OBJECT_TRACK,
                    grounded.reason
                )
            }
            return tracker.track(
                goal,
                state,
                objectTrack,
                locator,
                bitmap,
                cancelled,
                progress
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            AndroidQnnContextRunnerFactory.releasePersistentContexts()
        }
    }

    private fun pending(
        operation: AgentGoalOperation,
        kind: AgentDataKind,
        reason: String
    ) = AgentLocalObservationResolution.Pending(
        listOf(AgentObservationRequirement(
            requirementId = "observe-${operation.operationId}-${kind.name.lowercase()}",
            operationId = operation.operationId,
            kind = kind,
            description = reason
        )),
        reason
    )

    private fun groundingBitmap(
        source: PreparedAsset,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): Bitmap? {
        val video = source.videoFile
        val timestampUs = source.metadata.optLong("photo_timestamp_us", -1L)
        if (video != null && timestampUs >= 0L) {
            progress("正在解码封面时间戳对应的实际条件帧…")
            val timeline = AndroidVideoFrameTimelineDecoder.decode(video, cancelled)
            val anchorIndex = timeline.nearestFrameIndex(timestampUs)
            return AndroidVideoFrameBatchReader(video, timeline).use { reader ->
                reader.read(anchorIndex, 1).single().bitmap
            }
        }
        return source.imageFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }
}

/** Production/default-false fallback when the experimental context runner is not packaged. */
object UnavailableAgentLocalObservationBackend : AgentLocalObservationBackend {
    override fun observe(
        goal: AgentGoalIr,
        state: AgentRuntimeState,
        content: AgentContentGraphSnapshot,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): AgentLocalObservationResolution = AgentLocalObservationResolution.Pending(
        requirements = listOf(AgentObservationRequirement(
            requirementId = "observe-${goal.operations.first().operationId}-target",
            operationId = goal.operations.first().operationId,
            kind = AgentDataKind.OBJECT_TRACK,
            description = "Android QAIRT 2.45 locator/tracker backend is not registered"
        )),
        reason = "端侧局部目标执行层尚未安装；已拒绝回退到全局滤镜"
    )
}
