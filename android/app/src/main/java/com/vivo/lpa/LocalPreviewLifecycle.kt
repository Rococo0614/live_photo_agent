package com.vivo.lpa

enum class LocalPreviewReviewState {
    PENDING_REVIEW,
    ACCEPTED_FOR_REVIEW,
    CANCELLED,
    UNDONE
}

/**
 * User review state for a temporary preview. There is intentionally no COMMITTED state: accepting
 * the visual result does not authorize source mutation, MediaStore writeback or publishing.
 */
data class LocalPreviewLifecycle(
    val artifactId: String,
    val state: LocalPreviewReviewState,
    val revision: Int,
    val updatedAtMs: Long,
    val writebackAuthorized: Boolean = false
) {
    init {
        require(artifactId.isNotBlank())
        require(revision >= 0)
        require(updatedAtMs >= 0)
        require(!writebackAuthorized) { "temporary preview lifecycle cannot authorize writeback" }
    }
}

object LocalPreviewLifecycleReducer {
    fun create(artifactId: String, timestampMs: Long): LocalPreviewLifecycle =
        LocalPreviewLifecycle(
            artifactId,
            LocalPreviewReviewState.PENDING_REVIEW,
            revision = 0,
            updatedAtMs = timestampMs
        )

    fun acceptForReview(
        current: LocalPreviewLifecycle,
        timestampMs: Long
    ): LocalPreviewLifecycle = transition(
        current,
        expected = LocalPreviewReviewState.PENDING_REVIEW,
        next = LocalPreviewReviewState.ACCEPTED_FOR_REVIEW,
        timestampMs = timestampMs
    )

    fun cancel(current: LocalPreviewLifecycle, timestampMs: Long): LocalPreviewLifecycle =
        transition(
            current,
            expected = LocalPreviewReviewState.PENDING_REVIEW,
            next = LocalPreviewReviewState.CANCELLED,
            timestampMs = timestampMs
        )

    fun undo(current: LocalPreviewLifecycle, timestampMs: Long): LocalPreviewLifecycle =
        transition(
            current,
            expected = LocalPreviewReviewState.ACCEPTED_FOR_REVIEW,
            next = LocalPreviewReviewState.UNDONE,
            timestampMs = timestampMs
        )

    private fun transition(
        current: LocalPreviewLifecycle,
        expected: LocalPreviewReviewState,
        next: LocalPreviewReviewState,
        timestampMs: Long
    ): LocalPreviewLifecycle {
        require(current.state == expected) {
            "cannot transition ${current.artifactId} from ${current.state} to $next"
        }
        require(timestampMs >= current.updatedAtMs) { "preview lifecycle timestamp moved backwards" }
        return current.copy(
            state = next,
            revision = current.revision + 1,
            updatedAtMs = timestampMs,
            writebackAuthorized = false
        )
    }
}
