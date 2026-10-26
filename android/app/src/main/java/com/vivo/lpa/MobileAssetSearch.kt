package com.vivo.lpa

data class AssetSearchRequest(
    val query: String,
    val requestedCount: Int,
    val requiredMedia: RuntimeMediaKind? = null,
    val excludeAssetIds: Set<String> = emptySet(),
    val minScore: Double? = null
)

data class AssetSearchCandidate(
    val record: LibraryRecord,
    val score: Double,
    val accepted: Boolean,
    val rejectionReason: String? = null
)

data class AssetSearchOutcome(
    val request: AssetSearchRequest,
    val selected: List<LibraryRecord>,
    val candidates: List<AssetSearchCandidate>
)

/** Shared BGE/SQLite implementation for ordinary retrieval,补搜 and asset replacement. */
class MobileAssetSearch(private val context: android.content.Context) : AutoCloseable {
    private val database = LibraryDatabase(context)
    private val embedder = BgeEmbedder(context)

    fun available(): Boolean = embedder.available()

    fun search(request: AssetSearchRequest): AssetSearchOutcome {
        require(request.query.isNotBlank()) { "search query is blank" }
        require(request.requestedCount > 0) { "requestedCount must be positive" }
        check(available()) { "BGE 检索模型未部署" }

        val overFetch = maxOf(8, request.requestedCount * 4)
        val ranked = database.searchEmbeddingScored(
            embedder.embed(request.query), overFetch, request.query
        )
        val audited = ranked.map { (record, score) ->
            val reason = when {
                record.assetId in request.excludeAssetIds -> "already_in_use"
                request.minScore != null && score < request.minScore -> "score_below_threshold"
                !matchesMedia(record, request.requiredMedia) -> "media_mismatch"
                else -> null
            }
            AssetSearchCandidate(record, score, accepted = reason == null, rejectionReason = reason)
        }
        return AssetSearchOutcome(
            request = request,
            selected = audited.asSequence().filter { it.accepted }
                .take(request.requestedCount).map { it.record }.toList(),
            candidates = audited
        )
    }

    private fun matchesMedia(record: LibraryRecord, required: RuntimeMediaKind?): Boolean {
        if (required == null || required == RuntimeMediaKind.NONE || required == RuntimeMediaKind.MIXED) {
            return true
        }
        val format = record.mediaFormat.lowercase()
        return when (required) {
            RuntimeMediaKind.IMAGE -> format in setOf("image", "photo", "jpg", "jpeg", "png")
            RuntimeMediaKind.VIDEO -> format == "video" || format == "livephoto"
            RuntimeMediaKind.LIVE_PHOTO -> format == "livephoto"
            RuntimeMediaKind.NONE, RuntimeMediaKind.MIXED -> true
        }
    }

    override fun close() {
        database.close()
        embedder.close()
    }
}
