package io.androdex.android.ui.state

import io.androdex.android.AndrodexUiState
import io.androdex.android.ComposerSlashCommand
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.ComposerMentionedFile
import io.androdex.android.model.ComposerMentionedSkill
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.FuzzyFileMatch
import io.androdex.android.model.ModelOption
import io.androdex.android.model.QueuedTurnDraft
import io.androdex.android.model.SkillMetadata
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

internal data class AndrodexAppUiState(
    val overlay: AndrodexOverlayUiState,
    val settings: RuntimeSettingsUiState,
    val destination: AndrodexDestinationUiState,
)

internal data class AndrodexOverlayUiState(
    val approvalRequest: ApprovalRequest?,
    val errorMessage: String?,
)

internal sealed interface AndrodexDestinationUiState {
    data class Pairing(val state: PairingScreenUiState) : AndrodexDestinationUiState

    data class Home(val state: HomeScreenUiState) : AndrodexDestinationUiState

    data class Thread(val state: ThreadTimelineUiState) : AndrodexDestinationUiState
}

internal data class ConnectionBannerUiState(
    val status: ConnectionStatus,
    val detail: String?,
    val fingerprint: String?,
)

internal data class BusyUiState(
    val isVisible: Boolean,
    val label: String?,
)

internal data class PairingScreenUiState(
    val pairingInput: String,
    val hasSavedPairing: Boolean,
    val defaultRelayUrl: String?,
    val isBusy: Boolean,
    val reconnectButtonLabel: String,
    val reconnectEnabled: Boolean,
    val connection: ConnectionBannerUiState,
)

internal data class HomeScreenUiState(
    val connection: ConnectionBannerUiState,
    val busy: BusyUiState,
    val activeWorkspacePath: String?,
    val threadList: ThreadListPaneUiState,
    val projectPicker: ProjectPickerUiState?,
)

internal data class ThreadListPaneUiState(
    val threads: List<ThreadListItemUiState>,
    val emptyState: ThreadListEmptyStateUiState?,
)

internal data class ThreadListItemUiState(
    val id: String,
    val title: String,
    val preview: String?,
    val projectName: String,
    val updatedLabel: String?,
    val runState: ThreadRunBadgeUiState?,
)

internal data class ThreadListEmptyStateUiState(
    val title: String,
    val message: String,
    val showChooseProjectAction: Boolean,
)

internal data class ProjectPickerUiState(
    val isLoading: Boolean,
    val activeWorkspacePath: String?,
    val recentWorkspaces: List<WorkspaceRowUiState>,
    val browserPath: String,
    val browserParentPath: String?,
    val browserEntries: List<WorkspaceRowUiState>,
    val isBrowsing: Boolean,
)

internal data class WorkspaceRowUiState(
    val title: String,
    val subtitle: String,
    val active: Boolean,
    val action: WorkspaceRowAction,
)

internal enum class WorkspaceRowAction {
    ACTIVATE,
    BROWSE,
}

internal data class ThreadTimelineUiState(
    val threadId: String,
    val title: String,
    val messages: List<ConversationMessage>,
    val runState: ThreadRunBadgeUiState?,
    val busy: BusyUiState,
    val queuedDrafts: List<QueuedTurnDraft>,
    val queuePauseMessage: String?,
    val canRestoreQueuedDrafts: Boolean,
    val canPauseQueue: Boolean,
    val canResumeQueue: Boolean,
    val composer: ComposerUiState,
)

internal data class ComposerUiState(
    val text: String,
    val mentionedFiles: List<ComposerMentionedFile>,
    val mentionedSkills: List<ComposerMentionedSkill>,
    val fileAutocompleteItems: List<FuzzyFileMatch>,
    val isFileAutocompleteVisible: Boolean,
    val isFileAutocompleteLoading: Boolean,
    val fileAutocompleteQuery: String,
    val skillAutocompleteItems: List<SkillMetadata>,
    val isSkillAutocompleteVisible: Boolean,
    val isSkillAutocompleteLoading: Boolean,
    val skillAutocompleteQuery: String,
    val slashCommandItems: List<ComposerSlashCommand>,
    val isSlashCommandAutocompleteVisible: Boolean,
    val slashCommandQuery: String,
    val inputEnabled: Boolean,
    val submitMode: ComposerSubmitMode,
    val isPlanModeEnabled: Boolean,
    val planModeEnabled: Boolean,
    val isSubagentsEnabled: Boolean,
    val subagentsEnabled: Boolean,
    val isSubmitting: Boolean,
    val submitEnabled: Boolean,
    val showStop: Boolean,
    val isStopping: Boolean,
    val stopEnabled: Boolean,
    val queuedCount: Int,
    val isQueuePaused: Boolean,
)

