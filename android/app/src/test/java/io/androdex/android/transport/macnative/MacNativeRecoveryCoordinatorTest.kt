package io.androdex.android.transport.macnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacNativeRecoveryCoordinatorTest {
    @Test
    fun classifyDomainEvent_requiresRecoveryForSequenceGap() {
        val coordinator = MacNativeRecoveryCoordinator()

        assertTrue(coordinator.beginSnapshotRecovery("bootstrap"))
        assertFalse(coordinator.completeSnapshotRecovery(snapshotSequence = 5L))

        assertEquals(
            MacNativeRecoveryCoordinator.Action.RECOVER,
            coordinator.classifyDomainEvent(sequence = 8L),
        )
    }

    @Test
    fun markEventBatchApplied_advancesLatestSequence() {
        val coordinator = MacNativeRecoveryCoordinator()

        coordinator.beginSnapshotRecovery("bootstrap")
        coordinator.completeSnapshotRecovery(snapshotSequence = 1L)
        coordinator.markEventBatchApplied(
            listOf(
                MacNativeSequencedValue(2L),
                MacNativeSequencedValue(3L),
            )
        )

        assertEquals(3L, coordinator.getState().latestSequence)
        assertEquals(3L, coordinator.getState().highestObservedSequence)
    }

    @Test
    fun completeReplayRecovery_requestsRetryWhenFrontierStillAhead() {
        val coordinator = MacNativeRecoveryCoordinator()

        coordinator.beginSnapshotRecovery("bootstrap")
        coordinator.completeSnapshotRecovery(snapshotSequence = 1L)
        coordinator.classifyDomainEvent(sequence = 4L)
        assertTrue(coordinator.beginReplayRecovery("sequence-gap"))
        coordinator.markEventBatchApplied(listOf(MacNativeSequencedValue(2L)))

        val completion = coordinator.completeReplayRecovery()

        assertTrue(completion.replayMadeProgress)
        assertTrue(completion.shouldReplay)
    }

    @Test
    fun completeSnapshotRecovery_requestsReplayWhenDeferredEventsArrived() {
        val coordinator = MacNativeRecoveryCoordinator()

        assertTrue(coordinator.beginSnapshotRecovery("bootstrap"))
        assertEquals(
            MacNativeRecoveryCoordinator.Action.DEFER,
            coordinator.classifyDomainEvent(sequence = 7L),
        )

        assertTrue(coordinator.completeSnapshotRecovery(snapshotSequence = 5L))
    }

    @Test
    fun deriveReplayRetryDecision_stopsAfterConfiguredNoProgressRetries() {
        val state = MacNativeRecoveryState(
            latestSequence = 4L,
            highestObservedSequence = 6L,
            bootstrapped = true,
        )
        val first = deriveMacNativeReplayRetryDecision(
            previousTracker = null,
            completion = MacNativeReplayRecoveryCompletion(
                replayMadeProgress = false,
                shouldReplay = true,
            ),
            recoveryState = state,
            baseDelayMs = 100L,
            maxNoProgressRetries = 2,
        )
        val second = deriveMacNativeReplayRetryDecision(
            previousTracker = first.tracker,
            completion = MacNativeReplayRecoveryCompletion(
                replayMadeProgress = false,
                shouldReplay = true,
            ),
            recoveryState = state,
            baseDelayMs = 100L,
            maxNoProgressRetries = 2,
        )
        val third = deriveMacNativeReplayRetryDecision(
            previousTracker = second.tracker,
            completion = MacNativeReplayRecoveryCompletion(
                replayMadeProgress = false,
                shouldReplay = true,
            ),
            recoveryState = state,
            baseDelayMs = 100L,
            maxNoProgressRetries = 2,
        )

        assertTrue(first.shouldRetry)
        assertTrue(second.shouldRetry)
        assertFalse(third.shouldRetry)
    }
}
