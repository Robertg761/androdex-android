package io.androdex.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.androdex.android.ui.home.HomeScreen
import io.androdex.android.ui.pairing.PairingScreen
import io.androdex.android.ui.settings.RuntimeSettingsSheet
import io.androdex.android.ui.shared.ApprovalDialog
import io.androdex.android.ui.shared.ErrorMessageDialog
import io.androdex.android.ui.state.AndrodexDestinationUiState
import io.androdex.android.ui.state.toAppUiState
import io.androdex.android.ui.turn.ThreadTimelineScreen

@Composable
fun AndrodexApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }
    val appState = remember(uiState, settingsOpen) {
        uiState.toAppUiState(isSettingsVisible = settingsOpen)
    }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(viewModel::updatePairingInput)
    }

    if (appState.overlay.approvalRequest != null) {
        ApprovalDialog(
            request = appState.overlay.approvalRequest,
            onApprove = { viewModel.respondToApproval(true) },
            onDecline = { viewModel.respondToApproval(false) },
        )
    }

    appState.overlay.errorMessage?.let { message ->
        ErrorMessageDialog(
            message = message,
            onDismiss = viewModel::clearError,
        )
    }

    if (appState.settings.isVisible) {
        RuntimeSettingsSheet(
            state = appState.settings,
            onDismiss = { settingsOpen = false },
            onReload = viewModel::loadRuntimeConfig,
            onSelectModel = viewModel::selectModel,
            onSelectReasoning = viewModel::selectReasoningEffort,
        )
    }

    when (val destination = appState.destination) {
        is AndrodexDestinationUiState.Pairing -> {
            PairingScreen(
                state = destination.state,
                onPairingInputChanged = viewModel::updatePairingInput,
                onScanQr = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan the Androdex pairing QR")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                },
                onConnect = viewModel::connectWithCurrentPairingInput,
                onReconnectSaved = viewModel::reconnectSaved,
            )
        }

        is AndrodexDestinationUiState.Home -> {
            HomeScreen(
                state = destination.state,
                onDisconnect = { viewModel.disconnect(clearSavedPairing = false) },
                onForgetPairing = { viewModel.disconnect(clearSavedPairing = true) },
                onRefresh = viewModel::refreshThreads,
                onCreateThread = viewModel::createThread,
                onOpenThread = viewModel::openThread,
                onOpenSettings = { settingsOpen = true },
                onOpenProjects = viewModel::openProjectPicker,
                onCloseProjects = viewModel::closeProjectPicker,
                onRefreshProjects = viewModel::loadRecentWorkspaces,
                onBrowseWorkspace = viewModel::browseWorkspace,
                onWorkspaceBrowserPathChanged = viewModel::updateWorkspaceBrowserPath,
                onActivateWorkspace = viewModel::activateWorkspace,
            )
        }

        is AndrodexDestinationUiState.Thread -> {
            ThreadTimelineScreen(
                state = destination.state,
                onBack = viewModel::closeThread,
                onRefresh = { viewModel.openThread(destination.state.threadId) },
                onComposerChanged = viewModel::updateComposerText,
                onPlanModeChanged = viewModel::updateComposerPlanMode,
                onSubagentsModeChanged = viewModel::updateComposerSubagentsEnabled,
                onSelectFileAutocomplete = viewModel::selectFileAutocomplete,
                onRemoveMentionedFile = viewModel::removeMentionedFile,
                onSelectSkillAutocomplete = viewModel::selectSkillAutocomplete,
                onRemoveMentionedSkill = viewModel::removeMentionedSkill,
                onSelectSlashCommand = viewModel::selectSlashCommand,
                onSend = viewModel::sendMessage,
                onStop = viewModel::interruptSelectedThread,
                onPauseQueue = viewModel::pauseSelectedThreadQueue,
                onResumeQueue = viewModel::resumeSelectedThreadQueue,
                onRestoreQueuedDraft = viewModel::restoreQueuedDraftToComposer,
                onRemoveQueuedDraft = viewModel::removeQueuedDraft,
                onRefreshGit = viewModel::refreshSelectedThreadGitState,
                onLoadGitDiff = viewModel::refreshSelectedThreadGitDiff,
                onOpenGitCommit = viewModel::openGitCommitDialog,
                onUpdateGitCommitMessage = viewModel::updateGitCommitMessage,
                onDismissGitCommit = viewModel::dismissGitCommitDialog,
                onSubmitGitCommit = viewModel::submitGitCommit,
                onPushGit = viewModel::pushGitChanges,
                onRequestGitPull = viewModel::requestGitPull,
                onOpenGitBranchDialog = viewModel::openGitBranchDialog,
                onUpdateGitBranchName = viewModel::updateGitBranchName,
                onDismissGitBranchDialog = viewModel::dismissGitBranchDialog,
                onRequestCreateGitBranch = viewModel::requestCreateGitBranch,
                onRequestSwitchGitBranch = viewModel::requestSwitchGitBranch,
                onOpenGitWorktreeDialog = viewModel::openGitWorktreeDialog,
                onUpdateGitWorktreeBranchName = viewModel::updateGitWorktreeBranchName,
                onUpdateGitWorktreeBaseBranch = viewModel::updateGitWorktreeBaseBranch,
                onUpdateGitWorktreeTransferMode = viewModel::updateGitWorktreeTransferMode,
                onDismissGitWorktreeDialog = viewModel::dismissGitWorktreeDialog,
                onRequestCreateGitWorktree = viewModel::requestCreateGitWorktree,
                onRequestRemoveGitWorktree = viewModel::requestRemoveGitWorktree,
                onDismissGitAlert = viewModel::dismissGitAlert,
                onHandleGitAlertAction = viewModel::handleGitAlertAction,
            )
        }
    }
}
