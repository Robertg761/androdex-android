package io.androdex.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DismissibleDrawerSheet
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.ui.home.HomeScreen
import io.androdex.android.ui.pairing.FirstPairingOnboardingScreen
import io.androdex.android.ui.pairing.PairingScreen
import io.androdex.android.ui.settings.RuntimeSettingsSheet
import io.androdex.android.ui.shared.ApprovalDialog
import io.androdex.android.ui.shared.ErrorMessageDialog
import io.androdex.android.ui.shared.MissingNotificationThreadDialog
import io.androdex.android.ui.shared.remodexFadeIn
import io.androdex.android.ui.shared.remodexFadeOut
import io.androdex.android.ui.shared.remodexSlideInHorizontally
import io.androdex.android.ui.shared.remodexSlideOutHorizontally
import io.androdex.android.ui.sidebar.SidebarContent
import io.androdex.android.ui.state.AndrodexDestinationUiState
import io.androdex.android.ui.state.FirstPairingOnboardingUiState
import io.androdex.android.ui.state.HomeScreenUiState
import io.androdex.android.ui.state.PairingScreenUiState
import io.androdex.android.ui.state.toHomeScreenUiState
import io.androdex.android.ui.state.toAppUiState
import io.androdex.android.ui.state.ThreadTimelineUiState
import io.androdex.android.ui.theme.RemodexTheme
import io.androdex.android.ui.turn.ForkThreadSheet
import io.androdex.android.ui.turn.GitSheet
import io.androdex.android.ui.turn.ThreadRuntimeSheet
import io.androdex.android.ui.turn.ThreadTimelineScreen
import io.androdex.android.ui.turn.TurnAttachmentPipeline
import kotlinx.coroutines.launch

private const val DrawerWidthDp = 320

internal enum class ThreadBackAction {
    DISMISS_KEYBOARD,
    OPEN_SIDEBAR,
    CLOSE_SIDEBAR,
}

internal fun threadBackAction(
    isDrawerOpen: Boolean,
    isImeVisible: Boolean,
): ThreadBackAction = when {
    isImeVisible -> ThreadBackAction.DISMISS_KEYBOARD
    isDrawerOpen -> ThreadBackAction.CLOSE_SIDEBAR
    else -> ThreadBackAction.OPEN_SIDEBAR
}