internal enum class ComposerSubmitMode {
    SEND,
    QUEUE,
}

internal enum class ThreadRunBadgeUiState {
    RUNNING,
    READY,
    FAILED,
}

internal data class RuntimeSettingsUiState(
    val isVisible: Boolean,
    val isLoading: Boolean,
    val modelOptions: List<RuntimeSettingsOptionUiState>,
    val reasoningOptions: List<RuntimeSettingsOptionUiState>,
)

internal data class RuntimeSettingsOptionUiState(
    val value: String?,
    val title: String,
    val subtitle: String?,
    val selected: Boolean,
)

internal fun AndrodexUiState.toAppUiState(
    isSettingsVisible: Boolean,
    nowEpochMs: Long = System.currentTimeMillis(),
): AndrodexAppUiState {
    return AndrodexAppUiState(
        overlay = AndrodexOverlayUiState(
            approvalRequest = pendingApproval,
            errorMessage = errorMessage,
        ),
        settings = toRuntimeSettingsUiState(isSettingsVisible),
        destination = when {
            selectedThreadId != null -> AndrodexDestinationUiState.Thread(
                state = toThreadTimelineUiState()
            )
            connectionStatus == ConnectionStatus.CONNECTED -> AndrodexDestinationUiState.Home(
                state = toHomeScreenUiState(nowEpochMs)
            )
            else -> AndrodexDestinationUiState.Pairing(
                state = toPairingScreenUiState()
            )
        },
    )
}

internal fun relativeTimeLabel(
    epochMs: Long,
    nowEpochMs: Long = System.currentTimeMillis(),
): String {
    val diff = nowEpochMs - epochMs
    if (diff < 0) {
        return "now"
    }

    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)

    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochMs))
    }
}

private fun AndrodexUiState.toPairingScreenUiState(): PairingScreenUiState {
    return PairingScreenUiState(
        pairingInput = pairingInput,
        hasSavedPairing = hasSavedPairing,
        defaultRelayUrl = defaultRelayUrl,
        isBusy = isBusy,
        reconnectButtonLabel = reconnectButtonLabel(),
        reconnectEnabled = !isBusy && connectionStatus != ConnectionStatus.RETRYING_SAVED_PAIRING,
        connection = toConnectionBannerUiState(),
    )
}

private fun AndrodexUiState.toHomeScreenUiState(nowEpochMs: Long): HomeScreenUiState {
    return HomeScreenUiState(
        connection = toConnectionBannerUiState(),
        busy = toBusyUiState(),
        activeWorkspacePath = activeWorkspacePath,
        threadList = toThreadListPaneUiState(nowEpochMs),
        projectPicker = toProjectPickerUiState(),
    )
}

private fun AndrodexUiState.toThreadListPaneUiState(nowEpochMs: Long): ThreadListPaneUiState {
    val items = threads.map { thread ->
        ThreadListItemUiState(
            id = thread.id,
            title = thread.title,
            preview = thread.preview,
            projectName = thread.projectName,
            updatedLabel = thread.updatedAtEpochMs?.let { relativeTimeLabel(it, nowEpochMs) },
            runState = threadRunBadge(thread.id),
        )
    }
    val emptyState = if (items.isEmpty() && !isBusy) {
        ThreadListEmptyStateUiState(
            title = "No conversations yet",
            message = if (activeWorkspacePath == null) {
                "Choose a project to start chatting"
            } else {
                "Tap \"New Chat\" to start one"
            },
            showChooseProjectAction = activeWorkspacePath == null,
        )
    } else {
        null
    }

    return ThreadListPaneUiState(
        threads = items,
        emptyState = emptyState,
    )
}

