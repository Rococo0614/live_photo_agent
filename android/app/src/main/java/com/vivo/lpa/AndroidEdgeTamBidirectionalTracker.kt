package com.vivo.lpa

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

fun interface AndroidEdgeTamFrameInputMaterializer {
    fun materialize(frame: AndroidVideoFrameTime, destination: File)
}

data class AndroidEdgeTamTrackedFrame(
    val frameIndex: Int,
    val presentationTimeUs: Long,
    val maskFile: File,
    val rawMaskPixels: Int,
    val modelObjectPresent: Boolean,
    val evidenceFingerprint: String
)

data class AndroidEdgeTamBidirectionalTrackResult(
    val timeline: AndroidDecodedVideoTimeline,
    val anchorFrameIndex: Int,
    val frames: List<AndroidEdgeTamTrackedFrame>
) {
    init {
        require(frames.size == timeline.frameCount)
        require(frames.map { it.frameIndex } == timeline.frames.indices.toList())
        require(frames.zip(timeline.frames).all { (tracked, source) ->
            tracked.presentationTimeUs == source.presentationTimeUs
        })
    }
}

/** Dynamic-N bidirectional tracker. Masks are spooled to disk instead of retained as full bitmaps. */
class AndroidEdgeTamBidirectionalTracker(
    private val temporalRunner: AndroidEdgeTamTemporalQnnRunner
) {
    fun track(
        timeline: AndroidDecodedVideoTimeline,
        photoTimestampUs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        initialPromptCoordinates: FloatArray,
        outputDirectory: File,
        cancelled: AtomicBoolean,
        materializer: AndroidEdgeTamFrameInputMaterializer,
        progress: (String) -> Unit = {}
    ): AndroidEdgeTamBidirectionalTrackResult {
        require(initialPromptCoordinates.size == 4)
        require(sourceWidth > 0 && sourceHeight > 0)
        outputDirectory.mkdirs()
        val work = File(outputDirectory, "inputs").apply { mkdirs() }
        val masks = File(outputDirectory, "masks").apply { mkdirs() }
        val plan = AndroidBidirectionalTrackPlan.from(timeline, photoTimestampUs)
        val results = linkedMapOf<Int, AndroidEdgeTamTrackedFrame>()
        val forwardState = temporalRunner.createState()

        fun runStep(
            step: AndroidTrackSequenceStep,
            state: AndroidEdgeTamTemporalState,
            prompt: FloatArray?,
            produceMemory: Boolean
        ): AndroidEdgeTamTemporalFrameResult {
            if (cancelled.get()) throw InterruptedException("bidirectional tracking cancelled")
            val input = File(work, "${step.direction.name.lowercase()}-${step.sourceFrame.frameIndex}.raw")
            materializer.materialize(step.sourceFrame, input)
            require(input.isFile && input.length() == AndroidEdgeTamTemporalQnnRunner.IMAGE_INPUT_BYTES)
            return try {
                temporalRunner.processRawFrame(
                    sequenceIndex = step.sequenceIndex,
                    imageInput = input,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    initialPromptCoordinates = prompt,
                    state = state,
                    cancelled = cancelled,
                    produceMemory = produceMemory,
                    progress = progress
                )
            } finally {
                input.delete()
            }
        }

        val anchorStep = plan.forward.first()
        val hasPropagation = plan.forward.size > 1 || plan.backward.size > 1
        progress("EdgeTAM conditioning frame ${anchorStep.sourceFrame.frameIndex + 1}/${timeline.frameCount}")
        val anchor = runStep(
            anchorStep,
            forwardState,
            initialPromptCoordinates,
            produceMemory = hasPropagation
        )
        results[anchorStep.sourceFrame.frameIndex] = spool(anchorStep.sourceFrame, anchor, masks)
        if (hasPropagation) {
            val backwardState = forwardState.snapshotCopy()
            plan.forward.drop(1).forEachIndexed { index, step ->
                progress("EdgeTAM forward frame ${step.sourceFrame.frameIndex + 1}/${timeline.frameCount}")
                val result = runStep(
                    step,
                    forwardState,
                    prompt = null,
                    produceMemory = index < plan.forward.size - 2
                )
                results[step.sourceFrame.frameIndex] = spool(step.sourceFrame, result, masks)
            }
            plan.backward.drop(1).forEachIndexed { index, step ->
                progress("EdgeTAM backward frame ${step.sourceFrame.frameIndex + 1}/${timeline.frameCount}")
                val result = runStep(
                    step,
                    backwardState,
                    prompt = null,
                    produceMemory = index < plan.backward.size - 2
                )
                results[step.sourceFrame.frameIndex] = spool(step.sourceFrame, result, masks)
            }
        }
        work.deleteRecursively()
        val sorted = results.values.sortedBy { it.frameIndex }
        require(sorted.size == timeline.frameCount) {
            "bidirectional tracker covered ${sorted.size}/${timeline.frameCount} frames"
        }
        return AndroidEdgeTamBidirectionalTrackResult(
            timeline,
            plan.anchorFrameIndex,
            sorted
        )
    }

    private fun spool(
        source: AndroidVideoFrameTime,
        result: AndroidEdgeTamTemporalFrameResult,
        directory: File
    ): AndroidEdgeTamTrackedFrame {
        val maskFile = File(directory, "mask-${source.frameIndex.toString().padStart(6, '0')}.raw")
        maskFile.writeBytes(result.maskValues)
        return AndroidEdgeTamTrackedFrame(
            source.frameIndex,
            source.presentationTimeUs,
            maskFile,
            result.rawMaskPixels,
            result.modelObjectPresent,
            result.evidenceFingerprint
        )
    }
}
