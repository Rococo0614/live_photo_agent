package com.vivo.lpa

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

data class AndroidShadowSourceFrame(
    val assetId: String,
    val frameIndex: Int,
    val timestampMs: Long,
    val bitmap: Bitmap,
    val ownedByPreview: Boolean = false
)

fun interface AndroidShadowSourceFrameProvider {
    fun resolve(assetId: String, frameIndex: Int, timestampMs: Long): AndroidShadowSourceFrame?
}

data class AndroidTemporaryLocalPreviewArtifact(
    val artifactId: String,
    val sourceAssetId: String,
    val frameIndex: Int,
    val timestampMs: Long,
    val sourceBitmap: Bitmap,
    val previewBitmap: Bitmap,
    val effectKind: String,
    val rendererBackendId: String,
    val certificateFingerprint: String,
    val packedLivePhotoFile: java.io.File? = null,
    val timelineFrameCount: Int? = null,
    val lifecycle: LocalPreviewLifecycle
)

/** Process-local preview storage. It owns preview bitmaps but never source frames or media files. */
class AndroidTemporaryLocalPreviewStore(
    private val clockMs: () -> Long = android.os.SystemClock::elapsedRealtime
) {
    private data class Holder(
        val artifact: AndroidTemporaryLocalPreviewArtifact,
        var lifecycle: LocalPreviewLifecycle,
        val ownsSourceBitmap: Boolean,
        val timelineCleanupRoot: java.io.File?
    )

    private val sequence = AtomicLong()
    private val holders = linkedMapOf<String, Holder>()

    @Synchronized
    fun create(
        sourceAssetId: String,
        frameIndex: Int,
        timestampMs: Long,
        sourceBitmap: Bitmap,
        previewBitmap: Bitmap,
        effectKind: String,
        rendererBackendId: String,
        certificateFingerprint: String,
        ownsSourceBitmap: Boolean = false,
        encodedTimeline: AndroidEncodedTimelinePreview? = null
    ): AndroidLocalPreviewInteraction {
        require(sourceAssetId.isNotBlank())
        require(frameIndex >= 0 && timestampMs >= 0)
        require(!sourceBitmap.isRecycled && !previewBitmap.isRecycled)
        require(sourceBitmap.width == previewBitmap.width && sourceBitmap.height == previewBitmap.height)
        require(effectKind in setOf("color_tint", "blur"))
        require(rendererBackendId.isNotBlank())
        require(certificateFingerprint.isNotBlank())
        val now = clockMs()
        val artifactId = "local-preview-${now}-${sequence.incrementAndGet()}"
        val lifecycle = LocalPreviewLifecycleReducer.create(artifactId, now)
        val artifact = AndroidTemporaryLocalPreviewArtifact(
            artifactId,
            sourceAssetId,
            frameIndex,
            timestampMs,
            sourceBitmap,
            previewBitmap,
            effectKind,
            rendererBackendId,
            certificateFingerprint,
            encodedTimeline?.packedLivePhoto,
            encodedTimeline?.frameCount,
            lifecycle
        )
        holders[artifactId] = Holder(
            artifact,
            lifecycle,
            ownsSourceBitmap,
            encodedTimeline?.cleanupRoot
        )
        return AndroidLocalPreviewInteraction(this, artifactId)
    }

    @Synchronized
    fun snapshot(artifactId: String): AndroidTemporaryLocalPreviewArtifact? =
        holders[artifactId]?.let { it.artifact.copy(lifecycle = it.lifecycle) }

    @Synchronized
    internal fun accept(artifactId: String): AndroidTemporaryLocalPreviewArtifact {
        val holder = requireHolder(artifactId)
        holder.lifecycle = LocalPreviewLifecycleReducer.acceptForReview(holder.lifecycle, clockMs())
        return holder.artifact.copy(lifecycle = holder.lifecycle)
    }

    @Synchronized
    internal fun cancel(artifactId: String): AndroidTemporaryLocalPreviewArtifact {
        val holder = requireHolder(artifactId)
        holder.lifecycle = LocalPreviewLifecycleReducer.cancel(holder.lifecycle, clockMs())
        return holder.artifact.copy(lifecycle = holder.lifecycle)
    }

    @Synchronized
    internal fun undo(artifactId: String): AndroidTemporaryLocalPreviewArtifact {
        val holder = requireHolder(artifactId)
        holder.lifecycle = LocalPreviewLifecycleReducer.undo(holder.lifecycle, clockMs())
        return holder.artifact.copy(lifecycle = holder.lifecycle)
    }

    @Synchronized
    internal fun release(artifactId: String) {
        val holder = requireHolder(artifactId)
        require(holder.lifecycle.state in setOf(
            LocalPreviewReviewState.CANCELLED,
            LocalPreviewReviewState.UNDONE
        )) { "only cancelled or undone previews can be released" }
        holders.remove(artifactId)
        if (!holder.artifact.previewBitmap.isRecycled) holder.artifact.previewBitmap.recycle()
        if (holder.ownsSourceBitmap && !holder.artifact.sourceBitmap.isRecycled) {
            holder.artifact.sourceBitmap.recycle()
        }
        holder.timelineCleanupRoot?.deleteRecursively()
    }

    @Synchronized
    fun activeArtifactIds(): List<String> = holders.keys.toList()

    private fun requireHolder(artifactId: String): Holder = requireNotNull(holders[artifactId]) {
        "temporary preview $artifactId is missing"
    }
}