@Composable
fun AndrodexApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }
    var threadRuntimeOpen by remember { mutableStateOf(false) }
    var forkThreadOpen by remember { mutableStateOf(false) }
    var gitSheetOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val appState = remember(uiState, settingsOpen) {
        uiState.toAppUiState(isSettingsVisible = settingsOpen)
    }
    val liveHomeState = remember(uiState) {
        uiState.toHomeScreenUiState()
    }

    // Drawer state — persistent across Home and Thread screens
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Cache the last HomeScreenUiState so the sidebar stays populated
    // while viewing a thread (Thread state carries no thread-list data)
    var cachedSidebar by remember { mutableStateOf<HomeScreenUiState?>(null) }
    var cachedPairing by remember { mutableStateOf<PairingScreenUiState?>(null) }
    var cachedOnboarding by remember { mutableStateOf<FirstPairingOnboardingUiState?>(null) }
    val destination = appState.destination
    if (destination is AndrodexDestinationUiState.Home) {
        cachedSidebar = liveHomeState
    }
    if (destination is AndrodexDestinationUiState.Thread
        && (uiState.connectionStatus == ConnectionStatus.CONNECTED
            || uiState.hasLoadedThreadList
            || uiState.threads.isNotEmpty())
    ) {
        cachedSidebar = liveHomeState
    }
    if (destination is AndrodexDestinationUiState.Pairing) {
        cachedPairing = destination.state
    }
    if (destination is AndrodexDestinationUiState.Onboarding) {
        cachedOnboarding = destination.state
    }

    // Selected thread ID — used to highlight the active row in the sidebar
    val selectedThreadId = (destination as? AndrodexDestinationUiState.Thread)?.state?.threadId

    LaunchedEffect(destination) {
        if (destination !is AndrodexDestinationUiState.Thread) {
            threadRuntimeOpen = false
            forkThreadOpen = false
            gitSheetOpen = false
        }
        if ((destination is AndrodexDestinationUiState.Pairing
                || destination is AndrodexDestinationUiState.Onboarding)
            && drawerState.isOpen
        ) {
            drawerState.close()
        }
    }

    // ── Media / QR launchers ────────────────────────────────────────────
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        viewModel.completeFreshPairingScan(result.contents)
    }
    val launchPairingScan: () -> Unit = {
        viewModel.beginFreshPairingScan()
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the Androdex pairing QR")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap == null) return@rememberLauncherForActivityResult
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
        if (granted) cameraLauncher.launch(null)
        else viewModel.reportAttachmentError("Camera permission is required to take a photo.")
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
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

    // ── Overlays ────────────────────────────────────────────────────────
    if (appState.overlay.approvalRequest != null) {
        ApprovalDialog(
            request = appState.overlay.approvalRequest,
            onApprove = { viewModel.respondToApproval(true) },
            onDecline = { viewModel.respondToApproval(false) },
        )
    }
    appState.overlay.errorMessage?.let { message ->
        ErrorMessageDialog(message = message, onDismiss = viewModel::clearError)
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
            onOpenPairingSetup = {
                settingsOpen = false
                viewModel.openManualPairingSetup()
            },
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

    val rootShell = when (destination) {
        is AndrodexDestinationUiState.Onboarding,
        is AndrodexDestinationUiState.Pairing -> RootShell.Pairing
        is AndrodexDestinationUiState.Home,
        is AndrodexDestinationUiState.Thread -> RootShell.Connected
    }
    val connectedRoute = when (destination) {
        is AndrodexDestinationUiState.Home -> {
            val sidebarState = cachedSidebar ?: liveHomeState
            ConnectedRoute.Home(sidebarState)
        }
        is AndrodexDestinationUiState.Thread -> ConnectedRoute.Thread(destination.state)
        is AndrodexDestinationUiState.Onboarding -> null
        is AndrodexDestinationUiState.Pairing -> null
    }
    val connectedRouteTransitionKey = connectedShellTransitionKey(destination)
    val motion = RemodexTheme.motion

    AnimatedContent(
        targetState = rootShell,
        transitionSpec = {
            shellTransform(
                forward = targetState == RootShell.Connected,
                enterDivisor = 8,
                exitDivisor = 12,
                durationMillis = motion.shellMillis,
            )
        },
        label = "rootShellTransition",
    ) { route ->
        when (route) {
            RootShell.Pairing -> {
                when (destination) {
                    is AndrodexDestinationUiState.Onboarding -> {
                        val onboardingState = cachedOnboarding ?: destination.state
                        FirstPairingOnboardingScreen(
                            state = onboardingState,
                            onStartPairing = {
                                viewModel.markFirstPairingOnboardingSeen()
                                launchPairingScan()
                            },
                        )
                    }

                    is AndrodexDestinationUiState.Pairing -> {
                        val pairingState = cachedPairing ?: destination.state
                        PairingScreen(
                            state = pairingState,
                            onPairingInputChanged = viewModel::updatePairingInput,
                            onScanQr = launchPairingScan,
                            onConnect = viewModel::connectWithCurrentPairingInput,
                            onReconnectSaved = viewModel::reconnectSaved,
                            onRepairWithFreshQr = launchPairingScan,
                            onForgetTrustedHost = viewModel::forgetTrustedHost,
                        )
                    }

                    else -> {
                        cachedOnboarding?.let { onboardingState ->
                            FirstPairingOnboardingScreen(
                                state = onboardingState,
                                onStartPairing = {
                                    viewModel.markFirstPairingOnboardingSeen()
                                    launchPairingScan()
                                },
                            )
                        } ?: cachedPairing?.let { pairingState ->
                            PairingScreen(
                                state = pairingState,
                                onPairingInputChanged = viewModel::updatePairingInput,
                                onScanQr = launchPairingScan,
                                onConnect = viewModel::connectWithCurrentPairingInput,
                                onReconnectSaved = viewModel::reconnectSaved,
                                onRepairWithFreshQr = launchPairingScan,
                                onForgetTrustedHost = viewModel::forgetTrustedHost,
                            )
                        }
                    }
                }
            }

            RootShell.Connected -> {
                val sidebarState = cachedSidebar ?: return@AnimatedContent
                BackHandler(
                    enabled = drawerState.isOpen && connectedRoute !is ConnectedRoute.Thread,
                ) {
                    scope.launch { drawerState.close() }
                }

                DismissibleNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = true,
                    drawerContent = {
                        DismissibleDrawerSheet(
                            drawerContainerColor = RemodexTheme.colors.groupedBackground,
                            modifier = Modifier
                                .width(DrawerWidthDp.dp)
                                .fillMaxHeight(),
                        ) {
                            SidebarContent(
                                threadList = sidebarState.threadList,
                                connection = sidebarState.connection,
                                macName = sidebarState.trustedPair?.name,
                                selectedThreadId = selectedThreadId,
                                onRefreshThreads = viewModel::refreshThreads,
                                onCreateThread = { projectPath ->
                                    scope.launch { drawerState.close() }
                                    viewModel.createThread(projectPath)
                                },
                                onOpenThread = { id ->
                                    scope.launch { drawerState.close() }
                                    viewModel.openThread(id)
                                },
                                onOpenSettings = {
                                    scope.launch { drawerState.close() }
                                    settingsOpen = true
                                },
                            )
                        }
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(RemodexTheme.colors.appBackground),
                    ) {
                        AnimatedContent(
                            targetState = connectedRouteTransitionKey,
                            transitionSpec = {
                                when {
                                    initialState == targetState || targetState == null -> {
                                        shellTransform(
                                            forward = true,
                                            enterDivisor = 18,
                                            exitDivisor = 18,
                                            durationMillis = motion.shellMillis,
                                        )
                                    }
                                    targetState is ConnectedShellTransitionKey.Thread -> {
                                        shellTransform(
                                            forward = true,
                                            enterDivisor = 10,
                                            exitDivisor = 14,
                                            durationMillis = motion.shellMillis,
                                        )
                                    }
                                    else -> {
                                        shellTransform(
                                            forward = false,
                                            enterDivisor = 10,
                                            exitDivisor = 14,
                                            durationMillis = motion.shellMillis,
                                        )
                                    }
                                }
                            },
                            label = "connectedShellTransition",
                        ) { transitionKey ->
                            when (transitionKey) {
                                ConnectedShellTransitionKey.Home -> {
                                    val connectedState = connectedRoute as? ConnectedRoute.Home ?: return@AnimatedContent
                                    HomeScreen(
                                        state = connectedState.state,
                                        onOpenSidebar = { scope.launch { drawerState.open() } },
                                        onOpenSettings = { settingsOpen = true },
                                        onCreateThread = viewModel::createThread,
                                        onOpenThread = viewModel::openThread,
                                        onOpenProjects = viewModel::openProjectPicker,
                                        onCloseProjects = viewModel::closeProjectPicker,
                                        onRefreshProjects = viewModel::loadRecentWorkspaces,
                                        onBrowseWorkspace = viewModel::browseWorkspace,
                                        onWorkspaceBrowserPathChanged = viewModel::updateWorkspaceBrowserPath,
                                        onActivateWorkspace = viewModel::activateWorkspace,
                                    )
                                }

                                is ConnectedShellTransitionKey.Thread -> {
                                    val connectedState = connectedRoute as? ConnectedRoute.Thread ?: return@AnimatedContent
                                    if (threadRuntimeOpen) {
                                        ThreadRuntimeSheet(
                                            state = connectedState.state.runtime,
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
                                            state = connectedState.state.fork,
                                            onDismiss = { forkThreadOpen = false },
                                            onFork = { projectPath ->
                                                forkThreadOpen = false
                                                viewModel.forkSelectedThread(projectPath)
                                            },
                                        )
                                    }
                                    if (gitSheetOpen) {
                                        GitSheet(
                                            state = connectedState.state.git,
                                            onDismiss = { gitSheetOpen = false },
                                            onRefreshGit = viewModel::refreshSelectedThreadGitState,
                                            onLoadGitDiff = viewModel::refreshSelectedThreadGitDiff,
                                            onOpenGitCommit = viewModel::openGitCommitDialog,
                                            onPushGit = viewModel::pushGitChanges,
                                            onRequestGitPull = viewModel::requestGitPull,
                                            onOpenGitBranchDialog = viewModel::openGitBranchDialog,
                                            onOpenGitWorktreeDialog = viewModel::openGitWorktreeDialog,
                                        )
                                    }
                                    ThreadTimelineScreen(
                                        state = connectedState.state,
                                        isSidebarOpen = drawerState.isOpen,
                                        onBack = {
                                            when (
                                                threadBackAction(
                                                    isDrawerOpen = drawerState.isOpen,
                                                    isImeVisible = false,
                                                )
                                            ) {
                                                ThreadBackAction.DISMISS_KEYBOARD -> Unit
                                                ThreadBackAction.OPEN_SIDEBAR -> scope.launch { drawerState.open() }
                                                ThreadBackAction.CLOSE_SIDEBAR -> scope.launch { drawerState.close() }
                                            }
                                        },
                                        onOpenSidebar = { scope.launch { drawerState.open() } },
                                        onRefresh = { viewModel.openThread(connectedState.state.threadId) },
                                        onConsumeFocusTurn = viewModel::consumeFocusedTurnId,
                                        onComposerChanged = viewModel::updateComposerText,
                                        onPlanModeChanged = viewModel::updateComposerPlanMode,
                                        onSubagentsModeChanged = viewModel::updateComposerSubagentsEnabled,
                                        onSelectReviewTarget = viewModel::updateComposerReviewTarget,
                                        onReviewBaseBranchChanged = viewModel::updateComposerReviewBaseBranch,
                                        onRemoveReviewSelection = viewModel::clearComposerReviewSelection,
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
                                        onOpenGitSheet = { gitSheetOpen = true },
                                        onOpenFork = {
                                            if (connectedState.state.fork.isEnabled) forkThreadOpen = true
                                        },
                                        onCompactThread = viewModel::compactSelectedThread,
                                        onRollbackThread = viewModel::rollbackSelectedThread,
                                        onCleanBackgroundTerminals = viewModel::cleanSelectedThreadBackgroundTerminals,
                                        onSend = viewModel::sendMessage,
                                        onStop = viewModel::interruptSelectedThread,
                                        onPauseQueue = viewModel::pauseSelectedThreadQueue,
                                        onResumeQueue = viewModel::resumeSelectedThreadQueue,
                                        onRestoreQueuedDraft = viewModel::restoreQueuedDraftToComposer,
                                        onRemoveQueuedDraft = viewModel::removeQueuedDraft,
                                        onToolInputAnswerChanged = viewModel::updateToolInputAnswer,
                                        onSubmitToolInput = viewModel::submitToolInput,
                                        onUpdateGitCommitMessage = viewModel::updateGitCommitMessage,
                                        onDismissGitCommit = viewModel::dismissGitCommitDialog,
                                        onSubmitGitCommit = viewModel::submitGitCommit,
                                        onUpdateGitBranchName = viewModel::updateGitBranchName,
                                        onDismissGitBranchDialog = viewModel::dismissGitBranchDialog,
                                        onRequestCreateGitBranch = viewModel::requestCreateGitBranch,
                                        onRequestSwitchGitBranch = viewModel::requestSwitchGitBranch,
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

                                null -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class RootShell {
    Pairing,
    Connected,
}

private sealed interface ConnectedRoute {
    data class Home(val state: HomeScreenUiState) : ConnectedRoute
    data class Thread(val state: ThreadTimelineUiState) : ConnectedRoute
}

internal sealed interface ConnectedShellTransitionKey {
    data object Home : ConnectedShellTransitionKey
    data class Thread(val threadId: String) : ConnectedShellTransitionKey
}

internal fun connectedShellTransitionKey(
    destination: AndrodexDestinationUiState,
): ConnectedShellTransitionKey? = when (destination) {
    is AndrodexDestinationUiState.Home -> ConnectedShellTransitionKey.Home
    is AndrodexDestinationUiState.Thread -> ConnectedShellTransitionKey.Thread(destination.state.threadId)
    is AndrodexDestinationUiState.Onboarding -> null
    is AndrodexDestinationUiState.Pairing -> null
}

private fun shellTransform(
    forward: Boolean,
    enterDivisor: Int,
    exitDivisor: Int,
    durationMillis: Int,
): ContentTransform {
    return (remodexFadeIn(durationMillis) + remodexSlideInHorizontally(durationMillis) { fullWidth ->
        val distance = fullWidth / enterDivisor
        if (forward) distance else -distance
    }) togetherWith
        (remodexFadeOut(durationMillis) + remodexSlideOutHorizontally(durationMillis) { fullWidth ->
            val distance = fullWidth / exitDivisor
            if (forward) -distance else distance
        })
}
