package com.vivo.lpa

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.security.MessageDigest

data class LibrarySyncReport(val changed: Int, val removed: Int, val skipped: Boolean)

/** Incremental VLM indexer. It only analyzes media when the MediaStore fingerprint changes. */
class LibraryIndexer(private val context: Context) {
    private val database = LibraryDatabase(context)
    private val embeddingVersion = "bge-small-zh-v1.5-mean-v1"

    fun sync(cancelled: () -> Boolean = { false }, progress: (String) -> Unit = {}): LibrarySyncReport {
        val entries = queryLivePhotos()
        val signature = sha256(entries.joinToString("\n") { "${it.uri}|${it.fingerprint}" })
        val preferences = context.getSharedPreferences("library-index", Context.MODE_PRIVATE)
        val embedder = BgeEmbedder(context).takeIf { it.available() }
        if (embedder != null && preferences.getString("embedding_version", "") != embeddingVersion) {
            database.clearEmbeddings()
            preferences.edit().putString("embedding_version", embeddingVersion).apply()
        }
        val complete = entries.all {
            database.isFresh(it.uri.toString(), it.fingerprint, embedder != null)
        }
        if (preferences.getString("signature", "") == signature && complete) {
            return LibrarySyncReport(0, 0, skipped = true)
        }
        val currentUris = entries.map { it.uri.toString() }.toSet()
        var removed = 0
        database.list().filter { it.mediaFormat == "livephoto" && it.sourceUri !in currentUris }
            .forEach { removed += database.delete(it.assetId) }

        var changed = 0
        val preprocessor = LivePhotoPreprocessor(context)
        entries.forEachIndexed { index, entry ->
            if (cancelled()) return@forEachIndexed
            if (database.isFresh(entry.uri.toString(), entry.fingerprint, embedder != null)) return@forEachIndexed
            progress("素材索引 ${index + 1}/${entries.size}：${entry.name}")
            val assets = preprocessor.prepare(
                listOf(entry.uri),
                cancelled = cancelled,
                progress = { message -> progress(message) },
                frameLimit = 1
            )
            assets.forEach { asset ->
                database.upsert(asset, entry.fingerprint)
                if (embedder != null) {
                    val semantic = asset.semantic
                    val searchText = buildString {
                        append(semantic.optString("summary")); append(' ')
                        append(semantic.optString("theme")); append(' ')
                        listOf("scene_tags", "subject_tags", "motion_tags", "audio_tags", "search_keywords")
                            .forEach { key ->
                                semantic.optJSONArray(key)?.let { values ->
                                    for (index in 0 until values.length()) {
                                        append(values.optString(index)); append(' ')
                                    }
                                }
                            }
                    }
                    runCatching {
                        database.setEmbedding(asset.sourceUri.toString(), entry.fingerprint, embedder.embed(searchText))
                    }.onFailure { progress("BGE 向量写入失败：${it.message}") }
                }
            }
            changed += assets.size
        }
        preferences.edit().putString("signature", signature).apply()
        return LibrarySyncReport(changed, removed, skipped = false)
    }

    private data class Entry(val uri: android.net.Uri, val name: String, val fingerprint: String)

    private fun queryLivePhotos(): List<Entry> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? OR live_photo IS NOT NULL",
            arrayOf("Pictures/LivePhotoAgent/"),
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
                    val modified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED))
                    val relativePath = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    ).orEmpty()
                    if (isGeneratedOutput(name, relativePath)) continue
                    add(Entry(
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        name,
                        "semantic-v3:$size:$modified"
                    ))
                }
            }
        }.orEmpty()
    }

    private fun isGeneratedOutput(name: String, relativePath: String): Boolean {
        val lower = name.lowercase()
        return relativePath.contains("/Generated/") ||
            lower.startsWith("live-result-") ||
            lower.startsWith("template_collage") ||
            lower.startsWith("smart_collage") ||
            lower.startsWith("vivo-")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
