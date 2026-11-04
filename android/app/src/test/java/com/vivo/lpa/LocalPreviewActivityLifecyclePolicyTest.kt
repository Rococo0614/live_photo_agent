package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPreviewActivityLifecyclePolicyTest {
    @Test
    fun activeReviewStatesOverrideGenericRuntimeTerminalState() {
        assertEquals(
            LocalPreviewActivityLifecyclePolicy.WAITING_REVIEW,
            LocalPreviewActivityLifecyclePolicy.activeTaskStatus(
                LocalPreviewReviewState.PENDING_REVIEW
            )
        )
        assertEquals(
            LocalPreviewActivityLifecyclePolicy.ACCEPTED_REVIEW,
            LocalPreviewActivityLifecyclePolicy.activeTaskStatus(
                LocalPreviewReviewState.ACCEPTED_FOR_REVIEW
            )
        )
        assertNull(LocalPreviewActivityLifecyclePolicy.activeTaskStatus(
            LocalPreviewReviewState.UNDONE
        ))
    }

    @Test
    fun runnerCompletionPreservesReviewDecisionStates() {
        assertTrue(LocalPreviewActivityLifecyclePolicy.preserveOnRunnerCompletion(
            LocalPreviewActivityLifecyclePolicy.WAITING_REVIEW
        ))
        assertTrue(LocalPreviewActivityLifecyclePolicy.preserveOnRunnerCompletion(
            LocalPreviewActivityLifecyclePolicy.ACCEPTED_REVIEW
        ))
        assertFalse(LocalPreviewActivityLifecyclePolicy.preserveOnRunnerCompletion("running"))
    }

    @Test
    fun processDeathExpiresButRotationRetainsReview() {
        assertTrue(LocalPreviewActivityLifecyclePolicy.expiresAfterProcessRestart(
            LocalPreviewActivityLifecyclePolicy.WAITING_REVIEW,
            hasRetainedPreview = false
        ))
        assertFalse(LocalPreviewActivityLifecyclePolicy.expiresAfterProcessRestart(
            LocalPreviewActivityLifecyclePolicy.WAITING_REVIEW,
            hasRetainedPreview = true
        ))
        assertFalse(LocalPreviewActivityLifecyclePolicy.expiresAfterProcessRestart(
            "local_preview_undone",
            hasRetainedPreview = false
        ))
    }
}
