package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestFactExtractorTest {
    @Test
    fun extractsCountsAcrossMediaUnits() {
        assertEquals(1, RequestFactExtractor.explicitCount("找一段猫的视频"))
        assertEquals(3, RequestFactExtractor.explicitCount("找三张猫做三拼"))
        assertEquals(2, RequestFactExtractor.explicitCount("选择两条视频"))
        assertEquals(4, RequestFactExtractor.explicitCount("make a collage from four photos"))
        assertEquals(1, RequestFactExtractor.explicitCount("reverse one clip"))
    }

    @Test
    fun doesNotTreatEffectNumbersAsAssetCounts() {
        assertNull(RequestFactExtractor.explicitCount("把视频加速两倍"))
        assertNull(RequestFactExtractor.explicitCount("从第3秒开始倒放"))
    }
}
