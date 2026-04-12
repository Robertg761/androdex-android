package io.androdex.android.transport.macnative

internal typealias MacNativeRecoveryReason = String

internal data class MacNativeReplayRecoveryCompletion(
    val replayMadeProgress: Boolean,
    val shouldReplay: Boolean,
)

internal data class MacNativeReplayRetryTracker(
    val attempts: Int,
    val latestSequence: Long,
    val highestObservedSequence: Long,
)

internal data class MacNativeReplayRetryDecision(
    val shouldRetry: Boolean,
    val delayMs: Long,
    val tracker: MacNativeReplayRetryTracker?,
)

internal data class MacNativeRecoveryState(
    val latestSequence: Long = 0L,
    val highestObservedSequence: Long = 0L,
    val bootstrapped: Boolean = false,
    val pendingReplay: Boolean = false,
    val inFlight: MacNativeRecoveryPhase? = null,
)

internal data class MacNativeRecoveryPhase(
    val kind: Kind,
    val reason: MacNativeRecoveryReason,
) {
    internal enum class Kind {
        SNAPSHOT,
        REPLAY,
    }
}

internal data class MacNativeSequencedValue(
    val sequence: Long,
)

internal fun deriveMacNativeReplayRetryDecision(
    previousTracker: MacNativeReplayRetryTracker?,
    completion: MacNativeReplayRecoveryCompletion,
    recoveryState: MacNativeRecoveryState,
    baseDelayMs: Long,
    maxNoProgressRetries: Int,
): MacNativeReplayRetryDecision {
    if (!completion.shouldReplay) {
        return MacNativeReplayRetryDecision(
            shouldRetry = false,
            delayMs = 0L,
            tracker = null,
        )
    }
    if (completion.replayMadeProgress) {
        return MacNativeReplayRetryDecision(
            shouldRetry = true,
            delayMs = 0L,
            tracker = null,
        )
    }
    val sameFrontier = previousTracker != null
        && previousTracker.latestSequence == recoveryState.latestSequence
        && previousTracker.highestObservedSequence == recoveryState.highestObservedSequence
    val attempts = if (sameFrontier) {
        requireNotNull(previousTracker).attempts + 1
    } else {
        1
    }
    if (attempts > maxNoProgressRetries) {
        return MacNativeReplayRetryDecision(
            shouldRetry = false,
            delayMs = 0L,
            tracker = null,
        )
    }
    return MacNativeReplayRetryDecision(
        shouldRetry = true,
        delayMs = baseDelayMs * (1L shl (attempts - 1)),
        tracker = MacNativeReplayRetryTracker(
            attempts = attempts,
            latestSequence = recoveryState.latestSequence,
            highestObservedSequence = recoveryState.highestObservedSequence,
        ),
    )
}

internal class MacNativeRecoveryCoordinator {
    private var state = MacNativeRecoveryState()
    private var replayStartSequence: Long? = null

    fun getState(): MacNativeRecoveryState = state.copy(inFlight = state.inFlight?.copy())

    fun classifyDomainEvent(sequence: Long): Action {
        observeSequence(sequence)
        if (sequence <= state.latestSequence) {
            return Action.IGNORE
        }
        if (!state.bootstrapped || state.inFlight != null) {
            state = state.copy(pendingReplay = true)
            return Action.DEFER
        }
        if (sequence != state.latestSequence + 1L) {
            state = state.copy(pendingReplay = true)
            return Action.RECOVER
        }
        return Action.APPLY
    }

    fun markEventBatchApplied(events: List<MacNativeSequencedValue>): List<MacNativeSequencedValue> {
        val nextEvents = events
            .filter { it.sequence > state.latestSequence }
            .sortedBy { it.sequence }
        if (nextEvents.isEmpty()) {
            return emptyList()
        }
        val latestSequence = nextEvents.last().sequence
        state = state.copy(
            latestSequence = latestSequence,
            highestObservedSequence = maxOf(state.highestObservedSequence, latestSequence),
        )
        return nextEvents
    }

    fun beginSnapshotRecovery(reason: MacNativeRecoveryReason): Boolean {
        if (state.inFlight != null) {
            state = state.copy(pendingReplay = true)
            return false
        }
        state = state.copy(inFlight = MacNativeRecoveryPhase(MacNativeRecoveryPhase.Kind.SNAPSHOT, reason))
        return true
    }

    fun completeSnapshotRecovery(snapshotSequence: Long): Boolean {
        state = state.copy(
            latestSequence = maxOf(state.latestSequence, snapshotSequence),
            highestObservedSequence = maxOf(state.highestObservedSequence, snapshotSequence),
            bootstrapped = true,
            inFlight = null,
        )
        return resolveReplayNeedAfterRecovery()
    }

    fun failSnapshotRecovery() {
        state = state.copy(inFlight = null)
    }

    fun beginReplayRecovery(reason: MacNativeRecoveryReason): Boolean {
        if (!state.bootstrapped || state.inFlight != null) {
            state = state.copy(pendingReplay = true)
            return false
        }
        replayStartSequence = state.latestSequence
        state = state.copy(
            pendingReplay = false,
            inFlight = MacNativeRecoveryPhase(MacNativeRecoveryPhase.Kind.REPLAY, reason),
        )
        return true
    }

    fun completeReplayRecovery(): MacNativeReplayRecoveryCompletion {
        val replayMadeProgress = replayStartSequence != null && state.latestSequence > (replayStartSequence ?: 0L)
        replayStartSequence = null
        state = state.copy(inFlight = null)
        return MacNativeReplayRecoveryCompletion(
            replayMadeProgress = replayMadeProgress,
            shouldReplay = resolveReplayNeedAfterRecovery(),
        )
    }

    fun failReplayRecovery() {
        replayStartSequence = null
        state = state.copy(
            bootstrapped = false,
            inFlight = null,
        )
    }

    fun reset() {
        replayStartSequence = null
        state = MacNativeRecoveryState()
    }

    private fun observeSequence(sequence: Long) {
        state = state.copy(highestObservedSequence = maxOf(state.highestObservedSequence, sequence))
    }

    private fun resolveReplayNeedAfterRecovery(): Boolean {
        val shouldReplay = state.pendingReplay || state.highestObservedSequence > state.latestSequence
        state = state.copy(pendingReplay = false)
        return shouldReplay
    }

    internal enum class Action {
        IGNORE,
        DEFER,
        RECOVER,
        APPLY,
    }
}
