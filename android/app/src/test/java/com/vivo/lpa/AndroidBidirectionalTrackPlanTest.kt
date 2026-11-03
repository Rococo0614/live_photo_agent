package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBidirectionalTrackPlanTest {
    @Test
    fun anchorInMiddleCoversEveryDynamicFrameInBothDirections() {
        val plan = AndroidBidirectionalTrackPlan.from(timeline(5), 2_100)

        assertEquals(5, plan.expectedFrameCount)
        assertEquals(2, plan.anchorFrameIndex)
        assertEquals(listOf(2, 3, 4), plan.forward.map { it.sourceFrame.frameIndex })
        assertEquals(listOf(2, 1, 0), plan.backward.map { it.sourceFrame.frameIndex })
        assertTrue(plan.forward.first().conditioningFrame)
        assertTrue(plan.backward.first().conditioningFrame)
    }

    @Test
    fun edgeAnchorsStillCoverTimelineWithoutFrameCountAssumptions() {
        val first = AndroidBidirectionalTrackPlan.from(timeline(3), 0)
        val last = AndroidBidirectionalTrackPlan.from(timeline(3), 20_000)

        assertEquals(listOf(0, 1, 2), first.forward.map { it.sourceFrame.frameIndex })
        assertEquals(listOf(0), first.backward.map { it.sourceFrame.frameIndex })
        assertEquals(listOf(2), last.forward.map { it.sourceFrame.frameIndex })
        assertEquals(listOf(2, 1, 0), last.backward.map { it.sourceFrame.frameIndex })
    }

    private fun timeline(count: Int) = AndroidDecodedVideoTimeline(
        mimeType = "video/avc",
        width = 10,
        height = 10,
        rotationDegrees = 0,
        containerDurationUs = count * 1_000L,
        nominalFrameRate = null,
        frames = List(count) { index -> AndroidVideoFrameTime(index, index * 1_000L) }
    )
}
