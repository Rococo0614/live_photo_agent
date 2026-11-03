package com.vivo.lpa

enum class AndroidTrackDirection {
    FORWARD,
    BACKWARD
}

data class AndroidTrackSequenceStep(
    val direction: AndroidTrackDirection,
    val sequenceIndex: Int,
    val sourceFrame: AndroidVideoFrameTime,
    val conditioningFrame: Boolean
)

data class AndroidBidirectionalTrackPlan(
    val expectedFrameCount: Int,
    val anchorFrameIndex: Int,
    val forward: List<AndroidTrackSequenceStep>,
    val backward: List<AndroidTrackSequenceStep>
) {
    init {
        require(expectedFrameCount > 0)
        require(anchorFrameIndex in 0 until expectedFrameCount)
        require(forward.first().conditioningFrame)
        require(backward.first().conditioningFrame)
        require(forward.first().sourceFrame.frameIndex == anchorFrameIndex)
        require(backward.first().sourceFrame.frameIndex == anchorFrameIndex)
        require(forward.map { it.sequenceIndex } == forward.indices.toList())
        require(backward.map { it.sequenceIndex } == backward.indices.toList())
        val covered = (forward + backward)
            .map { it.sourceFrame.frameIndex }
            .toSet()
        require(covered == (0 until expectedFrameCount).toSet())
    }

    companion object {
        fun from(
            timeline: AndroidDecodedVideoTimeline,
            photoTimestampUs: Long
        ): AndroidBidirectionalTrackPlan {
            val anchor = timeline.nearestFrameIndex(photoTimestampUs)
            val forwardFrames = timeline.frames.subList(anchor, timeline.frameCount)
            val backwardFrames = timeline.frames.subList(0, anchor + 1).asReversed()
            fun steps(
                direction: AndroidTrackDirection,
                frames: List<AndroidVideoFrameTime>
            ) = frames.mapIndexed { sequenceIndex, frame ->
                AndroidTrackSequenceStep(
                    direction,
                    sequenceIndex,
                    frame,
                    conditioningFrame = sequenceIndex == 0
                )
            }
            return AndroidBidirectionalTrackPlan(
                timeline.frameCount,
                anchor,
                steps(AndroidTrackDirection.FORWARD, forwardFrames),
                steps(AndroidTrackDirection.BACKWARD, backwardFrames)
            )
        }
    }
}
