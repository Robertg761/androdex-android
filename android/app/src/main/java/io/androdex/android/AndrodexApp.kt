package io.androdex.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.ModelOption
import io.androdex.android.model.PlanStep
import io.androdex.android.model.ThreadSummary
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────
// Root composable
// ─────────────────────────────────────────────────

@Composable
fun AndrodexApp(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(viewModel::updatePairingInput)
    }

    if (state.pendingApproval != null) {
        ApprovalDialog(
            request = state.pendingApproval,
            onApprove = { viewModel.respondToApproval(true) },
            onDecline = { viewModel.respondToApproval(false) },
        )
    }

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            confirmButton = {
                TextButton(onClick = viewModel::clearError) {
                    Text("OK")
                }
            },
            title = {
                Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            },
            shape = MaterialTheme.shapes.large,
        )
    }

    if (settingsOpen) {
        RuntimeSettingsSheet(
            models = state.availableModels,
            selectedModelId = state.selectedModelId,
            selectedReasoningEffort = state.selectedReasoningEffort,
            isLoading = state.isLoadingRuntimeConfig,
            onDismiss = { settingsOpen = false },
            onReload = viewModel::loadRuntimeConfig,
            onSelectModel = viewModel::selectModel,
            onSelectReasoning = viewModel::selectReasoningEffort,
        )
    }

    if (state.selectedThreadId == null) {
        if (state.connectionStatus == ConnectionStatus.CONNECTED) {
            ThreadListScreen(
                state = state,
                onDisconnect = { viewModel.disconnect(clearSavedPairing = false) },
                onForgetPairing = { viewModel.disconnect(clearSavedPairing = true) },
                onRefresh = viewModel::refreshThreads,
                onCreateThread = viewModel::createThread,
                onOpenThread = viewModel::openThread,
                onOpenSettings = { settingsOpen = true },
                onOpenProjects = viewModel::openProjectPicker,
                onCloseProjects = viewModel::closeProjectPicker,
                onLoadRecentWorkspaces = viewModel::loadRecentWorkspaces,
                onBrowseWorkspace = viewModel::browseWorkspace,
                onWorkspaceBrowserPathChanged = viewModel::updateWorkspaceBrowserPath,
                onActivateWorkspace = viewModel::activateWorkspace,
            )
        } else {
            PairingScreen(
                state = state,
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
    } else {
        ThreadDetailScreen(
            state = state,
            onBack = viewModel::closeThread,
            onRefresh = { state.selectedThreadId?.let(viewModel::openThread) },
            onComposerChanged = viewModel::updateComposerText,
            onSend = viewModel::sendMessage,
        )
    }
}

// ─────────────────────────────────────────────────
// Approval Dialog
// ─────────────────────────────────────────────────

@Composable
private fun ApprovalDialog(
    request: ApprovalRequest?,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
) {
    val activeRequest = request ?: return
    AlertDialog(
        onDismissRequest = { },
        confirmButton = {
            Button(onClick = onApprove) {
                Text("Approve")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline) {
                Text("Decline")
            }
        },
        icon = {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Text("Approval Required", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = activeRequest.method,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                activeRequest.command?.takeIf { it.isNotBlank() }?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                activeRequest.reason?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        shape = MaterialTheme.shapes.large,
    )
}

// ─────────────────────────────────────────────────
// Pairing Screen
// ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingScreen(
    state: AndrodexUiState,
    onPairingInputChanged: (String) -> Unit,
    onScanQr: () -> Unit,
    onConnect: () -> Unit,
    onReconnectSaved: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Androdex", style = MaterialTheme.typography.titleLarge)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            StatusCapsule(
                status = state.connectionStatus,
                detail = state.connectionDetail,
                fingerprint = state.secureFingerprint,
            )

            Spacer(modifier = Modifier.weight(1f))

            // Branding + instructions
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Connect to your PC",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Run androdex pair once on your host to trust this phone, then reconnect from saved pairing later. Once connected, choose or switch projects directly from Android.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                state.defaultRelayUrl?.let { defaultRelayUrl ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = "Default relay: $defaultRelayUrl",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Pairing input card
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = state.pairingInput,
                        onValueChange = onPairingInputChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        label = { Text("Pairing payload") },
                        placeholder = { Text("{\"v\":3,...}") },
                        shape = MaterialTheme.shapes.medium,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = onScanQr,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.QrCode2,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan QR")
                        }
                        Button(
                            onClick = onConnect,
                            enabled = !state.isBusy,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text("Connect")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(visible = state.hasSavedPairing) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 40.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    val reconnectButtonLabel = when (state.connectionStatus) {
                        ConnectionStatus.RETRYING_SAVED_PAIRING -> "Retrying Saved Pairing..."
                        ConnectionStatus.RECONNECT_REQUIRED -> "Reconnect Saved Pairing"
                        ConnectionStatus.UPDATE_REQUIRED -> "Reconnect After Updating"
                        else -> "Reconnect Saved Pairing"
                    }
                    FilledTonalButton(
                        onClick = onReconnectSaved,
                        enabled = !state.isBusy && state.connectionStatus != ConnectionStatus.RETRYING_SAVED_PAIRING,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(reconnectButtonLabel)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = state.isBusy,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

// ─────────────────────────────────────────────────
// Thread List Screen
// ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadListScreen(
    state: AndrodexUiState,
    onDisconnect: () -> Unit,
    onForgetPairing: () -> Unit,
    onRefresh: () -> Unit,
    onCreateThread: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProjects: () -> Unit,
    onCloseProjects: () -> Unit,
    onLoadRecentWorkspaces: () -> Unit,
    onBrowseWorkspace: (String?) -> Unit,
    onWorkspaceBrowserPathChanged: (String) -> Unit,
    onActivateWorkspace: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    if (state.isProjectPickerOpen) {
        ProjectPickerSheet(
            state = state,
            onDismiss = onCloseProjects,
            onRefresh = onLoadRecentWorkspaces,
            onBrowse = onBrowseWorkspace,
            onBrowserPathChanged = onWorkspaceBrowserPathChanged,
            onActivateWorkspace = onActivateWorkspace,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Threads", style = MaterialTheme.typography.titleLarge)
                },
                actions = {
                    TextButton(onClick = onOpenProjects) {
                        Text("Projects")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                onClick = {
                                    menuExpanded = false
                                    onDisconnect()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Forget Pairing",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onForgetPairing()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateThread,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New Chat") },
                shape = MaterialTheme.shapes.large,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 2.dp,
                    pressedElevation = 6.dp,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .safeDrawingPadding(),
        ) {
            StatusCapsule(
                status = state.connectionStatus,
                detail = state.connectionDetail,
                fingerprint = state.secureFingerprint,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            BusyIndicator(state = state)

            ActiveWorkspaceBanner(
                activeWorkspacePath = state.activeWorkspacePath,
                onOpenProjects = onOpenProjects,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (state.threads.isEmpty() && !state.isBusy) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No conversations yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (state.activeWorkspacePath == null) {
                                "Choose a project to start chatting"
                            } else {
                                "Tap \"New Chat\" to start one"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        if (state.activeWorkspacePath == null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            FilledTonalButton(onClick = onOpenProjects) {
                                Text("Choose Project")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.threads, key = { it.id }) { thread ->
                        ThreadCard(
                            thread = thread,
                            onOpenThread = onOpenThread,
                        )
                    }
                    // Bottom spacer so FAB doesn't cover the last card
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ActiveWorkspaceBanner(
    activeWorkspacePath: String?,
    onOpenProjects: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Active Project",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = activeWorkspacePath ?: "No project selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(onClick = onOpenProjects) {
                Text(if (activeWorkspacePath == null) "Choose" else "Switch")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectPickerSheet(
    state: AndrodexUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onBrowse: (String?) -> Unit,
    onBrowserPathChanged: (String) -> Unit,
    onActivateWorkspace: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    val isBrowsing = state.workspaceBrowserPath != null || state.workspaceBrowserEntries.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Projects", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }

            if (state.isWorkspaceBrowserLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Current Project", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = state.activeWorkspacePath ?: "No project selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (!isBrowsing) {
                if (state.recentWorkspaces.isNotEmpty()) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium)
                    state.recentWorkspaces.forEach { workspace ->
                        WorkspaceRow(
                            title = workspace.name,
                            subtitle = workspace.path,
                            active = workspace.isActive,
                            onClick = { onActivateWorkspace(workspace.path) },
                        )
                    }
                }

                FilledTonalButton(
                    onClick = { onBrowse(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Browse Folders")
                }
            } else {
                Text("Browse Host Folders", style = MaterialTheme.typography.titleMedium)
                TextField(
                    value = state.workspaceBrowserPath.orEmpty(),
                    onValueChange = onBrowserPathChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Folder path") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onBrowse(state.workspaceBrowserParentPath) },
                        enabled = state.workspaceBrowserParentPath != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Up")
                    }
                    Button(
                        onClick = { onBrowse(state.workspaceBrowserPath) },
                        enabled = !state.workspaceBrowserPath.isNullOrBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Open")
                    }
                }

                state.workspaceBrowserEntries.forEach { entry ->
                    WorkspaceRow(
                        title = entry.name,
                        subtitle = entry.path,
                        active = entry.isActive,
                        onClick = {
                            if (entry.source == "recent") {
                                onActivateWorkspace(entry.path)
                            } else {
                                onBrowse(entry.path)
                            }
                        },
                    )
                }

                Button(
                    onClick = { state.workspaceBrowserPath?.let(onActivateWorkspace) },
                    enabled = !state.workspaceBrowserPath.isNullOrBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Use This Folder")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceRow(
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (active) {
                    Text("Active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ThreadCard(thread: ThreadSummary, onOpenThread: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenThread(thread.id) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = thread.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                thread.updatedAtEpochMs?.let { epochMs ->
                    Text(
                        text = relativeTimeLabel(epochMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            thread.preview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Project badge
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = thread.projectName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────
// Thread Detail / Conversation Screen
// ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadDetailScreen(
    state: AndrodexUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onComposerChanged: (String) -> Unit,
    onSend: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val listState = remember { LazyListState() }
    val coroutineScope = rememberCoroutineScope()
    val showJumpToLatest by remember {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems == 0) {
                false
            } else {
                val lastVisibleIndex =
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisibleIndex < totalItems - 1
            }
        }
    }

    LaunchedEffect(state.selectedThreadId, state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.selectedThreadTitle ?: "Conversation",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding(),
        ) {
            BusyIndicator(state = state)
            AgentActivityBanner(messages = state.messages)

            // Messages
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }
                }

                if (showJumpToLatest) {
                    SmallFloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                if (state.messages.isNotEmpty()) {
                                    listState.animateScrollToItem(state.messages.lastIndex)
                                }
                            }
                        },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Jump to latest",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // Composer bar
            ComposerBar(
                text = state.composerText,
                isBusy = state.isBusy,
                onTextChange = onComposerChanged,
                onSend = onSend,
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ConversationMessage) {
    val isUser = message.role == ConversationRole.USER
    val isSystem = message.role == ConversationRole.SYSTEM

    if (isSystem) {
        when (message.kind) {
            ConversationKind.FILE_CHANGE -> FileChangeBubble(message)
            ConversationKind.COMMAND -> CommandBubble(message)
            ConversationKind.PLAN -> PlanBubble(message)
            ConversationKind.THINKING -> ThinkingBubble(message)
            else -> SystemCapsule(message)
        }
        return
    }

    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleShape = if (isUser) {
        RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    }

    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Surface(
            shape = bubbleShape,
            color = containerColor,
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (message.isStreaming) {
                    StreamingIndicator()
                }

                Text(
                    text = message.text.ifBlank { " " },
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                )

                Text(
                    text = DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(Date(message.createdAtEpochMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.5f),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────
// System message: generic capsule
// ─────────────────────────────────────────────────

@Composable
private fun SystemCapsule(message: ConversationMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = message.text.ifBlank { " " },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────
// Thinking bubble
// ─────────────────────────────────────────────────

@Composable
private fun ThinkingBubble(message: ConversationMessage) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "thinkingPulse",
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Animated thinking dots
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { index ->
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            tween(600, delayMillis = index * 150),
                            RepeatMode.Reverse,
                        ),
                        label = "dot$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha)),
                    )
                }
            }
            Text(
                text = message.text.ifBlank { "Thinking..." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─────────────────────────────────────────────────
// File change bubble with diff rendering
// ─────────────────────────────────────────────────

@Composable
private fun FileChangeBubble(message: ConversationMessage) {
    val isCompleted = message.status?.lowercase() == "completed"
    val statusColor = if (isCompleted) Color(0xFF34D399) else Color(0xFFFBBF24)
    var expanded by remember { mutableStateOf(true) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // Header with file path and status
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    // File icon + path
                    Text(
                        text = message.filePath
                            ?.substringAfterLast('/')
                            ?.substringAfterLast('\\')
                            ?: "File Change",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Status badge
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = (message.status ?: "changed").replaceFirstChar(Char::uppercase),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            // Full file path (if available)
            message.filePath?.let { path ->
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // Diff content
            AnimatedVisibility(visible = expanded) {
                if (!message.diffText.isNullOrBlank()) {
                    DiffView(
                        diffText = message.diffText,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                } else {
                    // Fallback: show summary text
                    val summaryText = message.text
                        .removePrefix("Status: ${message.status ?: "completed"}")
                        .removePrefix("\n\n")
                        .removePrefix("Path: ${message.filePath ?: ""}")
                        .removePrefix("\n\n")
                        .trim()
                    if (summaryText.isNotBlank()) {
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────
// Diff view - color-coded unified diff
// ─────────────────────────────────────────────────

@Composable
private fun DiffView(diffText: String, modifier: Modifier = Modifier) {
    val addedBg = Color(0xFF22C55E).copy(alpha = 0.12f)
    val removedBg = Color(0xFFEF4444).copy(alpha = 0.12f)
    val addedText = Color(0xFF16A34A)
    val removedText = Color(0xFFDC2626)
    val hunkText = Color(0xFF6366F1)
    val contextText = MaterialTheme.colorScheme.onSurfaceVariant

    val scrollState = rememberScrollState()

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(0.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(vertical = 4.dp),
        ) {
            diffText.lines().forEach { line ->
                val (bgColor, fgColor) = when {
                    line.startsWith("+++") || line.startsWith("---") ->
                        Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                    line.startsWith("+") ->
                        addedBg to addedText
                    line.startsWith("-") ->
                        removedBg to removedText
                    line.startsWith("@@") ->
                        Color.Transparent to hunkText
                    else ->
                        Color.Transparent to contextText
                }

                Text(
                    text = line.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
                    color = fgColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                        .padding(horizontal = 12.dp, vertical = 1.dp),
                    softWrap = false,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────
// Command execution bubble
// ─────────────────────────────────────────────────

@Composable
private fun CommandBubble(message: ConversationMessage) {
    val cmdStatus = message.status?.lowercase() ?: "completed"
    val isRunning = cmdStatus == "running" || cmdStatus == "in_progress"
    val isFailed = cmdStatus == "failed" || cmdStatus == "error"
    val statusColor = when {
        isRunning -> Color(0xFFFBBF24)
        isFailed -> Color(0xFFEF4444)
        else -> Color(0xFF34D399)
    }
    val statusIcon = when {
        isRunning -> ">"
        isFailed -> "x"
        else -> "$"
    }

    Surface(
        color = Color(0xFF1E1E2E),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Terminal header
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Terminal dots
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF5F57)))
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFFBD2E)))
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF28C840)))
                }
                Spacer(modifier = Modifier.weight(1f))
                // Status badge
                Surface(
                    color = statusColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = cmdStatus.replaceFirstChar(Char::uppercase),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Command text
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = statusIcon,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = statusColor,
                )
                Text(
                    text = message.command ?: message.text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = Color(0xFFCDD6F4),
                )
            }

            if (isRunning) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = statusColor,
                    trackColor = Color(0xFF313244),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────
// Plan bubble with step checkmarks
// ─────────────────────────────────────────────────

@Composable
private fun PlanBubble(message: ConversationMessage) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = "PLAN",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (message.planSteps != null) {
                message.planSteps.forEachIndexed { index, step ->
                    PlanStepRow(
                        index = index + 1,
                        step = step,
                    )
                    if (index < message.planSteps.lastIndex) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            } else {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PlanStepRow(index: Int, step: PlanStep) {
    val isComplete = step.status?.lowercase() in listOf("completed", "done", "complete")
    val isActive = step.status?.lowercase() in listOf("in_progress", "active", "running")
    val stepColor = when {
        isComplete -> Color(0xFF34D399)
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val indicator = when {
        isComplete -> "+"
        isActive -> ">"
        else -> "$index"
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            color = stepColor.copy(alpha = 0.15f),
            shape = CircleShape,
            modifier = Modifier.size(22.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = indicator,
                    style = MaterialTheme.typography.labelSmall,
                    color = stepColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isComplete) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            step.status?.takeIf { it.isNotBlank() && !isComplete }?.let {
                Text(
                    text = it.replaceFirstChar(Char::uppercase),
                    style = MaterialTheme.typography.labelSmall,
                    color = stepColor,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────
// Streaming / activity indicator
// ─────────────────────────────────────────────────

@Composable
private fun StreamingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "streaming")

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        // Animated pulsing dots
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { index ->
                val dotScale by infiniteTransition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(
                        tween(500, delayMillis = index * 120),
                        RepeatMode.Reverse,
                    ),
                    label = "streamDot$index",
                )
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .scale(dotScale)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Writing",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ComposerBar(
    text: String,
    isBusy: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "Ask Codex…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
                maxLines = 5,
            )

            val canSend = text.isNotBlank() && !isBusy
            IconButton(
                onClick = onSend,
                enabled = canSend,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (canSend)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (canSend)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier.size(44.dp),
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────
// Status / Busy components
// ─────────────────────────────────────────────────

@Composable
private fun StatusCapsule(
    status: ConnectionStatus,
    detail: String?,
    fingerprint: String?,
    modifier: Modifier = Modifier,
) {
    val (dotColor, label) = when (status) {
        ConnectionStatus.CONNECTED -> Color(0xFF34D399) to "Connected"
        ConnectionStatus.CONNECTING -> Color(0xFFFBBF24) to "Connecting"
        ConnectionStatus.HANDSHAKING -> Color(0xFFFBBF24) to "Handshaking"
        ConnectionStatus.RETRYING_SAVED_PAIRING -> Color(0xFFF59E0B) to "Waiting For Host"
        ConnectionStatus.RECONNECT_REQUIRED -> Color(0xFFF87171) to "Repair Pairing Required"
        ConnectionStatus.UPDATE_REQUIRED -> Color(0xFFF87171) to "Update Required"
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline to "Disconnected"
    }
    val guidance = when (status) {
        ConnectionStatus.RETRYING_SAVED_PAIRING -> "Saved pairing is still trusted. We'll retry automatically when the host or relay comes back."
        ConnectionStatus.RECONNECT_REQUIRED -> "Saved pairing needs attention. Reconnect from saved pairing or scan a fresh QR code if trust changed."
        ConnectionStatus.UPDATE_REQUIRED -> updateRequiredGuidance(detail)
        else -> null
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                AnimatedContent(
                    targetState = label,
                    transitionSpec = {
                        fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                    },
                    label = "statusLabel",
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            guidance?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (detail.isNullOrBlank()) 4.dp else 2.dp),
                )
            }
            fingerprint?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "Host: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun updateRequiredGuidance(detail: String?): String {
    val normalized = detail?.lowercase() ?: return "Update the Android app or host bridge, then reconnect with the saved pairing."
    return when {
        normalized.contains("latest compatible androdex mobile app") ->
            "This host bridge needs a newer Android app build. Update the Android app, then reconnect with the saved pairing."
        normalized.contains("bridge and mobile client are not using the same secure transport version") ->
            "The Android app and host bridge are out of sync. Update whichever side is older, then reconnect with the saved pairing."
        normalized.contains("bridge version mismatch") || normalized.contains("different secure transport version") ->
            "This saved pairing reached a host bridge with a different secure transport version. Update the host bridge or Android app, then reconnect."
        else -> "Update the Android app or host bridge, then reconnect with the saved pairing."
    }
}

@Composable
private fun BusyIndicator(state: AndrodexUiState) {
    val label = state.busyLabel ?: if (state.isLoadingRuntimeConfig) "Loading models…" else null
    AnimatedVisibility(
        visible = state.isBusy || state.isLoadingRuntimeConfig,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            )
            label?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun AgentActivityBanner(messages: List<ConversationMessage>) {
    val isStreaming = messages.any { it.isStreaming }
    val lastSystemMessage = messages.lastOrNull { it.role == ConversationRole.SYSTEM }

    val activityText = when {
        !isStreaming && lastSystemMessage == null -> null
        isStreaming -> "Agent is writing a response..."
        lastSystemMessage?.kind == ConversationKind.FILE_CHANGE -> {
            val fileName = lastSystemMessage.filePath
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
            if (fileName != null) "Edited $fileName" else "Edited files"
        }
        lastSystemMessage?.kind == ConversationKind.COMMAND -> {
            "Ran: ${lastSystemMessage.command?.take(40) ?: "command"}"
        }
        lastSystemMessage?.kind == ConversationKind.THINKING -> "Thinking..."
        lastSystemMessage?.kind == ConversationKind.PLAN -> "Planning..."
        else -> null
    }

    AnimatedVisibility(
        visible = isStreaming,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = activityText ?: "Working...",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────
// Runtime Settings (Bottom Sheet)
// ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuntimeSettingsSheet(
    models: List<ModelOption>,
    selectedModelId: String?,
    selectedReasoningEffort: String?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onSelectModel: (String?) -> Unit,
    onSelectReasoning: (String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    val selectedModel = models.firstOrNull { it.id == selectedModelId || it.model == selectedModelId }
        ?: models.firstOrNull { it.isDefault }
        ?: models.firstOrNull()
    val supportedEfforts = selectedModel?.supportedReasoningEfforts.orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Runtime Settings",
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onReload) {
                    Text("Reload")
                }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Choose the model and reasoning effort for new turns.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(20.dp))
            SectionHeader("Model")
            Spacer(modifier = Modifier.height(8.dp))

            SettingsOptionRow(
                title = "Auto",
                subtitle = selectedModel?.displayName?.takeIf { it.isNotBlank() }
                    ?.let { "Default: $it" },
                selected = selectedModelId == null,
                onClick = { onSelectModel(null) },
            )

            models.forEach { model ->
                SettingsOptionRow(
                    title = model.displayName,
                    subtitle = model.description.ifBlank { model.model },
                    selected = selectedModelId == model.stableIdentifier || selectedModelId == model.model,
                    onClick = { onSelectModel(model.stableIdentifier) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            SectionHeader("Reasoning")
            Spacer(modifier = Modifier.height(8.dp))

            SettingsOptionRow(
                title = "Auto",
                subtitle = selectedModel?.defaultReasoningEffort?.let { "Default: $it" },
                selected = selectedReasoningEffort == null,
                onClick = { onSelectReasoning(null) },
            )

            supportedEfforts.forEach { effort ->
                SettingsOptionRow(
                    title = effort.reasoningEffort,
                    subtitle = effort.description,
                    selected = selectedReasoningEffort == effort.reasoningEffort,
                    onClick = { onSelectReasoning(effort.reasoningEffort) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 1.5f,
    )
}

@Composable
private fun SettingsOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────

private fun relativeTimeLabel(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMs
    if (diff < 0) return "now"

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
