package com.vivo.lpa

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Content-URI safe input normalization for photos, videos and motion photos. */
class LivePhotoPreprocessor(private val context: Context) {
    private val livePhotoSdk = LivePhotoSdkAdapter(context)

    fun prepare(
        uris: List<Uri>,
        cancelled: () -> Boolean = { false },
        progress: (String) -> Unit = {},
        frameLimit: Int = 3
    ): List<PreparedAsset> {
        val root = File(context.cacheDir, "agent-inputs").apply { mkdirs() }
        val vlm = MobileVlmPreprocessor(context)
        return uris.mapIndexed { index, uri ->
            check(!cancelled()) { "cancelled" }
            progress("预处理素材 ${index + 1}/${uris.size}")
            val assetDir = File(root, "asset-${System.currentTimeMillis()}-$index").apply { mkdirs() }
            val source = copyUri(uri, File(assetDir, displayName(uri, index)))
            val split = livePhotoSdk.unpack(source, File(assetDir, "sdk-decoded"))
                ?: splitMotionPhoto(source, assetDir)
            val image = split.first ?: if (isImage(source)) source else extractFrame(source, File(assetDir, "cover.jpg"))
            val sidecar = if (split.second == null && isImage(source)) {
                findSidecarVideo(source, assetDir)
            } else {
                null
            }
            val video = split.second ?: if (isVideo(source)) source else sidecar
            val metadata = metadata(source, image, video)
            val provisional = PreparedAsset(
                assetId = assetId(uri, source),
                sourceUri = uri,
                sourceFile = source,
                imageFile = image,
                videoFile = video,
                metadata = metadata,
                semantic = JSONObject()
            )
            val sourceFingerprint = sourceFingerprint(uri)
            val cachedSemantic = LibraryDatabase(context).readySemantic(uri.toString(), sourceFingerprint)
            val semantic = if (cachedSemantic != null) {
                MobileVlmPreprocessor.Result(cachedSemantic, true, "复用素材库 VLM 摘要")
            } else {
                val frames = if (video != null) extractFrames(video, assetDir).take(frameLimit.coerceAtLeast(1))
                else listOfNotNull(image)
                vlm.analyze(provisional, frames)
            }
            progress("${semantic.message}：${source.name}")
            provisional.copy(semantic = semantic.payload.put("ready", semantic.ready))
        }
    }

    private fun copyUri(uri: Uri, destination: File): File {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取素材 URI: $uri")
        input.use { source ->
            FileOutputStream(destination).use { output ->
                source.copyTo(output, 64 * 1024)
            }
        }
        return destination
    }