class AndroidLocalPreviewInteraction internal constructor(
    private val store: AndroidTemporaryLocalPreviewStore,
    val artifactId: String
) {
    fun snapshot(): AndroidTemporaryLocalPreviewArtifact =
        requireNotNull(store.snapshot(artifactId)) { "temporary preview $artifactId was released" }

    /** Confirmation only accepts a temporary visual result for review; it never commits media. */
    fun confirm(): AndroidTemporaryLocalPreviewArtifact = store.accept(artifactId)

    fun cancel(): AndroidTemporaryLocalPreviewArtifact = store.cancel(artifactId)

    fun undo(): AndroidTemporaryLocalPreviewArtifact = store.undo(artifactId)

    fun release() = store.release(artifactId)
}

/**
 * Executes only [LocalDeterministicChangePreviewAction] against already-materialized shadow facts.
 * No instance is registered in the production tool registry.
 */
class AndroidLocalPreviewShadowExecutor(
    private val materialization: AgentShadowObservationMaterialization,
    private val sourceFrames: AndroidShadowSourceFrameProvider,
    private val timelinePreviewRenderer: AndroidTimelinePreviewRenderer? = null,
    private val previewStore: AndroidTemporaryLocalPreviewStore,
    private val currentStateFingerprint: () -> String,
    private val onPreviewReady: (AndroidLocalPreviewInteraction) -> Unit = {}
) : AgentDagNodeExecutor {
    override fun execute(
        node: AgentDagNode,
        inputs: AgentDagNodeInputs
    ): AgentDagNodeExecutionResult {
        require(node.actionId == LocalDeterministicChangePreviewAction.ACTION_ID) {
            "shadow preview executor cannot run ${node.actionId}"
        }
        require(node.phase == AgentActionPhase.TRANSFORM)
        require(!node.destructive && node.effects == setOf(AgentStateEffect.ADD_ARTIFACT))
        val parameters = JSONObject(node.parametersJson)
        require(parameters.optBoolean("preview_only", false))
        require(parameters.has("writeback") && !parameters.getBoolean("writeback"))
        val change = parseChange(parameters)
        val allowedParameters = when (change) {
            is LocalDeterministicChange.ColorTint ->
                setOf("effect_kind", "color_hex", "intensity", "preview_only", "writeback")
            is LocalDeterministicChange.Blur ->
                setOf("effect_kind", "blur_radius_px", "preview_only", "writeback")
        }
        require(parameters.keys().asSequence().all { it in allowedParameters }) {
            "shadow preview contains an undeclared parameter"
        }

        val media = inputs.single("media") as? AgentDagRuntimeValue.AssetSet
            ?: throw IllegalArgumentException("media input must be an asset set")
        require(media.assetIds.size == 1)
        val sourceAssetId = media.assetIds.single()
        require(sourceAssetId == materialization.maskTrack.objectTrack.sourceAssetId)

        val maskValue = inputs.single("mask_track") as? AgentDagRuntimeValue.ContentNode
            ?: throw IllegalArgumentException("mask_track input must be a content node")
        require(maskValue.kind == AgentDataKind.MASK_TRACK)
        require(maskValue.graphId == materialization.snapshot.graph.graphId)
        require(maskValue.graphVersion == materialization.snapshot.graph.version)
        require(maskValue.nodeId == materialization.maskTrack.maskTrackNodeId)

        val scopeValue = inputs.single("scope_certificate") as? AgentDagRuntimeValue.ScopeCertificate
            ?: throw IllegalArgumentException("scope_certificate input is missing")
        val certificate = materialization.certificate
        val currentFingerprint = currentStateFingerprint()
        require(media.stateFingerprint == currentFingerprint) { "source asset state is stale" }
        require(scopeValue.certificateId == certificate.certificateId)
        require(scopeValue.stateFingerprint == currentFingerprint)
        require(scopeValue.certificateFingerprint == certificate.evidenceFingerprint)
        require(certificate.readyForPreview(currentFingerprint))
        require(!certificate.readyForMutation(currentFingerprint)) {
            "shadow preview executor refuses mutation-approved certificates"
        }

        val preferredFrame = materialization.maskTrack.preferredPreviewFrameIndex
        val observation = preferredFrame?.let { preferred ->
            certificate.observations.firstOrNull {
                it.frameIndex == preferred &&
                    it.status == TrackObservationStatus.VISIBLE_VALIDATED
            }
        } ?: certificate.observations.firstOrNull {
            it.status == TrackObservationStatus.VISIBLE_VALIDATED
        } ?: throw IllegalStateException("mask track has no visible validated frame to preview")
        val maskFrame = requireNotNull(materialization.maskTrack.frame(observation.frameIndex)) {
            "validated mask payload for frame ${observation.frameIndex} is missing"
        }
        require(maskFrame.rawMaskPixels == observation.rawMaskPixels)
        val sourceFrame = requireNotNull(sourceFrames.resolve(
            sourceAssetId,
            observation.frameIndex,
            observation.timestampMs
        )) { "source frame ${observation.frameIndex} is unavailable" }
        require(sourceFrame.assetId == sourceAssetId)
        require(sourceFrame.frameIndex == observation.frameIndex)
        require(sourceFrame.timestampMs == observation.timestampMs)
        require(!sourceFrame.bitmap.isRecycled)
        require(sourceFrame.bitmap.width == maskFrame.width &&
            sourceFrame.bitmap.height == maskFrame.height)

        val maskBitmap = maskBitmap(maskFrame)
        val rendered = try {
            AndroidLocalMaskPreviewRenderer.render(sourceFrame.bitmap, maskBitmap, change)
        } finally {
            maskBitmap.recycle()
        }
        val encodedTimeline = try {
            timelinePreviewRenderer?.render(materialization, change)
        } catch (failure: Throwable) {
            rendered.bitmap.recycle()
            if (sourceFrame.ownedByPreview && !sourceFrame.bitmap.isRecycled) {
                sourceFrame.bitmap.recycle()
            }
            throw failure
        }
        val interaction = try {
            previewStore.create(
                sourceAssetId = sourceAssetId,
                frameIndex = sourceFrame.frameIndex,
                timestampMs = sourceFrame.timestampMs,
                sourceBitmap = sourceFrame.bitmap,
                previewBitmap = rendered.bitmap,
                effectKind = change.effectKind,
                rendererBackendId = rendered.backendId,
                certificateFingerprint = certificate.evidenceFingerprint,
                ownsSourceBitmap = sourceFrame.ownedByPreview,
                encodedTimeline = encodedTimeline
            )
        } catch (failure: Exception) {
            rendered.bitmap.recycle()
            encodedTimeline?.cleanupRoot?.deleteRecursively()
            if (sourceFrame.ownedByPreview && !sourceFrame.bitmap.isRecycled) {
                sourceFrame.bitmap.recycle()
            }
            throw failure
        }
        try {
            onPreviewReady(interaction)
        } catch (failure: Exception) {
            interaction.cancel()
            interaction.release()
            throw failure
        }
        return AgentDagNodeExecutionResult(
            outputs = mapOf("preview" to listOf(
                AgentDagRuntimeValue.Artifact(
                    interaction.artifactId,
                    AgentDataKind.MEDIA_ARTIFACT
                )
            )),
            message = "temporary local preview is ready for review"
        )
    }

    private fun parseChange(parameters: JSONObject): LocalDeterministicChange =
        when (parameters.getString("effect_kind")) {
            "color_tint" -> LocalDeterministicChange.ColorTint(
                parameters.getString("color_hex"),
                parameters.getDouble("intensity")
            )
            "blur" -> LocalDeterministicChange.Blur(parameters.getDouble("blur_radius_px"))
            else -> throw IllegalArgumentException("unsupported local deterministic effect")
        }

    private fun maskBitmap(frame: ShadowMaskFrame): Bitmap {
        val pixels = IntArray(frame.width * frame.height)
        frame.copyMaskValues().forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            pixels[index] = 0xff000000.toInt() or
                (value shl 16) or (value shl 8) or value
        }
        return Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
    }
}
