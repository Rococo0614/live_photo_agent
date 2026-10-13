package com.vivo.lpa

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileInputStream

data class ImportReport(val imported: Int, val skipped: Int, val errors: List<String>)

/** Packs staged JPG/MP4 pairs into gallery-visible Motion Photo JPGs. */
class LivePhotoLibraryImporter(private val context: Context) {
    private val sdk = LivePhotoSdkAdapter(context)
    private val database = LibraryDatabase(context)

    fun importDirectory(
        imageRoot: File,
        originalVideoRoot: File,
        activeVideoRoot: File,
        progress: (String) -> Unit = {}
    ): ImportReport {
        val images = imageRoot.listFiles()
            ?.filter { it.isFile && it.extension.equals("jpg", true) }
            ?.sortedBy { it.name }
            .orEmpty()
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        images.forEachIndexed { index, image ->
            val stem = image.name.substringBeforeLast('.')
            val original = File(originalVideoRoot, "$stem.mp4")
            val active = File(activeVideoRoot, "$stem.mp4")
            // The active copies are normalized for VideoEditorSDK. Keep the
            // original files as archival sources, but embed the compatible
            // stream so later unpack -> edit -> export stays native.
            val video = active.takeIf { it.isFile } ?: original.takeIf { it.isFile }
            if (video == null) {
                skipped++
                errors += "$stem: MP4 不存在"
                return@forEachIndexed
            }
            progress("动态照片 ${index + 1}/${images.size}：$stem")
            var published: Uri? = null
            try {
                val staging = File(context.cacheDir, "livephoto-import")
                val encoded = sdk.pack(image, video, staging, presentationTimestampUs(video))
                published = publish(encoded, "$stem.jpg")
                database.upsertImported(published, "$stem.jpg")
                imported++
            } catch (error: Throwable) {
                published?.let { context.contentResolver.delete(it, null, null) }
                errors += "$stem: ${error.message ?: error::class.java.simpleName}"
            }
        }
        if (images.isNotEmpty() && imported == images.size && errors.isEmpty()) {
            removeStaticSources(imageRoot)
        }
        return ImportReport(imported, skipped, errors)
    }

    private fun removeStaticSources(imageRoot: File) {
        val prefix = imageRoot.absolutePath.trimEnd('/') + "/%"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DATA} LIKE ?",
            arrayOf(prefix),
            null
        )?.use { cursor ->
            val ids = buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
            ids.forEach { id ->
                runCatching {
                    context.contentResolver.delete(
                        Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()),
                        null,
                        null
                    )
                }
            }
        }
        imageRoot.listFiles()?.forEach { it.delete() }
        imageRoot.delete()
    }

    private fun publish(source: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            // vivo Gallery keys Motion Photo playback from this MediaStore
            // extension in addition to the embedded XMP container.
            put("live_photo", "motionphoto00010000000000000")
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LivePhotoAgent")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建相册记录：$displayName")
        try {
            FileInputStream(source).use { input ->
                resolver.openOutputStream(uri)?.use { output -> input.copyTo(output, 128 * 1024) }
                    ?: error("无法写入相册记录：$displayName")
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            // MediaProvider may ignore this vendor column during insert; the
            // post-publish update is what vivo Gallery actually reads.
            resolver.update(uri, ContentValues().apply {
                put("live_photo", "motionphoto00010000000000000")
            }, null, null)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun presentationTimestampUs(video: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 3000L
            (durationMs * 1000L / 2L).coerceIn(1_000_000L, 1_500_000L)
        } finally {
            retriever.release()
        }
    }
}
