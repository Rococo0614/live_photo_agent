package com.vivo.lpa

import android.net.Uri
import java.io.File

/** The media shape an executor consumes or produces. */
enum class AgentArtifactKind {
    IMAGE,
    VIDEO,
    LIVE_PHOTO,
    PALETTE,
    JSON,
    LAYOUT
}

/**
 * An explicit, auditable output of one runtime step.
 *
 * A Live Photo cannot be represented by a single anonymous `lastOutput`: while it is being edited
 * it has a cover, a motion track and eventually a packed file. Keeping those pieces together is
 * what lets the next tool consume the actual previous result instead of silently returning to the
 * original gallery asset.
 */
data class AgentArtifact(
    val artifactId: String,
    val kind: AgentArtifactKind,
    val producerStepId: String,
    val file: File? = null,
    val imageFile: File? = null,
    val videoFile: File? = null,
    val contentUri: Uri? = null,
    val photoTimestampUs: Long? = null,
    val metadataJson: String = "{}",
    val validated: Boolean = false,
    val published: Boolean = false,
    val temporary: Boolean = true
)

/** A tool input must name its source; executors never infer it from a global lastOutput. */
sealed interface AgentInputRef {
    data class AssetSet(val assetIds: List<String>) : AgentInputRef
    data class Artifact(val artifactId: String) : AgentInputRef
    data class Layout(val version: Int) : AgentInputRef
}
