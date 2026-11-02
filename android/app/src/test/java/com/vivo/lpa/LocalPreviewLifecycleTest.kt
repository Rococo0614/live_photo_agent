package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalPreviewLifecycleTest {
    @Test
    fun confirmationCanOnlyBeUndoneAndNeverAuthorizesWriteback() {
        val pending = LocalPreviewLifecycleReducer.create("preview-1", 100)
        val accepted = LocalPreviewLifecycleReducer.acceptForReview(pending, 110)
        val undone = LocalPreviewLifecycleReducer.undo(accepted, 120)

        assertEquals(LocalPreviewReviewState.PENDING_REVIEW, pending.state)
        assertEquals(LocalPreviewReviewState.ACCEPTED_FOR_REVIEW, accepted.state)
        assertEquals(LocalPreviewReviewState.UNDONE, undone.state)
        assertEquals(2, undone.revision)
        assertFalse(pending.writebackAuthorized)
        assertFalse(accepted.writebackAuthorized)
        assertFalse(undone.writebackAuthorized)
    }

    @Test
    fun cancellationIsTerminalAndCannotBeConfirmedAfterwards() {
        val cancelled = LocalPreviewLifecycleReducer.cancel(
            LocalPreviewLifecycleReducer.create("preview-2", 100),
            101
        )

        assertEquals(LocalPreviewReviewState.CANCELLED, cancelled.state)
        assertThrows(IllegalArgumentException::class.java) {
            LocalPreviewLifecycleReducer.acceptForReview(cancelled, 102)
        }
    }

    @Test
    fun staleLifecycleTimestampIsRejected() {
        val pending = LocalPreviewLifecycleReducer.create("preview-3", 100)

        assertThrows(IllegalArgumentException::class.java) {
            LocalPreviewLifecycleReducer.cancel(pending, 99)
        }
    }
}
