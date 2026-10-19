package com.vivo.lpa

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.security.MessageDigest

data class LibrarySyncReport(
    val changed: Int,
    val removed: Int,
    val skipped: Boolean,
    /** Gallery live photos still lacking a current-version parse after this pass. */
    val pending: Int = 0,
    val total: Int = 0
)

/** Incremental VLM indexer. It only analyzes media when the MediaStore fingerprint changes. */
class LibraryIndexer(private val context: Context) {
    private val database = LibraryDatabase(context)
    private val embeddingVersion = "bge-small-zh-v1.5-mean-v1"

    /** Agent output locations, owned by the publisher so the two sides cannot drift. */
    private val outputDirs = listOf(
        MobileAgentRunner.OUTPUT_IMAGE_PATH.trimEnd('/'),
        MobileAgentRunner.OUTPUT_VIDEO_PATH.trimEnd('/')
    )

    fun sync(cancelled: () -> Boolean = { false }, progress: (String) -> Unit = {}): LibrarySyncReport {
        val entries = queryLivePhotos()
        val signature = sha256(entries.joinToString("\n") { "${it.uri}|${it.fingerprint}" })
        val preferences = context.getSharedPreferences("library-index", Context.MODE_PRIVATE)
        val embedder = BgeEmbedder(context).takeIf { it.available() }
        if (embedder != null && preferences.getString("embedding_version", "") != embeddingVersion) {
            database.clearEmbeddings()
            preferences.edit().putString("embedding_version", embeddingVersion).apply()
        }
        // Runs before the freshness check on purpose: a row from an older analysis
        // version must not be mistaken for an up-to-date one and short-circuit the sync.
        val purged = database.purgeStaleAnalysis(MobileVlmPreprocessor.ANALYSIS_VERSION)
        if (purged > 0) progress("清理旧版本解析结果 $purged 条，将按当前模型重新分析")

        val complete = entries.all {
            database.isFresh(it.uri.toString(), it.fingerprint, embedder != null)
        }
        if (preferences.getString("signature", "") == signature && complete) {
            return LibrarySyncReport(0, 0, skipped = true, pending = 0, total = entries.size)
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
                // Frames are sampled at 0% / 33% / 66% / 100% and packed into a 2x2 grid,
                // so the VLM sees the whole motion arc in a single inference. This must
                // match MobileVlmPreprocessor.MAX_GRID_FRAMES: a smaller limit silently
                // leaves a grid cell blank, which is what the 4-frame layout exists to avoid.
                frameLimit = MobileVlmPreprocessor.MAX_GRID_FRAMES,
                // Only the semantic payload is persisted, so drop the decoded copies
                // instead of accumulating ~15 MB per asset on every indexing pass.
                keepFiles = false
            )
            assets.forEach { asset ->
                database.upsert(asset, entry.fingerprint)
                // Only embed a parse that actually succeeded. For a failed one the payload's
                // summary is just the filename, so embedding it would put a noise vector into
                // the retrieval pool -- and searchEmbedding() ranks by vector without
                // re-checking status, so the noise would be reachable by real queries.
                val analyzed = asset.semantic.optBoolean("ready", false) ||
                    asset.semantic.optString("status") == "ready"
                if (embedder != null && analyzed) {
                    val semantic = asset.semantic
                    val searchText = buildString {
                        append(semantic.optString("summary")); append(' ')
                        append(semantic.optString("theme")); append(' ')
                        listOf("scene_tags", "subject_tags", "motion_tags", "search_keywords")
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
        val pending = entries.count {
            !database.isFresh(it.uri.toString(), it.fingerprint, embedder != null)
        }
        return LibrarySyncReport(changed, removed, skipped = false, pending = pending, total = entries.size)
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
                    if (isGeneratedOutput(relativePath)) continue
                    add(Entry(
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        name,
                        "semantic-v3:$size:$modified"
                    ))
                }
            }
        }.orEmpty()
    }

    /**
     * True when the row is something the agent itself published, not a user asset.
     *
     * Matched purely on directory, never on filename: results are named by the tool that
     * produced them plus a timestamp, so a prefix list silently rots whenever a tool is
     * renamed or added, and a user photo that happens to start with "vivo-" would be
     * dropped by mistake. The paths come from MobileAgentRunner, the side that writes them.
     */
    private fun isGeneratedOutput(relativePath: String): Boolean {
        val normalized = relativePath.trimEnd('/')
        return outputDirs.any { normalized == it || normalized.startsWith("$it/") }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
