package com.vivo.lpa

import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Executes only the separately gated, single-frame masked-generation preview action. */
class AndroidLocalGenerationPreviewShadowExecutor(
    private val materialization: AgentShadowObservationMaterialization,
    private val sourceFrames: AndroidShadowSourceFrameProvider,
    private val previewStore: AndroidTemporaryLocalPreviewStore,
    private val backend: AndroidMaskedGenerationBackend,
    private val timelinePreviewRenderer: AndroidGenerationTimelinePreviewRenderer? = null,
    private val cancelled: AtomicBoolean,
    private val progress: (String) -> Unit = {},
    private val currentStateFingerprint: () -> String,
    private val onPreviewReady: (AndroidLocalPreviewInteraction) -> Unit = {}
) : AgentDagNodeExecutor {
    override fun execute(
        node: AgentDagNode,
        inputs: AgentDagNodeInputs
    ): AgentDagNodeExecutionResult {
        require(node.actionId == LocalMaskedGenerationPreviewAction.ACTION_ID)
        require(node.phase == AgentActionPhase.TRANSFORM)
        require(!node.destructive && node.effects == setOf(AgentStateEffect.ADD_ARTIFACT))
        require(backend.available) { "masked generation backend is unavailable" }
        val parameters = JSONObject(node.parametersJson)
        require(parameters.keys().asSequence().toSet() == setOf(
            "effect_kind",
            "generation_prompt",
            "generation_prompt_source_text",
            "generation_mask_padding_px",
            "preview_only",
            "writeback",
            "single_frame_only"
        ))
        require(parameters.getString("effect_kind") == LocalGenerativeChange.EFFECT_KIND)
        require(parameters.getBoolean("preview_only"))
        require(!parameters.getBoolean("writeback"))
        require(parameters.getBoolean("single_frame_only"))
        val change = LocalGenerativeChange(
            parameters.getString("generation_prompt"),
            parameters.getString("generation_prompt_source_text")
        )
        val generationMaskPaddingPx = parameters.getInt("generation_mask_padding_px")
        require(generationMaskPaddingPx in 0..32)

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
            ?: throw IllegalArgumentException("scope certificate input is missing")
        val certificate = materialization.certificate
        val fingerprint = currentStateFingerprint()
        require(media.stateFingerprint == fingerprint)
        require(scopeValue.certificateId == certificate.certificateId)
        require(scopeValue.stateFingerprint == fingerprint)
        require(scopeValue.certificateFingerprint == certificate.evidenceFingerprint)
        require(certificate.readyForPreview(fingerprint))
        require(!certificate.readyForMutation(fingerprint))

        val preferred = materialization.maskTrack.preferredPreviewFrameIndex
        val observation = preferred?.let { frameIndex ->
            certificate.observations.firstOrNull {
                it.frameIndex == frameIndex && it.status == TrackObservationStatus.VISIBLE_VALIDATED
            }
        } ?: certificate.observations.firstOrNull {
            it.status == TrackObservationStatus.VISIBLE_VALIDATED
        } ?: error("mask track has no visible validated frame for generation")
        val maskFrame = requireNotNull(materialization.maskTrack.frame(observation.frameIndex))
        require(maskFrame.rawMaskPixels == observation.rawMaskPixels)
        val cleanedMask = AndroidGenerationRegionPolicy.retainSignificantComponents(
            maskFrame.copyMaskValues(),
            maskFrame.width,
            maskFrame.height
        )
        val cleanedPixels = cleanedMask.count { (it.toInt() and 0xff) > 0 }
        val maskAreaRatio = cleanedPixels.toDouble() /
            (maskFrame.width.toLong() * maskFrame.height).toDouble()
        require(maskAreaRatio >= AndroidGenerationRegionPolicy.MIN_MASK_AREA_RATIO) {
            "generation mask area $maskAreaRatio is too small for a reviewable preview"
        }
        val sourceFrame = requireNotNull(sourceFrames.resolve(
            sourceAssetId,
            observation.frameIndex,
            observation.timestampMs
        ))
        require(sourceFrame.assetId == sourceAssetId)
        require(sourceFrame.frameIndex == observation.frameIndex)
        require(sourceFrame.timestampMs == observation.timestampMs)
        require(!sourceFrame.bitmap.isRecycled)
        require(sourceFrame.bitmap.width == maskFrame.width &&
            sourceFrame.bitmap.height == maskFrame.height)

        val generated = try {
            val generationMask = AndroidGenerationRegionPolicy.dilateBinaryMask(
                cleanedMask,
                maskFrame.width,
                maskFrame.height,
                generationMaskPaddingPx
            )
            backend.generate(
                sourceFrame.bitmap,
                generationMask,
                change,
                certificate.evidenceFingerprint,
                cancelled,
                progress
            )
        } catch (failure: Throwable) {
            if (sourceFrame.ownedByPreview && !sourceFrame.bitmap.isRecycled) {
                sourceFrame.bitmap.recycle()
            }
            throw failure
        }
        val encodedTimeline = try {
            timelinePreviewRenderer?.render(
                materialization,
                change,
                generated.bitmap
            )
        } catch (failure: Throwable) {
            generated.bitmap.recycle()
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
                previewBitmap = generated.bitmap,
                effectKind = change.effectKind,
                rendererBackendId = generated.backendId,
                certificateFingerprint = certificate.evidenceFingerprint,
                backendEvidenceFingerprint = generated.evidenceFingerprint,
                ownsSourceBitmap = sourceFrame.ownedByPreview,
                encodedTimeline = encodedTimeline,
                publishEligible = false
            )
        } catch (failure: Throwable) {
            generated.bitmap.recycle()
            encodedTimeline?.cleanupRoot?.deleteRecursively()
            if (sourceFrame.ownedByPreview && !sourceFrame.bitmap.isRecycled) {
                sourceFrame.bitmap.recycle()
            }
            throw failure
        }
        try {
            onPreviewReady(interaction)
        } catch (failure: Throwable) {
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
            message = "private generated anchor preview is ready"
        )
    }

}
