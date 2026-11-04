package com.vivo.lpa

/** Pure state mapping for Activity callbacks and process/configuration recreation. */
internal object LocalPreviewActivityLifecyclePolicy {
    const val WAITING_REVIEW = "waiting_local_preview_review"
    const val ACCEPTED_REVIEW = "accepted_local_preview"
    const val EXPIRED_AFTER_RESTART = "local_preview_expired_after_restart"

    fun activeTaskStatus(state: LocalPreviewReviewState?): String? = when (state) {
        LocalPreviewReviewState.PENDING_REVIEW -> WAITING_REVIEW
        LocalPreviewReviewState.ACCEPTED_FOR_REVIEW -> ACCEPTED_REVIEW
        LocalPreviewReviewState.CANCELLED,
        LocalPreviewReviewState.UNDONE,
        null -> null
    }

    fun preserveOnRunnerCompletion(status: String): Boolean = status in setOf(
        "waiting_user",
        "waiting_observation",
        WAITING_REVIEW,
        ACCEPTED_REVIEW
    )

    fun expiresAfterProcessRestart(status: String, hasRetainedPreview: Boolean): Boolean =
        !hasRetainedPreview && status in setOf(WAITING_REVIEW, ACCEPTED_REVIEW)
}
