package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidVideoFrameTimelineTest {
    @Test
    fun nearestFrameUsesActualPresentationTimestamps() {
        val timeline = timeline(longArrayOf(0, 31_000, 67_000, 101_000, 140_000))

        assertEquals(0, timeline.nearestFrameIndex(0))
        assertEquals(1, timeline.nearestFrameIndex(40_000))
        assertEquals(2, timeline.nearestFrameIndex(80_000))
        assertEquals(4, timeline.nearestFrameIndex(200_000))
        assertEquals(36_000L, timeline.medianPositiveFrameIntervalUs())
    }

    @Test
    fun duplicatePtsRemainValidButDoNotDefineFrameInterval() {
        val timeline = timeline(longArrayOf(0, 0, 40_000))

        assertEquals(0, timeline.nearestFrameIndex(0))
        assertEquals(40_000L, timeline.medianPositiveFrameIntervalUs())
    }

    @Test
    fun singleFrameHasNoEstimatedInterval() {
        assertNull(timeline(longArrayOf(900_000)).medianPositiveFrameIntervalUs())
    }

    @Test
    fun rejectsNonContiguousOrNonMonotonicTimeline() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidDecodedVideoTimeline(
                "video/avc", 10, 10, 0, 1,
                null,
                listOf(AndroidVideoFrameTime(1, 0))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            timeline(longArrayOf(10, 9))
        }
    }

    private fun timeline(times: LongArray) = AndroidDecodedVideoTimeline(
        mimeType = "video/avc",
        width = 1080,
        height = 1440,
        rotationDegrees = 0,
        containerDurationUs = times.lastOrNull()?.plus(33_000) ?: 0,
        nominalFrameRate = null,
        frames = times.mapIndexed { index, time -> AndroidVideoFrameTime(index, time) }
    )
}
