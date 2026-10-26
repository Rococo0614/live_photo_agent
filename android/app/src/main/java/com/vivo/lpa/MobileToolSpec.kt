package com.vivo.lpa

enum class ToolFamily {
    RETRIEVE,
    COMPOSE,
    STYLE,
    TEMPORAL,
    LAYOUT,
    REPLACE,
    ANALYZE,
    PUBLISH
}

enum class RuntimeMediaKind {
    NONE,
    IMAGE,
    VIDEO,
    LIVE_PHOTO,
    MIXED
}

data class ToolRuntimeContext(
    val family: ToolFamily? = null,
    val mediaKind: RuntimeMediaKind = RuntimeMediaKind.NONE,
    val assetCount: Int = 0,
    val imageCount: Int = 0,
    val videoCount: Int = 0,
    val livePhotoCount: Int = 0,
    val selectedCount: Int = 0,
    val hasLayout: Boolean = false,
    val artifactKind: AgentArtifactKind? = null,
    val artifactValidated: Boolean = false
) {
    companion object {
        fun from(
            assets: List<PreparedAsset>,
            selectedCount: Int = 0,
            hasLayout: Boolean = false,
            artifactKind: AgentArtifactKind? = null,
            artifactValidated: Boolean = false,
            family: ToolFamily? = null
        ): ToolRuntimeContext {
            val imageCount = assets.count { it.imageFile != null }
            val videoCount = assets.count { it.videoFile != null }
            val liveCount = assets.count {
                it.metadata.optString("media_format") == "livephoto" &&
                    it.imageFile != null && it.videoFile != null
            }
            val kind = when {
                artifactKind == AgentArtifactKind.IMAGE || artifactKind == AgentArtifactKind.PALETTE ->
                    RuntimeMediaKind.IMAGE
                artifactKind == AgentArtifactKind.VIDEO -> RuntimeMediaKind.VIDEO
                artifactKind == AgentArtifactKind.LIVE_PHOTO -> RuntimeMediaKind.LIVE_PHOTO
                assets.isEmpty() -> RuntimeMediaKind.NONE
                liveCount == assets.size -> RuntimeMediaKind.LIVE_PHOTO
                videoCount == assets.size && imageCount == 0 -> RuntimeMediaKind.VIDEO
                imageCount == assets.size && videoCount == 0 -> RuntimeMediaKind.IMAGE
                else -> RuntimeMediaKind.MIXED
            }
            val effectiveCounts = when (artifactKind) {
                AgentArtifactKind.IMAGE, AgentArtifactKind.PALETTE -> intArrayOf(1, 1, 0, 0)
                AgentArtifactKind.VIDEO -> intArrayOf(1, 0, 1, 0)
                AgentArtifactKind.LIVE_PHOTO -> intArrayOf(1, 1, 1, 1)
                AgentArtifactKind.JSON, AgentArtifactKind.LAYOUT -> intArrayOf(1, 0, 0, 0)
                null -> intArrayOf(assets.size, imageCount, videoCount, liveCount)
            }
            return ToolRuntimeContext(
                family = family,
                mediaKind = kind,
                assetCount = effectiveCounts[0],
                imageCount = effectiveCounts[1],
                videoCount = effectiveCounts[2],
                livePhotoCount = effectiveCounts[3],
                selectedCount = selectedCount,
                hasLayout = hasLayout,
                artifactKind = artifactKind,
                artifactValidated = artifactValidated
            )
        }
    }
}

data class ToolAvailability(val available: Boolean, val reason: String = "")

data class MobileToolSpec(
    val id: String,
    val family: ToolFamily,
    val description: String,
    val acceptedMedia: Set<RuntimeMediaKind> = RuntimeMediaKind.entries.toSet(),
    val minAssets: Int = 0,
    val maxAssets: Int = Int.MAX_VALUE,
    val minImageTracks: Int = 0,
    val minVideoTracks: Int = 0,
    val maxVideoTracks: Int = Int.MAX_VALUE,
    val minLivePhotos: Int = 0,
    val selectionArity: Int? = null,
    val requiresLayout: Boolean = false,
    val requiredArtifactKinds: Set<AgentArtifactKind> = emptySet(),
    val observationBarrier: Boolean = false,
    val destructive: Boolean = false,
    val idempotent: Boolean = true,
    val singleton: Boolean = false,
    /** Runtime-only orchestration tool hidden from the legacy static planner prompt. */
    val loopOnly: Boolean = false,
    /** Legacy aliases can remain parseable without competing in the v2 loop catalogue. */
    val loopVisible: Boolean = true
) {
    fun availability(context: ToolRuntimeContext): ToolAvailability {
        if (context.family != null && context.family != family) {
            return ToolAvailability(false, "family=${context.family} != $family")
        }
        if (context.mediaKind !in acceptedMedia) {
            return ToolAvailability(false, "media=${context.mediaKind} not accepted")
        }
        if (context.assetCount !in minAssets..maxAssets) {
            return ToolAvailability(false, "assets=${context.assetCount} not in $minAssets..$maxAssets")
        }
        if (context.imageCount < minImageTracks) {
            return ToolAvailability(false, "image_tracks=${context.imageCount} < $minImageTracks")
        }
        if (context.videoCount !in minVideoTracks..maxVideoTracks) {
            return ToolAvailability(false, "video_tracks=${context.videoCount} not in $minVideoTracks..$maxVideoTracks")
        }
        if (context.livePhotoCount < minLivePhotos) {
            return ToolAvailability(false, "live_photos=${context.livePhotoCount} < $minLivePhotos")
        }
        if (selectionArity != null && context.selectedCount != selectionArity) {
            return ToolAvailability(false, "selection=${context.selectedCount} != $selectionArity")
        }
        if (requiresLayout && !context.hasLayout) {
            return ToolAvailability(false, "layout required")
        }
        if (requiredArtifactKinds.isNotEmpty()) {
            if (!context.artifactValidated || context.artifactKind !in requiredArtifactKinds) {
                return ToolAvailability(false, "validated artifact $requiredArtifactKinds required")
            }
        }
        return ToolAvailability(true)
    }
}