private fun AndrodexUiState.toProjectPickerUiState(): ProjectPickerUiState? {
    if (!isProjectPickerOpen) {
        return null
    }

    val isBrowsing = workspaceBrowserPath != null || workspaceBrowserEntries.isNotEmpty()
    return ProjectPickerUiState(
        isLoading = isWorkspaceBrowserLoading,
        activeWorkspacePath = activeWorkspacePath,
        recentWorkspaces = recentWorkspaces.map { workspace ->
            WorkspaceRowUiState(
                title = workspace.name,
                subtitle = workspace.path,
                active = workspace.isActive,
                action = WorkspaceRowAction.ACTIVATE,
            )
        },
        browserPath = workspaceBrowserPath.orEmpty(),
        browserParentPath = workspaceBrowserParentPath,
        browserEntries = workspaceBrowserEntries.map { entry ->
            WorkspaceRowUiState(
                title = entry.name,
                subtitle = entry.path,
                active = entry.isActive,
                action = if (entry.source == "recent") {
                    WorkspaceRowAction.ACTIVATE
                } else {
                    WorkspaceRowAction.BROWSE
                },
            )
        },
        isBrowsing = isBrowsing,
    )
}

private fun AndrodexUiState.toThreadTimelineUiState(): ThreadTimelineUiState {
    val threadId = requireNotNull(selectedThreadId)
    val isThreadRunning = threadId in runningThreadIds || threadId in protectedRunningFallbackThreadIds
    val isPlanModeEnabled = isComposerPlanMode || threadId in composerPlanModeByThread
    val isSubagentsEnabled = isComposerSubagentsEnabled || threadId in composerSubagentsByThread
    val queueState = queuedDraftStateByThread[threadId]
    val queuedDrafts = queueState?.drafts.orEmpty()
    val queueControlsEnabled = !isBusy && !isSendingMessage && !isInterruptingSelectedThread
    return ThreadTimelineUiState(
        threadId = threadId,
        title = selectedThreadTitle ?: "Conversation",
        messages = messages,
        runState = threadRunBadge(threadId),
        busy = toBusyUiState(),
        queuedDrafts = queuedDrafts,
        queuePauseMessage = queueState?.pauseMessage,
        canRestoreQueuedDrafts = queuedDrafts.isNotEmpty()
            && queueControlsEnabled
            && composerText.isBlank()
            && composerMentionedFilesByThread[threadId].orEmpty().isEmpty()
            && composerMentionedSkillsByThread[threadId].orEmpty().isEmpty()
            && !isSubagentsEnabled,
        canPauseQueue = queuedDrafts.isNotEmpty()
            && queueControlsEnabled
            && queueState?.isPaused != true,
        canResumeQueue = queuedDrafts.isNotEmpty()
            && queueControlsEnabled
            && queueState?.isPaused == true,
        composer = ComposerUiState(
            text = composerText,
            mentionedFiles = composerMentionedFilesByThread[threadId].orEmpty(),
            mentionedSkills = composerMentionedSkillsByThread[threadId].orEmpty(),
            fileAutocompleteItems = composerFileAutocompleteItems,
            isFileAutocompleteVisible = isFileAutocompleteVisible,
            isFileAutocompleteLoading = isFileAutocompleteLoading,
            fileAutocompleteQuery = fileAutocompleteQuery,
            skillAutocompleteItems = composerSkillAutocompleteItems,
            isSkillAutocompleteVisible = isSkillAutocompleteVisible,
            isSkillAutocompleteLoading = isSkillAutocompleteLoading,
            skillAutocompleteQuery = skillAutocompleteQuery,
            slashCommandItems = composerSlashCommandItems,
            isSlashCommandAutocompleteVisible = isSlashCommandAutocompleteVisible,
            slashCommandQuery = slashCommandQuery,
            inputEnabled = !isBusy && !isSendingMessage && !isInterruptingSelectedThread,
            submitMode = if (isThreadRunning) ComposerSubmitMode.QUEUE else ComposerSubmitMode.SEND,
            isPlanModeEnabled = isPlanModeEnabled,
            planModeEnabled = !isBusy && !isSendingMessage && !isInterruptingSelectedThread,
            isSubagentsEnabled = isSubagentsEnabled,
            subagentsEnabled = !isBusy && !isSendingMessage && !isInterruptingSelectedThread,
            isSubmitting = isSendingMessage,
            submitEnabled = (composerText.isNotBlank() || isSubagentsEnabled)
                && !isBusy
                && !isSendingMessage
                && !isInterruptingSelectedThread,
            showStop = isThreadRunning,
            isStopping = isInterruptingSelectedThread,
            stopEnabled = isThreadRunning && !isBusy && !isSendingMessage && !isInterruptingSelectedThread,
            queuedCount = queuedDrafts.size,
            isQueuePaused = queueState?.isPaused == true,
        ),
    )
}

