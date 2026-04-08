package io.androdex.android.model

enum class ThreadCapabilityAction {
    TURN_START,
    TURN_INTERRUPT,
    APPROVAL_RESPONSES,
    USER_INPUT_RESPONSES,
    TOOL_INPUT_RESPONSES,
    CHECKPOINT_ROLLBACK,
}

fun ThreadSummary.capabilityFlag(action: ThreadCapabilityAction): ThreadCapabilityFlag? {
    val capabilities = threadCapabilities ?: return null
    return when (action) {
        ThreadCapabilityAction.TURN_START -> capabilities.turnStart
        ThreadCapabilityAction.TURN_INTERRUPT -> capabilities.turnInterrupt
        ThreadCapabilityAction.APPROVAL_RESPONSES -> capabilities.approvalResponses
        ThreadCapabilityAction.USER_INPUT_RESPONSES -> capabilities.userInputResponses
        ThreadCapabilityAction.TOOL_INPUT_RESPONSES -> capabilities.toolInputResponses
        ThreadCapabilityAction.CHECKPOINT_ROLLBACK -> capabilities.checkpointRollback
    }
}

fun ThreadSummary.isCapabilitySupported(action: ThreadCapabilityAction): Boolean {
    return capabilityFlag(action)?.supported != false
}

fun ThreadSummary.capabilityBlockReason(action: ThreadCapabilityAction): String? {
    val capability = capabilityFlag(action) ?: return null
    if (capability.supported) {
        return null
    }
    return capability.reason
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: threadCapabilities?.companionSupportReason
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        ?: defaultCapabilityBlockReason(action)
}

fun ThreadSummary.workspacePresentationNotice(): String? {
    val capabilities = threadCapabilities ?: return null
    if (!capabilities.workspaceFallbackUsed) {
        return null
    }
    return "Using the project workspace root because this thread's recorded worktree no longer resolves locally."
}

private fun defaultCapabilityBlockReason(action: ThreadCapabilityAction): String {
    return when (action) {
        ThreadCapabilityAction.TURN_START -> "This thread can't start new turns from Androdex right now."
        ThreadCapabilityAction.TURN_INTERRUPT -> "This thread can't stop the current run from Androdex right now."
        ThreadCapabilityAction.APPROVAL_RESPONSES -> "This thread can't send approval responses from Androdex right now."
        ThreadCapabilityAction.USER_INPUT_RESPONSES -> "This thread can't answer user input prompts from Androdex right now."
        ThreadCapabilityAction.TOOL_INPUT_RESPONSES -> "This thread can't submit tool input responses from Androdex right now."
        ThreadCapabilityAction.CHECKPOINT_ROLLBACK -> "This thread can't roll back checkpoints from Androdex right now."
    }
}
