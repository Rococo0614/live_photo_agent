package com.vivo.lpa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RetrievalQueryGuardTest {
    @Test fun rejectsEditOperationAsContent() {
        assertFalse(RetrievalQueryGuard.isUsable("给我的猫做个三拼", "三拼"))
    }

    @Test fun acceptsConcreteSubjectFromOriginalRequest() {
        assertTrue(RetrievalQueryGuard.isUsable("给我的猫做个三拼", "猫"))
        assertTrue(RetrievalQueryGuard.isUsable("从相册找海边的视频倒放", "海边"))
    }

    @Test fun rejectsContradictoryConcreteSubject() {
        assertFalse(RetrievalQueryGuard.isUsable("给我的猫做个三拼", "狗"))
    }

    @Test fun acceptsGenericMediaWhenUserNamedNoContentSubject() {
        assertTrue(RetrievalQueryGuard.isUsable("把视频倒放", "视频"))
    }

    @Test fun acceptsEnglishContentOverlap() {
        assertTrue(RetrievalQueryGuard.isUsable("make a collage of my cat", "cat"))
        assertFalse(RetrievalQueryGuard.isUsable("make a collage of my cat", "dog"))
    }

    @Test fun canonicalizesVerboseAlbumQueryToVisualSubject() {
        assertEquals(
            "海边",
            RetrievalQueryGuard.canonicalize(
                "用海边的照片做个拼图",
                "最近相册中所有包含海边的照片"
            )
        )
        assertEquals("狗", RetrievalQueryGuard.canonicalize("做个狗的四拼", "狗的四拼"))
        assertEquals("美食", RetrievalQueryGuard.canonicalize("找几张美食照片拼起来", "美食照片"))
    }
}
