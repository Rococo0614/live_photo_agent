package com.vivo.lpa

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

data class LibraryRecord(
    val assetId: String,
    val sourceUri: String,
    val displayName: String,
    val mediaFormat: String,
    val metadata: JSONObject,
    val semantic: JSONObject,
    val fingerprint: String,
    val updatedAt: Long
)

data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** Small on-device catalog for parsed media and VLM results. */
class LibraryDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "live_photo_agent.db",
    null,
    6
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE assets (
                asset_id TEXT PRIMARY KEY NOT NULL,
                source_uri TEXT NOT NULL,
                display_name TEXT NOT NULL,
                media_format TEXT NOT NULL,
                metadata_json TEXT NOT NULL,
                semantic_json TEXT NOT NULL,
                fingerprint TEXT NOT NULL DEFAULT '',
                search_embedding BLOB,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX assets_name_idx ON assets(display_name)")
        db.execSQL("CREATE INDEX assets_updated_idx ON assets(updated_at DESC)")
        createChatTables(db)
        db.execSQL(
            "CREATE TABLE interaction_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "kind TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE assets ADD COLUMN fingerprint TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 3) db.execSQL(
            "CREATE TABLE IF NOT EXISTS interaction_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, " +
                "payload TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
        if (oldVersion < 4) db.execSQL("ALTER TABLE assets ADD COLUMN search_embedding BLOB")
        if (oldVersion < 5) createChatTables(db)
        if (oldVersion >= 5 && oldVersion < 6) {
            db.execSQL("ALTER TABLE chat_sessions ADD COLUMN session_state_json TEXT NOT NULL DEFAULT '{}'")
        }
    }

    fun upsert(asset: PreparedAsset, fingerprint: String = "${asset.sourceUri}:${asset.sourceFile.length()}") {
        val existingId = lookupAssetId(asset.sourceUri.toString()) ?: asset.assetId
        upsert(
            assetId = existingId,
            sourceUri = asset.sourceUri.toString(),
            displayName = asset.sourceFile.name,
            mediaFormat = asset.metadata.optString("media_format", "unknown"),
            metadata = asset.metadata,
            semantic = asset.semantic,
            fingerprint = fingerprint
        )
    }

    fun upsertImported(uri: Uri, displayName: String, metadata: JSONObject = JSONObject()) {
        upsert(
            assetId = "motion:$displayName",
            sourceUri = uri.toString(),
            displayName = displayName,
            mediaFormat = "livephoto",
            metadata = metadata,
            semantic = JSONObject().put("status", "pending"),
            fingerprint = "imported:$displayName"
        )
    }

    fun upsert(
        assetId: String,
        sourceUri: String,
        displayName: String,
        mediaFormat: String,
        metadata: JSONObject,
        semantic: JSONObject,
        fingerprint: String = ""
    ) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("asset_id", assetId)
            put("source_uri", sourceUri)
            put("display_name", displayName)
            put("media_format", mediaFormat)
            put("metadata_json", metadata.toString())
            put("semantic_json", semantic.toString())
            put("fingerprint", fingerprint)
            put("created_at", now)
            put("updated_at", now)
        }
        writableDatabase.insertWithOnConflict("assets", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateSemantic(assetId: String, semantic: JSONObject) {
        writableDatabase.update(
            "assets",
            ContentValues().apply {
                put("semantic_json", semantic.toString())
                put("updated_at", System.currentTimeMillis())
            },
            "asset_id = ?",
            arrayOf(assetId)
        )
    }

    fun setEmbedding(sourceUri: String, fingerprint: String, embedding: FloatArray) {
        writableDatabase.update(
            "assets",
            ContentValues().apply {
                put("search_embedding", floatsToBytes(embedding))
                put("updated_at", System.currentTimeMillis())
            },
            "source_uri = ? AND fingerprint = ?",
            arrayOf(sourceUri, fingerprint)
        )
    }

    fun clearEmbeddings() {
        writableDatabase.execSQL("UPDATE assets SET search_embedding = NULL")
    }

    /**
     * Drops every row whose semantic payload was not produced by the current analysis
     * version, so a prompt or frame-sampling change can never leave a stale parse behind.
     *
     * Stale rows are deleted rather than filtered at query time: their search vectors were
     * built from superseded text (e.g. the removed audio_tags), so keeping them around
     * means keeping vectors that no longer correspond to anything the pipeline produces.
     * The indexer recreates the rows on the next pass.
     */
    fun purgeStaleAnalysis(currentVersion: String): Int = writableDatabase.delete(
        "assets",
        "semantic_json IS NULL OR semantic_json NOT LIKE ?",
        arrayOf("%\"analysis_version\":\"$currentVersion\"%")
    )

    fun searchEmbedding(query: FloatArray, limit: Int, queryText: String = ""): List<LibraryRecord> =
        searchEmbeddingScored(query, limit, queryText).map { it.first }

    /**
     * Same ranking as [searchEmbedding], but keeps the cosine score and the tag-hit count.
     *
     * Retrieval is otherwise a black box: when the wrong asset comes back there is no way to tell
     * a weak embedding from a wrong search phrase. Exposing the scores is what makes a bad
     * replacement diagnosable from the audit log.
     */
    fun searchEmbeddingScored(
        query: FloatArray,
        limit: Int,
        queryText: String = ""
    ): List<Pair<LibraryRecord, Double>> {
        val records = list()
        val scored = records.mapNotNull { record ->
            val blob = readableDatabase.query(
                "assets", arrayOf("search_embedding"), "asset_id = ?", arrayOf(record.assetId), null, null, null
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
            val vector = blob?.let(::bytesToFloats) ?: return@mapNotNull null
            var score = 0.0
            query.indices.forEach { index -> if (index < vector.size) score += query[index] * vector[index] }
            record to score
        }
        val terms = queryTerms(queryText)
        val exact = scored.map { (record, score) ->
            val semantic = record.semantic.toString().lowercase()
            val hits = terms.count { semantic.contains(it) }
            Triple(record, score, hits)
        }
        val hasExplicitTagHit = exact.any { it.third > 0 }
        return exact.sortedWith(
            compareByDescending<Triple<LibraryRecord, Double, Int>> { if (hasExplicitTagHit) it.third else 0 }
                .thenByDescending { it.second }
        ).take(limit).map { it.first to it.second }
    }

    private fun queryTerms(query: String): List<String> {
        val lower = query.lowercase()
        val han = lower.filter { it.code in 0x4e00..0x9fff }
        val hanTerms = if (han.length <= 1) listOf(han) else {
            listOf(han) + han.windowed(2, 1, partialWindows = false)
        }
        val latin = lower.split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }
        return (hanTerms + latin).distinct()
    }

    fun list(query: String = ""): List<LibraryRecord> {
        val normalized = query.trim()
        val selection = if (normalized.isEmpty()) null else
            "display_name LIKE ? OR semantic_json LIKE ?"
        val args = if (normalized.isEmpty()) null else {
            val pattern = "%$normalized%"
            arrayOf(pattern, pattern)
        }
        return readableDatabase.query(
            "assets", null, selection, args, null, null, "updated_at DESC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    LibraryRecord(
                        assetId = cursor.getString(cursor.getColumnIndexOrThrow("asset_id")),
                        sourceUri = cursor.getString(cursor.getColumnIndexOrThrow("source_uri")),
                        displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                        mediaFormat = cursor.getString(cursor.getColumnIndexOrThrow("media_format")),
                        metadata = JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("metadata_json"))),
                        semantic = JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("semantic_json"))),
                        fingerprint = cursor.getString(cursor.getColumnIndexOrThrow("fingerprint")),
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                    )
                )
            }
        }
    }

    /**
     * True when this asset needs no further work from the indexer.
     *
     * Two shapes qualify, both stamped with the current analysis version:
     *  - a successful parse (status=ready)
     *  - a settled failure, i.e. one that cannot heal by retrying
     *
     * Settled failures must count as fresh. The VLM sampler uses a fixed seed, so
     * re-running it on the same frames reproduces the same bad output forever; without
     * this, every launch would re-analyse those assets and the pending count would never
     * reach zero. Bumping ANALYSIS_VERSION after a prompt change clears them again.
     */
    /**
     * True when this asset needs no further work from the indexer.
     *
     * Two shapes qualify, both stamped with the current analysis version:
     *  - a successful parse (status=ready), which must also carry its embedding
     *  - a settled failure, which by design has no embedding
     *
     * The embedding requirement is scoped to the ready branch on purpose. A settled row is
     * one that cannot heal by retrying (the sampler uses a fixed seed, so the same frames
     * reproduce the same bad output), and LibraryIndexer deliberately does not embed
     * failed parses. Requiring an embedding there would make the row permanently not-fresh,
     * so every launch would re-run VLM on it and the pending count would never reach zero.
     */
    fun isFresh(sourceUri: String, fingerprint: String, requireEmbedding: Boolean = false): Boolean {
        val versionClause = "semantic_json LIKE ?"
        val readyClause = "(semantic_json LIKE ? OR semantic_json LIKE ?)"
        val settledClause = "semantic_json LIKE ?"
        val embeddingClause = if (requireEmbedding) " AND search_embedding IS NOT NULL" else ""
        return readableDatabase.query(
            "assets", arrayOf("asset_id"),
            "source_uri = ? AND fingerprint = ? AND $versionClause AND " +
                "($readyClause$embeddingClause OR $settledClause)",
            arrayOf(
                sourceUri, fingerprint,
                "%\"analysis_version\":\"${MobileVlmPreprocessor.ANALYSIS_VERSION}\"%",
                "%\"status\":\"ready\"%", "%\"ready\":true%",
                "%\"settled\":true%"
            ),
            null, null, null
        ).use { it.moveToFirst() }
    }

    fun readySemantic(sourceUri: String, fingerprint: String): JSONObject? = readableDatabase.query(
        "assets", arrayOf("semantic_json"),
        "source_uri = ? AND fingerprint = ? AND (semantic_json LIKE ? OR semantic_json LIKE ?) " +
            "AND semantic_json LIKE ?",
        arrayOf(
            sourceUri, fingerprint,
            "%\"status\":\"ready\"%", "%\"ready\":true%",
            "%\"analysis_version\":\"${MobileVlmPreprocessor.ANALYSIS_VERSION}\"%"
        ),
        null, null, null
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else JSONObject(cursor.getString(0))
    }

    private fun lookupAssetId(sourceUri: String): String? = readableDatabase.query(
        "assets", arrayOf("asset_id"), "source_uri = ?", arrayOf(sourceUri), null, null, null
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun recommend(query: String, limit: Int = 3): List<LibraryRecord> {
        val records = list().filter {
            it.semantic.optBoolean("ready", false) || it.semantic.optString("status") == "ready"
        }
        if (records.isEmpty()) return emptyList()
        val raw = query.lowercase()
        val han = raw.filter { it.code in 0x4e00..0x9fff }
        val hanTerms = if (han.length <= 1) listOf(han) else {
            listOf(han) + han.windowed(2, 1, partialWindows = false)
        }
        val terms: List<String> = raw
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 } + hanTerms
        return records.sortedByDescending { record ->
            val haystack = (record.displayName + " " + record.semantic.toString()).lowercase()
            terms.fold(0) { score, term -> score + if (haystack.contains(term)) 12 else 0 } +
                if (record.semantic.optBoolean("ready", false) ||
                    record.semantic.optString("status") == "ready") 2 else 0
        }.take(limit)
    }

    fun requestedCount(query: String, fallback: Int = 3): Int {
        return RequestFactExtractor.explicitCount(query) ?: fallback
    }

    fun delete(assetId: String): Int = writableDatabase.delete("assets", "asset_id = ?", arrayOf(assetId))

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM assets", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun createSession(title: String = "新对话"): String {
        val id = "session-${System.currentTimeMillis()}-${(1000..9999).random()}"
        val now = System.currentTimeMillis()
        writableDatabase.insert("chat_sessions", null, ContentValues().apply {
            put("session_id", id); put("title", title); put("created_at", now); put("updated_at", now)
        })
        return id
    }

    fun sessionState(sessionId: String): JSONObject = readableDatabase.query(
        "chat_sessions", arrayOf("session_state_json"), "session_id = ?", arrayOf(sessionId),
        null, null, null
    ).use { cursor ->
        if (!cursor.moveToFirst()) JSONObject()
        else runCatching { JSONObject(cursor.getString(0)) }.getOrElse { JSONObject() }
    }

    fun saveSessionState(sessionId: String, state: JSONObject) {
        writableDatabase.update(
            "chat_sessions",
            ContentValues().apply {
                put("session_state_json", state.toString())
                put("updated_at", System.currentTimeMillis())
            },
            "session_id = ?",
            arrayOf(sessionId)
        )
    }

    /** Compact, structured context for the on-device planner. */
    fun plannerContext(sessionId: String, messageLimit: Int = 8): String {
        val state = sessionState(sessionId)
        val assetIds = JSONArray()
        state.optJSONArray("prepared_assets")?.let { assets ->
            for (index in 0 until assets.length()) {
                assets.optJSONObject(index)?.optString("asset_id")?.takeIf { it.isNotBlank() }
                    ?.let(assetIds::put)
            }
        }
        return JSONObject()
            .put("task_status", state.optString("task_status", "idle"))
            .put("last_query", state.optString("last_query", ""))
            .put("selected_uris", state.optJSONArray("selected_uris") ?: JSONArray())
            .put("selected_asset_ids", assetIds)
            .put("layout_context", state.optJSONObject("layout_context") ?: JSONObject.NULL)
            .put("last_plan", state.optJSONObject("last_plan") ?: JSONObject.NULL)
            .put("last_result_uri", state.optString("last_result_uri", ""))
            .put("recent_messages", JSONArray(recentMessages(sessionId, messageLimit)))
            .toString()
    }

    fun sessions(): List<ChatSession> = readableDatabase.query(
        "chat_sessions", null, null, null, null, null, "updated_at DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(ChatSession(
                cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("title")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
            ))
        }
    }

    fun deleteSession(sessionId: String) {
        writableDatabase.delete("chat_messages", "session_id = ?", arrayOf(sessionId))
        writableDatabase.delete("chat_sessions", "session_id = ?", arrayOf(sessionId))
    }

    /**
     * File paths owned by one session's prepared-asset cache, read back from its saved state.
     *
     * Only these copies are session-private; agent-edits and album outputs are shared or
     * user-visible and are deliberately not listed here.
     */
    fun sessionPreparedPaths(sessionId: String): List<String> {
        val prepared = sessionState(sessionId).optJSONArray("prepared_assets") ?: return emptyList()
        return buildList {
            for (index in 0 until prepared.length()) {
                val item = prepared.optJSONObject(index) ?: continue
                listOf("source_file", "image_file", "video_file").forEach { key ->
                    item.optString(key, "").takeIf { it.isNotBlank() && it != "null" }?.let(::add)
                }
            }
        }
    }

    fun appendMessage(sessionId: String, role: String, content: String) {
        val now = System.currentTimeMillis()
        writableDatabase.insert("chat_messages", null, ContentValues().apply {
            put("session_id", sessionId); put("role", role); put("content", content); put("created_at", now)
        })
        writableDatabase.update("chat_sessions", ContentValues().apply {
            put("updated_at", now)
            if (role == "user" && content.length < 40) put("title", content)
        }, "session_id = ?", arrayOf(sessionId))
    }

    fun recentMessages(sessionId: String, limit: Int = 12): List<String> = readableDatabase.rawQuery(
        "SELECT role || ': ' || content FROM chat_messages WHERE session_id = ? ORDER BY id DESC LIMIT ?",
        arrayOf(sessionId, limit.toString())
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }.reversed()
    }

    private fun createChatTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_sessions (session_id TEXT PRIMARY KEY, title TEXT NOT NULL, session_state_json TEXT NOT NULL DEFAULT '{}', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS chat_messages_session_idx ON chat_messages(session_id, id)")
    }

    private fun floatsToBytes(values: FloatArray): ByteArray = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putFloat) }.array()

    private fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }

    fun logEvent(kind: String, payload: String) {
        writableDatabase.insert("interaction_events", null, ContentValues().apply {
            put("kind", kind)
            put("payload", payload)
            put("created_at", System.currentTimeMillis())
        })
    }

    fun recentEvents(limit: Int = 200): List<String> = readableDatabase.rawQuery(
        "SELECT kind || '|' || payload FROM interaction_events ORDER BY id DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
}
