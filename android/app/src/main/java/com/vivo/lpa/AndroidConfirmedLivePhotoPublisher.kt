package com.vivo.lpa

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class AndroidPublishedLocalPreview(
    val uri: Uri,
    val displayName: String,
    val contentSha256: String,
    val byteCount: Long
)

/** Pure fail-closed policy shared by the UI publisher and headless lifecycle probe. */
internal object AndroidLocalPreviewPublishPolicy {
    fun requirePublishable(
        cacheDir: File,
        lifecycle: LocalPreviewLifecycle,
        packedLivePhoto: File?,
        packedLivePhotoSha256: String?,
        timelineFrameCount: Int?,
        certificateFingerprint: String
    ): File {
        require(lifecycle.state == LocalPreviewReviewState.ACCEPTED_FOR_REVIEW) {
            "publishing requires a separately confirmed preview"
        }
        require(!lifecycle.writebackAuthorized) {
            "publishing a copy must not authorize source writeback"
        }
        require(Regex("[0-9a-f]{64}").matches(certificateFingerprint)) {
            "preview certificate fingerprint is invalid"
        }
        require(timelineFrameCount != null && timelineFrameCount > 1) {
            "publishing requires a complete dynamic timeline"
        }
        val packed = requireNotNull(packedLivePhoto) {
            "confirmed preview has no packed Live Photo"
        }.canonicalFile
        val allowedRoot = File(cacheDir, "full-timeline-previews").canonicalFile
        require(packed.isFile && packed.length() > 0L &&
            packed.toPath().startsWith(allowedRoot.toPath())
        ) { "packed preview is outside the private timeline artifact root" }
        require(packed.extension.equals("jpg", true) || packed.extension.equals("jpeg", true)) {
            "packed preview is not a JPEG Live Photo"
        }
        val expectedSha = requireNotNull(packedLivePhotoSha256) {
            "packed preview has no content binding"
        }
        require(Regex("[0-9a-f]{64}").matches(expectedSha) &&
            contentSha256(packed) == expectedSha
        ) { "packed preview content changed after confirmation" }
        return packed
    }

    fun contentSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/**
 * Publishes only an already-confirmed private Live Photo as a new MediaStore copy.
 * It never replaces the source asset and deletes an incomplete row on every failure.
 */
internal class AndroidConfirmedLivePhotoPublisher(private val context: Context) {
    fun publish(artifact: AndroidTemporaryLocalPreviewArtifact): AndroidPublishedLocalPreview {
        require(Build.VERSION.SDK_INT >= 29) {
            "confirmed Live Photo publishing requires scoped MediaStore"
        }
        val packed = AndroidLocalPreviewPublishPolicy.requirePublishable(
            context.cacheDir,
            artifact.lifecycle,
            artifact.packedLivePhotoFile,
            artifact.packedLivePhotoSha256,
            artifact.timelineFrameCount,
            artifact.certificateFingerprint
        )
        val expectedSha = requireNotNull(artifact.packedLivePhotoSha256)
        val expectedByteCount = packed.length()
        val validationRoot = File(
            context.cacheDir,
            "confirmed-publish-validation/${artifact.artifactId}-${System.nanoTime()}"
        ).apply { mkdirs() }
        try {
            val decoded = requireNotNull(
                LivePhotoSdkAdapter(context).decode(packed, validationRoot)
            ) { "confirmed preview cannot be decoded before publish" }
            val timeline = AndroidVideoFrameTimelineDecoder.decode(
                decoded.video,
                AtomicBoolean(false)
            )
            require(timeline.frameCount == artifact.timelineFrameCount) {
                "confirmed preview timeline changed before publish"
            }
        } finally {
            validationRoot.deleteRecursively()
        }

        val displayName = "LPA_AgentPreview_${System.currentTimeMillis()}.jpg"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, MobileAgentRunner.OUTPUT_IMAGE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put("live_photo", LIVE_PHOTO_MARKER)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("unable to create MediaStore row for confirmed preview")
        try {
            packed.inputStream().use { input ->
                resolver.openOutputStream(uri, "w")?.use { output ->
                    input.copyTo(output, 1024 * 1024)
                } ?: error("unable to open confirmed preview output")
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
                put("live_photo", LIVE_PHOTO_MARKER)
            }, null, null)
            val verified = resolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.RELATIVE_PATH,
                    MediaStore.Images.Media.IS_PENDING,
                    "live_photo"
                ),
                null,
                null,
                null
            )?.use { cursor ->
                    cursor.moveToFirst() &&
                    cursor.getString(0) == displayName &&
                    cursor.getLong(1) == expectedByteCount &&
                    cursor.getString(2) == MobileAgentRunner.OUTPUT_IMAGE_PATH + "/" &&
                    cursor.getInt(3) == 0 && cursor.getString(4).orEmpty().isNotBlank()
            } == true
            require(verified && sha256(uri) == expectedSha) {
                "published Live Photo copy failed content verification"
            }
            return AndroidPublishedLocalPreview(uri, displayName, expectedSha, expectedByteCount)
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    fun deletePublishedCopy(published: AndroidPublishedLocalPreview): Boolean {
        require(published.uri.authority == MediaStore.AUTHORITY) {
            "refusing to delete a non-MediaStore URI"
        }
        val owned = context.contentResolver.query(
            published.uri,
            arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH
            ),
            null,
            null,
            null
        )?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == published.displayName &&
                cursor.getString(1) == MobileAgentRunner.OUTPUT_IMAGE_PATH + "/"
        } == true
        require(owned && sha256(published.uri) == published.contentSha256) {
            "refusing to delete a MediaStore row not owned by this publisher"
        }
        return context.contentResolver.delete(published.uri, null, null) == 1
    }

    private fun sha256(file: File): String = file.inputStream().use(::digest)

    private fun sha256(uri: Uri): String = requireNotNull(
        context.contentResolver.openInputStream(uri)
    ) { "published Live Photo cannot be reopened" }.use(::digest)

    private fun digest(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val LIVE_PHOTO_MARKER = "motionphoto00010000000000000"
    }
}
