package io.androdex.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.androdex.android.ui.home.HomeScreen
import io.androdex.android.ui.pairing.PairingScreen
import io.androdex.android.ui.settings.RuntimeSettingsSheet
import io.androdex.android.ui.shared.ApprovalDialog
import io.androdex.android.ui.shared.ErrorMessageDialog
import io.androdex.android.ui.shared.MissingNotificationThreadDialog
import io.androdex.android.ui.state.AndrodexDestinationUiState
import io.androdex.android.ui.state.toAppUiState
import io.androdex.android.ui.turn.ForkThreadSheet
import io.androdex.android.ui.turn.ThreadRuntimeSheet
import io.androdex.android.ui.turn.ThreadTimelineScreen
import io.androdex.android.ui.turn.TurnAttachmentPipeline
import kotlinx.coroutines.launch

@Composable
fun AndrodexApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }
    var threadRuntimeOpen by remember { mutableStateOf(false) }
    var forkThreadOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val appState = remember(uiState, settingsOpen) {
        uiState.toAppUiState(isSettingsVisible = settingsOpen)
    }
    LaunchedEffect(appState.destination) {
        if (appState.destination !is AndrodexDestinationUiState.Thread) {
            threadRuntimeOpen = false
            forkThreadOpen = false
        }
    }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(viewModel::updatePairingInput)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap == null) {
            return@rememberLauncherForActivityResult
        }
        val reservation = viewModel.beginComposerAttachmentIntake(1) ?: return@rememberLauncherForActivityResult
        val attachmentId = reservation.acceptedIds.firstOrNull() ?: return@rememberLauncherForActivityResult
        scope.launch {
            val state = TurnAttachmentPipeline.loadCameraAttachment(bitmap)
            viewModel.updateComposerAttachmentState(
                threadId = reservation.threadId,
                attachmentId = attachmentId,
                state = state,
            )
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraLauncher.launch(null)
        } else {
            viewModel.reportAttachmentError("Camera permission is required to take a photo.")
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) {
            return@rememberLauncherForActivityResult
        }
        val reservation = viewModel.beginComposerAttachmentIntake(uris.size) ?: return@rememberLauncherForActivityResult
        uris.take(reservation.acceptedIds.size)
            .zip(reservation.acceptedIds)
            .forEach { (uri, attachmentId) ->
                scope.launch {
                    val state = TurnAttachmentPipeline.loadGalleryAttachment(
                        contentResolver = context.contentResolver,
                        uriString = uri.toString(),
                    )
                    viewModel.updateComposerAttachmentState(
                        threadId = reservation.threadId,
                        attachmentId = attachmentId,
                        state = state,
                    )
                }
            }
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

    appState.overlay.missingNotificationThreadPrompt?.let { prompt ->
        MissingNotificationThreadDialog(
            prompt = prompt,
            onDismiss = viewModel::dismissMissingNotificationThreadPrompt,
        )
    }

    if (appState.settings.isVisible) {
        RuntimeSettingsSheet(
            state = appState.settings,
            onDismiss = { settingsOpen = false },
            onReload = viewModel::loadRuntimeConfig,
            onSelectModel = viewModel::selectModel,
            onSelectReasoning = viewModel::selectReasoningEffort,
            onSelectServiceTier = { wireValue ->
                viewModel.selectServiceTier(io.androdex.android.model.ServiceTier.fromWireValue(wireValue))
            },
            onSelectAccessMode = { wireValue ->
                viewModel.selectAccessMode(
                    io.androdex.android.model.AccessMode.fromWireValue(wireValue)
                        ?: io.androdex.android.model.AccessMode.ON_REQUEST
                )
            },
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
            if (threadRuntimeOpen) {
                ThreadRuntimeSheet(
                    state = destination.state.runtime,
                    onDismiss = { threadRuntimeOpen = false },
                    onSelectReasoning = viewModel::selectThreadReasoningOverride,
                    onSelectServiceTier = { wireValue ->
                        viewModel.selectThreadServiceTierOverride(
                            io.androdex.android.model.ServiceTier.fromWireValue(wireValue)
                        )
                    },
                    onUseDefaults = viewModel::useThreadRuntimeDefaults,
                )
            }

            if (forkThreadOpen) {
                ForkThreadSheet(
                    state = destination.state.fork,
                    onDismiss = { forkThreadOpen = false },
                    onFork = { projectPath ->
                        forkThreadOpen = false
                        viewModel.forkSelectedThread(projectPath)
                    },
                )
            }

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
                onAddCamera = {
                    val permissionGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    when {
                        permissionGranted -> cameraLauncher.launch(null)
                        activity == null -> viewModel.reportAttachmentError("Camera is unavailable right now.")
                        else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onAddGallery = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRemoveComposerAttachment = viewModel::removeComposerAttachment,
                onOpenRuntime = { threadRuntimeOpen = true },
                onOpenFork = {
                    if (destination.state.fork.isEnabled) {
                        forkThreadOpen = true
                    }
                },
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
