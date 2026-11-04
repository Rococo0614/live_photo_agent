package com.vivo.lpa

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidLocalPreviewPublishPolicyTest {
    @Test
    fun acceptedCompletePrivateTimelineIsPublishable() {
        val cache = Files.createTempDirectory("publish-policy").toFile()
        val packed = cache.resolve("full-timeline-previews/cert/run/preview.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val accepted = LocalPreviewLifecycleReducer.acceptForReview(
            LocalPreviewLifecycleReducer.create("artifact", 1),
            2
        )

        assertEquals(
            packed.canonicalFile,
            AndroidLocalPreviewPublishPolicy.requirePublishable(
                cache,
                accepted,
                packed,
                AndroidLocalPreviewPublishPolicy.contentSha256(packed),
                53,
                "a".repeat(64)
            )
        )
        cache.deleteRecursively()
    }

    @Test
    fun pendingPreviewCannotBePublished() {
        val cache = Files.createTempDirectory("publish-policy-pending").toFile()
        val packed = cache.resolve("full-timeline-previews/cert/preview.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }

        assertThrows(IllegalArgumentException::class.java) {
            AndroidLocalPreviewPublishPolicy.requirePublishable(
                cache,
                LocalPreviewLifecycleReducer.create("artifact", 1),
                packed,
                AndroidLocalPreviewPublishPolicy.contentSha256(packed),
                5,
                "b".repeat(64)
            )
        }
        cache.deleteRecursively()
    }

    @Test
    fun artifactOutsidePrivateTimelineRootCannotBePublished() {
        val cache = Files.createTempDirectory("publish-policy-root").toFile()
        val packed = cache.resolve("escape.jpg").apply { writeBytes(byteArrayOf(1)) }
        val accepted = LocalPreviewLifecycleReducer.acceptForReview(
            LocalPreviewLifecycleReducer.create("artifact", 1),
            2
        )

        assertThrows(IllegalArgumentException::class.java) {
            AndroidLocalPreviewPublishPolicy.requirePublishable(
                cache,
                accepted,
                packed,
                AndroidLocalPreviewPublishPolicy.contentSha256(packed),
                5,
                "c".repeat(64)
            )
        }
        cache.deleteRecursively()
    }

    @Test
    fun packedArtifactChangedAfterConfirmationCannotBePublished() {
        val cache = Files.createTempDirectory("publish-policy-content").toFile()
        val packed = cache.resolve("full-timeline-previews/cert/preview.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val accepted = LocalPreviewLifecycleReducer.acceptForReview(
            LocalPreviewLifecycleReducer.create("artifact", 1),
            2
        )
        val originalSha = AndroidLocalPreviewPublishPolicy.contentSha256(packed)
        packed.writeBytes(byteArrayOf(3, 2, 1))

        assertThrows(IllegalArgumentException::class.java) {
            AndroidLocalPreviewPublishPolicy.requirePublishable(
                cache,
                accepted,
                packed,
                originalSha,
                5,
                "d".repeat(64)
            )
        }
        cache.deleteRecursively()
    }
}
