package com.vivo.lpa

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPreviewArtifactJanitorTest {
    @Test
    fun onlyOrphanedFullTimelineRootsAreDeleted() {
        val cache = Files.createTempDirectory("preview-janitor").toFile()
        val first = cache.resolve("full-timeline-previews/first").apply { mkdirs() }
        first.resolve("packed.jpg").writeBytes(ByteArray(7))
        val second = cache.resolve("full-timeline-previews/second/nested").apply { mkdirs() }
        second.resolve("motion.mp4").writeBytes(ByteArray(11))
        val unrelated = cache.resolve("unrelated/keep.bin").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(5))
        }

        val report = AndroidPreviewArtifactJanitor.clean(cache)

        assertEquals(2, report.scannedRootCount)
        assertEquals(2, report.deletedRootCount)
        assertEquals(18L, report.deletedByteCount)
        assertEquals(0, report.failedRootCount)
        assertFalse(first.exists())
        assertFalse(second.parentFile!!.exists())
        assertTrue(unrelated.isFile)
        cache.deleteRecursively()
    }
}