    private fun sourceFingerprint(uri: Uri): String {
        val values = context.contentResolver.query(
            uri,
            arrayOf("_size", "date_modified"),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) "${cursor.getLong(0)}:${cursor.getLong(1)}" else null
        }
        return values?.let { "semantic-v3:$it" } ?: uri.toString()
    }

    private fun findSidecarVideo(image: File, outputDir: File): File? {
        val displayName = image.name.substringBeforeLast('.', image.name) + ".mp4"
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA
        )
        val indexed = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Video.Media.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
            val dataIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            if (dataIndex >= 0) {
                val dataPath = cursor.getString(dataIndex).orEmpty()
                if (dataPath.isNotEmpty() && File(dataPath).isFile) return@use File(dataPath)
            }
            runCatching {
                copyUri(
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    File(outputDir, "motion-sidecar.mp4")
                )
            }.getOrNull()
        }
        if (indexed != null) return indexed
        val fallback = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "LivePhotoAgent/$displayName"
        )
        return runCatching {
            if (!fallback.isFile) null
            else fallback.copyTo(File(outputDir, "motion-sidecar.mp4"), overwrite = true)
        }.getOrNull()
    }

    private fun displayName(uri: Uri, index: Int): String {
        val fallback = "input-$index"
        val name = context.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        val safe = (name ?: fallback).replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (safe.contains('.')) safe else "$safe.bin"
    }

    private fun splitMotionPhoto(source: File, outputDir: File): Pair<File?, File?> {
        if (!source.name.lowercase(Locale.ROOT).endsWith(".jpg") &&
            !source.name.lowercase(Locale.ROOT).endsWith(".jpeg")) return null to null
        val mp4Start = findMp4Start(source) ?: return null to null
        val jpegEnd = findLastJpegEnd(source, mp4Start) ?: return null to null
        if (mp4Start < jpegEnd || source.length() - mp4Start < 16) return null to null
        val image = File(outputDir, "cover.jpg")
        val video = File(outputDir, "motion.mp4")
        copyRange(source, image, 0L, jpegEnd)
        copyRange(source, video, mp4Start, source.length() - mp4Start)
        return image to video
    }

    private fun findMp4Start(file: File): Long? {
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(64 * 1024)
            var offset = 0L
            var carry = ByteArray(0)
            while (offset < raf.length()) {
                raf.seek(offset)
                val read = raf.read(buffer)
                if (read <= 0) break
                val joined = ByteArray(carry.size + read)
                System.arraycopy(carry, 0, joined, 0, carry.size)
                System.arraycopy(buffer, 0, joined, carry.size, read)
                for (i in 0..joined.size - 4) {
                    if (joined[i] == 'f'.code.toByte() && joined[i + 1] == 't'.code.toByte() &&
                        joined[i + 2] == 'y'.code.toByte() && joined[i + 3] == 'p'.code.toByte()) {
                        val candidate = offset - carry.size + i - 4
                        if (candidate >= 0 && candidate + 8 <= raf.length()) {
                            raf.seek(candidate)
                            val size = raf.readInt()
                            val type = ByteArray(4)
                            raf.readFully(type)
                            if (String(type, Charsets.US_ASCII) == "ftyp" && size >= 8) return candidate
                        }
                    }
                }
                carry = joined.copyOfRange(maxOf(0, joined.size - 7), joined.size)
                offset += read
            }
        }
        return null
    }

    private fun findLastJpegEnd(file: File, before: Long): Long? {
        RandomAccessFile(file, "r").use { raf ->
            val limit = before.coerceAtMost(raf.length())
            val buffer = ByteArray(64 * 1024)
            var offset = 0L
            var previous = -1
            while (offset < limit) {
                val size = minOf(buffer.size.toLong(), limit - offset).toInt()
                raf.seek(offset)
                val read = raf.read(buffer, 0, size)
                if (read <= 0) break
                for (i in 0 until read - 1) {
                    if (buffer[i] == 0xff.toByte() && buffer[i + 1] == 0xd9.toByte()) {
                        previous = (offset + i).toInt()
                    }
                }
                offset += read
            }
            return if (previous >= 0) previous.toLong() + 2L else null
        }
    }

    private fun copyRange(source: File, destination: File, start: Long, length: Long) {
        FileInputStream(source).use { input ->
            input.channel.position(start)
            FileOutputStream(destination).use { output ->
                var remaining = length
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
    }

    private fun extractFrames(video: File, directory: File): List<File> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.times(1000L) ?: 0L
            val times = listOf(0L, durationUs / 2L, (durationUs * 9L) / 10L).distinct()
            times.mapIndexedNotNull { index, time ->
                val bitmap = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: return@mapIndexedNotNull null
                val output = File(directory, "frame-$index.jpg")
                FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bitmap.recycle()
                output
            }
        } finally {
            retriever.release()
        }
    }

    private fun extractFrame(source: File, output: File): File? {
        if (!isVideo(source)) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            val bitmap = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
            FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
            output
        } finally {
            retriever.release()
        }
    }

    private fun metadata(source: File, image: File?, video: File?): JSONObject {
        val result = JSONObject()
            .put("media_format", when {
                video != null && image != null -> "livephoto"
                video != null -> "video"
                image != null -> "image"
                else -> "unknown"
            })
            .put("bytes", source.length())
        if (image != null) {
            BitmapFactory.decodeFile(image.absolutePath)?.let {
                result.put("width", it.width).put("height", it.height)
                it.recycle()
            }
        }
        if (video != null) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(video.absolutePath)
                result.put("duration_ms", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
                result.put("video_width", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0)
                result.put("video_height", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0)
                result.put("rotation", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)
            } finally {
                retriever.release()
            }
        }
        return result
    }

    private fun isImage(file: File): Boolean = file.name.lowercase(Locale.ROOT).let {
        it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp")
    }

    private fun isVideo(file: File): Boolean = file.name.lowercase(Locale.ROOT).let {
        it.endsWith(".mp4") || it.endsWith(".mov") || it.endsWith(".3gp") || it.endsWith(".mkv")
    }

    private fun assetId(uri: Uri, file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "${uri}:${file.length()}".toByteArray()
        )
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