private fun AndrodexUiState.toRuntimeSettingsUiState(
    isVisible: Boolean,
): RuntimeSettingsUiState {
    val selectedModel = resolveSelectedModel(availableModels, selectedModelId)
    val modelOptions = buildList {
        add(
            RuntimeSettingsOptionUiState(
                value = null,
                title = "Auto",
                subtitle = selectedModel?.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "Default: $it" },
                selected = selectedModelId == null,
            )
        )
        availableModels.forEach { model ->
            add(
                RuntimeSettingsOptionUiState(
                    value = model.stableIdentifier,
                    title = model.displayName,
                    subtitle = model.description.ifBlank { model.model },
                    selected = selectedModelId == model.stableIdentifier
                        || selectedModelId == model.model,
                )
            )
        }
    }
    val reasoningOptions = buildList {
        add(
            RuntimeSettingsOptionUiState(
                value = null,
                title = "Auto",
                subtitle = selectedModel?.defaultReasoningEffort?.let { "Default: $it" },
                selected = selectedReasoningEffort == null,
            )
        )
        selectedModel?.supportedReasoningEfforts.orEmpty().forEach { effort ->
            add(
                RuntimeSettingsOptionUiState(
                    value = effort.reasoningEffort,
                    title = effort.reasoningEffort,
                    subtitle = effort.description,
                    selected = selectedReasoningEffort == effort.reasoningEffort,
                )
            )
        }
    }

    return RuntimeSettingsUiState(
        isVisible = isVisible,
        isLoading = isLoadingRuntimeConfig,
        modelOptions = modelOptions,
        reasoningOptions = reasoningOptions,
    )
}

private fun AndrodexUiState.toConnectionBannerUiState(): ConnectionBannerUiState {
    return ConnectionBannerUiState(
        status = connectionStatus,
        detail = connectionDetail,
        fingerprint = secureFingerprint,
    )
}

private fun AndrodexUiState.toBusyUiState(): BusyUiState {
    return BusyUiState(
        isVisible = isBusy || isLoadingRuntimeConfig,
        label = busyLabel ?: if (isLoadingRuntimeConfig) "Loading models..." else null,
    )
}

private fun AndrodexUiState.threadRunBadge(threadId: String): ThreadRunBadgeUiState? {
    return when {
        threadId in runningThreadIds || threadId in protectedRunningFallbackThreadIds -> ThreadRunBadgeUiState.RUNNING
        threadId in failedThreadIds -> ThreadRunBadgeUiState.FAILED
        threadId in readyThreadIds -> ThreadRunBadgeUiState.READY
        else -> null
    }
}

private fun AndrodexUiState.reconnectButtonLabel(): String {
    return when (connectionStatus) {
        ConnectionStatus.RETRYING_SAVED_PAIRING -> "Retrying Saved Pairing..."
        ConnectionStatus.RECONNECT_REQUIRED -> "Reconnect Saved Pairing"
        ConnectionStatus.UPDATE_REQUIRED -> "Reconnect After Updating"
        else -> "Reconnect Saved Pairing"
    }
}

private fun resolveSelectedModel(
    models: List<ModelOption>,
    selectedModelId: String?,
): ModelOption? {
    return models.firstOrNull { it.id == selectedModelId || it.model == selectedModelId }
        ?: models.firstOrNull { it.isDefault }
        ?: models.firstOrNull()
}
