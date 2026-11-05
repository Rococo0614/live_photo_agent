package com.vivo.lpa

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidEncodedTimelinePreviewTest {
    @Test
    fun acceptsPrivateMotionAndPackedFilesInsideCleanupRoot() {
        val root = Files.createTempDirectory("encoded-timeline-preview").toFile()
        try {
            val motion = root.resolve("motion.mp4").apply { writeBytes(byteArrayOf(1)) }
            val packed = root.resolve("packed.jpg").apply { writeBytes(byteArrayOf(2)) }

            val preview = AndroidEncodedTimelinePreview(
                packedLivePhoto = packed,
                motionVideo = motion,
                cleanupRoot = root,
                frameCount = 5,
                photoTimestampUs = 80_000L
            )

            assertEquals(motion.canonicalFile, preview.motionVideo.canonicalFile)
            assertEquals(packed.canonicalFile, preview.packedLivePhoto.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsMotionFileOutsideOwnedCleanupRoot() {
        val root = Files.createTempDirectory("encoded-timeline-preview-root").toFile()
        val outside = Files.createTempDirectory("encoded-timeline-preview-outside").toFile()
        try {
            val motion = outside.resolve("motion.mp4").apply { writeBytes(byteArrayOf(1)) }
            val packed = root.resolve("packed.jpg").apply { writeBytes(byteArrayOf(2)) }

            assertThrows(IllegalArgumentException::class.java) {
                AndroidEncodedTimelinePreview(
                    packedLivePhoto = packed,
                    motionVideo = motion,
                    cleanupRoot = root,
                    frameCount = 5,
                    photoTimestampUs = 80_000L
                )
            }
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
