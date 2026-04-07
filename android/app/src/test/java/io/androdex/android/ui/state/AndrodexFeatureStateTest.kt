package io.androdex.android.ui.state

import io.androdex.android.GitActionKind
import io.androdex.android.ThreadGitState
import io.androdex.android.AndrodexUiState
import io.androdex.android.FreshPairingAttemptState
import io.androdex.android.FreshPairingStage
import io.androdex.android.model.AccessMode
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ComposerImageAttachment
import io.androdex.android.model.ComposerImageAttachmentState
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.HostAccountSnapshot
import io.androdex.android.model.HostAccountSnapshotOrigin
import io.androdex.android.model.HostAccountStatus
import io.androdex.android.model.HostRuntimeMetadata
import io.androdex.android.model.ImageAttachment
import io.androdex.android.model.GitRepoSyncResult
import io.androdex.android.model.ModelOption
import io.androdex.android.model.QueuePauseState
import io.androdex.android.model.QueuedTurnDraft
import io.androdex.android.model.ReasoningEffortOption
import io.androdex.android.model.ServiceTier
import io.androdex.android.model.ThreadCapabilities
import io.androdex.android.model.ThreadCapabilityFlag
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ThreadQueuedDraftState
import io.androdex.android.model.ThreadRuntimeOverride
import io.androdex.android.model.ToolUserInputQuestion
import io.androdex.android.model.ToolUserInputOption
import io.androdex.android.model.ToolUserInputRequest
import io.androdex.android.model.WorkspaceDirectoryEntry
import io.androdex.android.model.WorkspacePathSummary
import io.androdex.android.model.TrustedPairSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndrodexFeatureStateTest {
    @Test
    fun onboardingRoute_showsForUnpairedInstallsThatHaveNotSeenIt() {
        val state = AndrodexUiState(
            hasSeenFirstPairingOnboarding = false,
            isFirstPairingOnboardingActive = true,
        )

        val appState = state.toAppUiState(isSettingsVisible = false)

        assertTrue(appState.destination is AndrodexDestinationUiState.Onboarding)
    }

    @Test
    fun onboardingRoute_isSkippedAfterItHasBeenSeen() {
        val state = AndrodexUiState(
            hasSeenFirstPairingOnboarding = true,
            isFirstPairingOnboardingActive = false,
        )

        val appState = state.toAppUiState(isSettingsVisible = false)

        assertTrue(appState.destination is AndrodexDestinationUiState.Pairing)
    }

    @Test
    fun pairingRoute_usesReconnectPresentationForSavedPairingRecovery() {
        val state = AndrodexUiState(
            hasSeenFirstPairingOnboarding = true,
            hasSavedPairing = true,
            trustedPairSnapshot = TrustedPairSnapshot(
                deviceId = "host-1234",
                relayUrl = "wss://relay.example.com/socket",
                fingerprint = "ABCD1234EFGH5678",
                lastPairedAtEpochMs = 1_000L,
            ),
            hostAccountSnapshot = HostAccountSnapshot(
                status = HostAccountStatus.AUTHENTICATED,
                authMethod = "chatgpt",
                email = "host@example.com",
                planType = "pro",
                bridgeVersion = "1.1.3",
            ),
            connectionStatus = ConnectionStatus.RETRYING_SAVED_PAIRING,
            connectionDetail = "Waiting for host",
        )

        val appState = state.toAppUiState(isSettingsVisible = false)
        val route = appState.destination as AndrodexDestinationUiState.Pairing

        assertEquals("Retrying Saved Pairing...", route.state.reconnectButtonLabel)
        assertFalse(route.state.reconnectEnabled)
        assertEquals(ConnectionStatus.RETRYING_SAVED_PAIRING, route.state.connection.status)
        assertNotNull(route.state.trustedPair)
        assertEquals("Authenticated", route.state.hostAccount?.statusLabel)
        assertEquals(SharedStatusTone.Success, route.state.hostAccount?.tone)
        assertEquals("host@example.com • pro", route.state.hostAccount?.detail)
        assertEquals("Retrying saved pair", route.state.trustedPair?.statusLabel)
        assertEquals(SharedStatusTone.Accent, route.state.trustedPair?.tone)
        assertEquals("relay.example.com", route.state.trustedPair?.relayLabel)
        assertTrue(route.state.compatibilityMessage.isNullOrBlank())
    }

    @Test
    fun pairingRoute_overridesReconnectPresentationDuringFreshPairing() {
        val state = AndrodexUiState(
            hasSeenFirstPairingOnboarding = true,
            hasSavedPairing = true,
            trustedPairSnapshot = TrustedPairSnapshot(
                deviceId = "host-1234",
                relayUrl = "wss://relay.example.com/socket",
                fingerprint = "ABCD1234EFGH5678",
                lastPairedAtEpochMs = 1_000L,
                hasSavedRelaySession = true,
            ),
            connectionStatus = ConnectionStatus.RETRYING_SAVED_PAIRING,
            connectionDetail = "Waiting for host",
            freshPairingAttempt = FreshPairingAttemptState(FreshPairingStage.SCANNER_OPEN),
        )

        val appState = state.toAppUiState(isSettingsVisible = false)
        val route = appState.destination as AndrodexDestinationUiState.Pairing

        assertEquals(ConnectionStatus.CONNECTING, route.state.connection.status)
        assertEquals("Fresh pairing ready", route.state.connection.presentationOverride?.title)
        assertFalse(route.state.reconnectEnabled)
        assertEquals("Fresh Pairing", route.state.recoveryTitle)
        assertTrue(route.state.recoveryMessage.contains("Saved reconnect is paused"))
        assertEquals("Connecting", route.state.trustedPair?.statusLabel)
    }

    @Test
    fun pairingRoute_surfacesTrustBlockedRepairState() {
        val state = AndrodexUiState(
            hasSeenFirstPairingOnboarding = true,
            hasSavedPairing = true,
            trustedPairSnapshot = TrustedPairSnapshot(
                deviceId = "host-1234",
                relayUrl = "wss://relay.example.com/socket",
                fingerprint = "ABCD1234EFGH5678",
                lastPairedAtEpochMs = 1_000L,
            ),
            connectionStatus = ConnectionStatus.TRUST_BLOCKED,
            connectionDetail = "Local trust is unreadable.",
        )

        val appState = state.toAppUiState(isSettingsVisible = false)
        val route = appState.destination as AndrodexDestinationUiState.Pairing
        val homeState = state.toHomeScreenUiState()

        assertEquals("Repair With Fresh QR", route.state.reconnectButtonLabel)
        assertTrue(route.state.reconnectEnabled)
        assertEquals(ConnectionStatus.TRUST_BLOCKED, route.state.connection.status)
        assertEquals("Repair with fresh QR", route.state.trustedPair?.statusLabel)
        assertEquals("Local Trust Blocked", homeState.bridgeStatus.title)
    }

    @Test
    fun pairingRoute_surfacesAccountSourceAndRateLimits() {
        val state = AndrodexUiState(
            hasSeenFirstPairingOnboarding = true,
            hostAccountSnapshot = HostAccountSnapshot(
                status = HostAccountStatus.AUTHENTICATED,
                authMethod = "chatgpt",
                email = "host@example.com",
                planType = "pro",
                bridgeVersion = "1.1.3",
                rateLimits = listOf(
                    io.androdex.android.model.HostRateLimitBucket(
                        name = "gpt-5.4",
                        remaining = 42,
                        limit = 100,
                        resetsAtEpochMs = 1_774_614_600_000L,
                    )
                ),
                origin = HostAccountSnapshotOrigin.NATIVE_LIVE,
            ),
        )

        val appState = state.toAppUiState(isSettingsVisible = false)
        val route = appState.destination as AndrodexDestinationUiState.Pairing

        assertEquals("Live host updates", route.state.hostAccount?.sourceLabel)
        assertEquals("Managed on the host", route.state.hostAccount?.authControlLabel)
        assertEquals("gpt-5.4", route.state.hostAccount?.rateLimits?.single()?.name)
        assertEquals("42 left of 100", route.state.hostAccount?.rateLimits?.single()?.usageLabel)
    }

    @Test
    fun homeRoute_formatsThreadListAndProjectPickerState() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            hasLoadedThreadList = true,
            trustedPairSnapshot = TrustedPairSnapshot(
                deviceId = "host-1234",
                relayUrl = "wss://relay.example.com/socket",
                fingerprint = "ABCD1234EFGH5678",
                lastPairedAtEpochMs = 1_000L,
            ),
            hostAccountSnapshot = HostAccountSnapshot(
                status = HostAccountStatus.AUTHENTICATED,
                authMethod = "chatgpt",
                email = "host@example.com",
                planType = "pro",
                bridgeVersion = "1.1.3",
            ),
            hostRuntimeMetadata = HostRuntimeMetadata(
                runtimeTarget = "codex-native",
                runtimeTargetDisplayName = "Codex Native",
                backendProvider = "codex-native",
                backendProviderDisplayName = "Codex Native",
            ),
            activeWorkspacePath = "C:\\Projects\\Androdex",
            runningThreadIds = setOf("thread-1"),
            threads = listOf(
                ThreadSummary(
                    id = "thread-1",
                    title = "Ship the refactor",
                    preview = "Working on Android structure",
                    cwd = "C:\\Projects\\Androdex",
                    createdAtEpochMs = null,
                    updatedAtEpochMs = 30L * 60L * 1000L,
                )
            ),
            isProjectPickerOpen = true,
            recentWorkspaces = listOf(
                WorkspacePathSummary(
                    path = "C:\\Projects\\Androdex",
                    name = "Androdex",
                    isActive = true,
                )
            ),
            workspaceBrowserPath = "C:\\Projects",
            workspaceBrowserEntries = listOf(
                WorkspaceDirectoryEntry(
                    path = "C:\\Projects\\Androdex",
                    name = "Androdex",
                    isDirectory = true,
                    isActive = true,
                    source = "recent",
                )
            ),
        )

        val appState = state.toAppUiState(
            isSettingsVisible = false,
            nowEpochMs = 60L * 60L * 1000L,
        )
        val route = appState.destination as AndrodexDestinationUiState.Home

        assertEquals("30m ago", route.state.threadList.threads.single().updatedLabel)
        assertEquals("Androdex", route.state.threadList.threads.single().projectName)
        assertEquals("C:\\Projects\\Androdex", route.state.threadList.threads.single().projectPath)
        assertEquals("C:\\Projects\\Androdex", route.state.threadList.activeWorkspacePath)
        assertEquals(ThreadRunBadgeUiState.RUNNING, route.state.threadList.threads.single().runState)
        assertFalse(route.state.threadList.showLoadingOverlay)
        assertEquals("Bridge Ready", route.state.bridgeStatus.title)
        assertEquals("Connected", route.state.bridgeStatus.statusLabel)
        assertEquals(SharedStatusTone.Success, route.state.bridgeStatus.tone)
        assertEquals("Codex Native", route.state.bridgeStatus.runtimeTargetLabel)
        assertEquals("Codex Native", route.state.bridgeStatus.backendProviderLabel)
        assertNotNull(route.state.trustedPair)
        assertEquals("Authenticated", route.state.hostAccount?.statusLabel)
        assertEquals(SharedStatusTone.Success, route.state.hostAccount?.tone)
        assertEquals("Connected pair", route.state.trustedPair?.statusLabel)
        assertEquals(SharedStatusTone.Success, route.state.trustedPair?.tone)
        assertNotNull(route.state.projectPicker)
        assertTrue(route.state.projectPicker?.isBrowsing == true)
        assertEquals(
            WorkspaceRowAction.ACTIVATE,
            route.state.projectPicker?.browserEntries?.single()?.action,
        )
    }

    @Test
    fun homeRoute_marksRepairAndUpdateBridgeStatesWithWarningTone() {
        val repairState = AndrodexUiState(
            connectionStatus = ConnectionStatus.RECONNECT_REQUIRED,
        ).toHomeScreenUiState()

        val updateState = AndrodexUiState(
            connectionStatus = ConnectionStatus.UPDATE_REQUIRED,
        ).toHomeScreenUiState()

        assertEquals("Repair needed", repairState.bridgeStatus.statusLabel)
        assertEquals(SharedStatusTone.Warning, repairState.bridgeStatus.tone)
        assertEquals("Update required", updateState.bridgeStatus.statusLabel)
        assertEquals(SharedStatusTone.Warning, updateState.bridgeStatus.tone)
    }

    @Test
    fun homeRoute_marksTrustBlockedBridgeStateWithWarningTone() {
        val blockedState = AndrodexUiState(
            connectionStatus = ConnectionStatus.TRUST_BLOCKED,
        ).toHomeScreenUiState()

        assertEquals("Local Trust Blocked", blockedState.bridgeStatus.title)
        assertEquals("Blocked", blockedState.bridgeStatus.statusLabel)
        assertEquals(SharedStatusTone.Warning, blockedState.bridgeStatus.tone)
    }

    @Test
    fun homeRoute_distinguishesTrustedHostReadyFromGenericOffline() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.DISCONNECTED,
            trustedPairSnapshot = TrustedPairSnapshot(
                deviceId = "host-1234",
                relayUrl = "wss://relay.example.com/socket",
                fingerprint = "ABCD1234EFGH5678",
                lastPairedAtEpochMs = 1_000L,
                displayName = "Robert's Mac",
                hasSavedRelaySession = false,
            ),
        ).toHomeScreenUiState()

        assertEquals("Trusted Host Ready", state.bridgeStatus.title)
        assertEquals("Trusted host", state.bridgeStatus.statusLabel)
        assertEquals("Robert's Mac", state.trustedPair?.name)
        assertEquals("Trusted host", state.trustedPair?.statusLabel)
    }

    @Test
    fun homeRoute_keepsThreadListInLoadingStateUntilFirstSuccessfulThreadSync() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            threads = emptyList(),
            hasLoadedThreadList = false,
        )

        val appState = state.toAppUiState(isSettingsVisible = false)
        val route = appState.destination as AndrodexDestinationUiState.Home

        assertTrue(route.state.threadList.isLoading)
        assertFalse(route.state.threadList.showLoadingOverlay)
        assertEquals(null, route.state.threadList.emptyState)
    }

    @Test
    fun homeRoute_showsEmptyStateAfterLoadedEmptyThreadSync() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            threads = emptyList(),
            hasLoadedThreadList = true,
        )

        val appState = state.toAppUiState(isSettingsVisible = false)
        val route = appState.destination as AndrodexDestinationUiState.Home

        assertFalse(route.state.threadList.isLoading)
        assertFalse(route.state.threadList.showLoadingOverlay)
        assertEquals("No conversations yet", route.state.threadList.emptyState?.title)
        assertFalse(route.state.threadList.emptyState?.message.orEmpty().contains("New Chat"))
    }

    @Test
    fun homeRoute_showsSidebarLoadingOverlayFlagWhenRefreshingExistingThreads() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            threads = listOf(
                ThreadSummary(
                    id = "thread-1",
                    title = "Existing",
                    preview = null,
                    cwd = "/Users/robert/Documents/Projects/androdex",
                    createdAtEpochMs = null,
                    updatedAtEpochMs = 1_000L,
                )
            ),
            hasLoadedThreadList = true,
            isLoadingThreadList = true,
        )

        val appState = state.toAppUiState(isSettingsVisible = false, nowEpochMs = 2_000L)
        val route = appState.destination as AndrodexDestinationUiState.Home

        assertFalse(route.state.threadList.isLoading)
        assertTrue(route.state.threadList.showLoadingOverlay)
        assertEquals(null, route.state.threadList.emptyState)
    }

    @Test
    fun threadRoute_buildsTimelineComposerAndSettingsOptions() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            selectedThreadId = "thread-9",
            selectedThreadTitle = "Conversation",
            composerText = "Please continue",
            composerPlanModeByThread = setOf("thread-9"),
            protectedRunningFallbackThreadIds = setOf("thread-9"),
            queuedDraftStateByThread = mapOf(
                "thread-9" to ThreadQueuedDraftState(
                    drafts = listOf(
                        QueuedTurnDraft(
                            id = "queued-1",
                            text = "Run the tests after this finishes",
                            createdAtEpochMs = 2L,
                        )
                    ),
                    pauseState = QueuePauseState.PAUSED,
                    pauseMessage = "Queue paused after a send failure.",
                )
            ),
            messages = listOf(
                ConversationMessage(
                    id = "msg-1",
                    threadId = "thread-9",
                    role = ConversationRole.ASSISTANT,
                    kind = ConversationKind.CHAT,
                    text = "Sure.",
                    createdAtEpochMs = 1L,
                )
            ),
            availableModels = listOf(
                ModelOption(
                    id = "gpt-5.4",
                    model = "gpt-5.4",
                    displayName = "GPT-5.4",
                    description = "Primary",
                    isDefault = true,
                    supportedReasoningEfforts = listOf(
                        ReasoningEffortOption("medium", "Balanced"),
                        ReasoningEffortOption("high", "Deep"),
                    ),
                    defaultReasoningEffort = "medium",
                )
            ),
            selectedModelId = "gpt-5.4",
            selectedReasoningEffort = "high",
            collaborationModes = setOf(CollaborationModeKind.PLAN),
        )

        val appState = state.toAppUiState(isSettingsVisible = true)
        val route = appState.destination as AndrodexDestinationUiState.Thread

        assertEquals("thread-9", route.state.threadId)
        assertEquals(ComposerSubmitMode.QUEUE, route.state.composer.submitMode)
        assertTrue(route.state.composer.isPlanModeEnabled)
        assertTrue(route.state.composer.planModeEnabled)
        assertTrue(route.state.composer.submitEnabled)
        assertTrue(route.state.composer.showStop)
        assertEquals(1, route.state.composer.queuedCount)
        assertTrue(route.state.composer.isQueuePaused)
        assertEquals("Please continue", route.state.composer.text)
        assertEquals(1, route.state.queuedDrafts.size)
        assertEquals("Queue paused after a send failure.", route.state.queuePauseMessage)
        assertFalse(route.state.canRestoreQueuedDrafts)
        assertTrue(route.state.canResumeQueue)
        assertTrue(appState.settings.isVisible)
        assertTrue(appState.settings.modelOptions.any { it.value == "gpt-5.4" && it.selected })
        assertTrue(appState.settings.reasoningOptions.any { it.value == "high" && it.selected })
    }

    @Test
    fun threadRoute_disablesPlanModeWhenRuntimeHasNotAdvertisedSupport() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            selectedThreadId = "thread-9",
            selectedThreadTitle = "Conversation",
            composerPlanModeByThread = setOf("thread-9"),
        )

        val appState = state.toAppUiState(isSettingsVisible = false)
        val route = appState.destination as AndrodexDestinationUiState.Thread

        assertFalse(route.state.composer.isPlanModeEnabled)
        assertFalse(route.state.composer.isPlanModeSupported)
        assertFalse(route.state.composer.planModeEnabled)
        assertEquals("Plan unavailable", route.state.composer.planModeLabel)
        assertFalse(route.state.runtime.supportsPlanMode)
        assertTrue(route.state.runtime.collaborationSummary.contains("not advertised", ignoreCase = true))
    }

    @Test
    fun threadRoute_clearsQueuedDraftPlanModeWhenRuntimeSupportDisappears() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            selectedThreadId = "thread-9",
            selectedThreadTitle = "Conversation",
            queuedDraftStateByThread = mapOf(
                "thread-9" to ThreadQueuedDraftState(
                    drafts = listOf(
                        QueuedTurnDraft(
                            id = "queued-1",
                            text = "Plan the cleanup",
                            createdAtEpochMs = 1L,
                            collaborationMode = CollaborationModeKind.PLAN,
                        )
                    )
                )
            ),
        )

        val appState = state.toAppUiState(isSettingsVisible = false)
        val route = appState.destination as AndrodexDestinationUiState.Thread

        assertEquals(1, route.state.queuedDrafts.size)
        assertEquals(null, route.state.queuedDrafts.single().collaborationMode)
        assertFalse(route.state.composer.isPlanModeSupported)
    }

    @Test
    fun threadRoute_allowsSendForSubagentsOnlyComposerState() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            selectedThreadId = "thread-9",
            selectedThreadTitle = "Conversation",
            composerSubagentsByThread = setOf("thread-9"),
        )

        val appState = state.toAppUiState(isSettingsVisible = false)
        val route = appState.destination as AndrodexDestinationUiState.Thread

        assertTrue(route.state.composer.isSubagentsEnabled)
        assertTrue(route.state.composer.submitEnabled)
    }

    @Test
    fun threadRoute_allowsImageOnlySendWhenAttachmentsAreReady() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            selectedThreadId = "thread-9",
            selectedThreadTitle = "Conversation",
            composerAttachmentsByThread = mapOf(
                "thread-9" to listOf(
                    ComposerImageAttachment(
                        id = "attachment-1",
                        state = ComposerImageAttachmentState.Ready(
                            ImageAttachment(
                                id = "image-1",
                                thumbnailBase64Jpeg = "thumb",
                                payloadDataUrl = "data:image/jpeg;base64,AAAA",
                            )
                        ),
                    )
                )
            ),
        )

        val appState = state.toAppUiState(isSettingsVisible = false)
        val route = appState.destination as AndrodexDestinationUiState.Thread

        assertTrue(route.state.composer.submitEnabled)
        assertEquals(1, route.state.composer.attachments.size)
        assertEquals(3, route.state.composer.remainingAttachmentSlots)
    }

    @Test
    fun threadRoute_blocksSendAndRestoreWhenAttachmentStateIsLoadingOrFailed() {
        val loadingState = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            selectedThreadId = "thread-9",
            selectedThreadTitle = "Conversation",
            queuedDraftStateByThread = mapOf(
                "thread-9" to ThreadQueuedDraftState(
                    drafts = listOf(
                        QueuedTurnDraft(
                            id = "queued-1",
                            text = "Restore me",
                            createdAtEpochMs = 1L,
                        )
                    )
                )
            ),
            composerAttachmentsByThread = mapOf(
                "thread-9" to listOf(
                    ComposerImageAttachment(
                        id = "attachment-1",
                        state = ComposerImageAttachmentState.Loading,
                    ),
                    ComposerImageAttachment(
                        id = "attachment-2",
                        state = ComposerImageAttachmentState.Failed("Couldn't load"),
                    ),
                )
            ),
        )

        val appState = loadingState.toAppUiState(isSettingsVisible = false)
        val route = appState.destination as AndrodexDestinationUiState.Thread

        assertFalse(route.state.composer.submitEnabled)
        assertTrue(route.state.composer.hasBlockingAttachmentState)
        assertFalse(route.state.canRestoreQueuedDrafts)
    }

    @Test
    fun threadRoute_buildsRuntimeAndForkUiFromOverrides() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            activeWorkspacePath = "D:\\Projects\\Alt",
            selectedThreadId = "thread-9",
            selectedThreadTitle = "Fork me",
            threads = listOf(
                ThreadSummary(
                    id = "thread-9",
                    title = "Fork me",
                    preview = "Explore a new path",
                    cwd = "C:\\Projects\\Androdex",
                    createdAtEpochMs = null,
                    updatedAtEpochMs = null,
                    forkedFromThreadId = "thread-1",
                )
            ),
            messages = listOf(
                ConversationMessage(
                    id = "msg-1",
                    threadId = "thread-9",
                    role = ConversationRole.USER,
                    kind = ConversationKind.CHAT,
                    text = "Explore a new path",
                    createdAtEpochMs = 1L,
                )
            ),
            availableModels = listOf(
                ModelOption(
                    id = "gpt-5.4",
                    model = "gpt-5.4",
                    displayName = "GPT-5.4",
                    description = "Primary",
                    isDefault = true,
                    supportedReasoningEfforts = listOf(
                        ReasoningEffortOption("medium", "Balanced"),
                        ReasoningEffortOption("high", "Deep"),
                    ),
                    defaultReasoningEffort = "medium",
                )
            ),
            selectedModelId = "gpt-5.4",
            selectedReasoningEffort = "medium",
            selectedAccessMode = AccessMode.FULL_ACCESS,
            selectedServiceTier = ServiceTier.FAST,
            threadRuntimeOverridesByThread = mapOf(
                "thread-9" to ThreadRuntimeOverride(
                    reasoningEffort = "high",
                    serviceTierRawValue = "fast",
                    overridesReasoning = true,
                    overridesServiceTier = true,
                )
            ),
        )

        val appState = state.toAppUiState(isSettingsVisible = true)
        val route = appState.destination as AndrodexDestinationUiState.Thread

        assertTrue(route.state.isForkedThread)
        assertTrue(route.state.runtime.hasOverrides)
        assertEquals("Full Access", route.state.runtime.accessModeLabel)
        assertTrue(route.state.runtime.reasoningOptions.any { it.value == "high" && it.selected })
        assertTrue(route.state.runtime.serviceTierOptions.any { it.value == "fast" && it.selected })
        assertEquals("Runtime: High • Fast", route.state.composer.runtimeButtonLabel)
        assertTrue(route.state.compact.isEnabled)
        assertTrue(route.state.rollback.isEnabled)
        assertTrue(route.state.backgroundTerminals.isEnabled)
        assertEquals(2, route.state.fork.targets.size)
        assertEquals("Current project", route.state.fork.targets.first().title)
        assertEquals("Active workspace", route.state.fork.targets.last().title)
        assertTrue(appState.settings.accessModeOptions.any { it.value == "full-access" && it.selected })
        assertTrue(appState.settings.serviceTierOptions.any { it.value == "fast" && it.selected })
        assertTrue(appState.settings.about.projectUrl.contains("github.com"))
        assertTrue(appState.settings.bridgeStatus.serviceTierMessage.contains("available", ignoreCase = true))
    }

    @Test
    fun threadRoute_gitAvailabilityMessage_explainsDisconnectedBusyAndRunningStates() {
        val disconnectedRoute = gitThreadState(connectionStatus = ConnectionStatus.DISCONNECTED)
            .toAppUiState(isSettingsVisible = false)
            .destination as AndrodexDestinationUiState.Thread
        assertEquals(
            "Reconnect to the host to use Git actions.",
            disconnectedRoute.state.git.availabilityMessage,
        )

        val busyRoute = gitThreadState(runningGitAction = GitActionKind.PUSH)
            .toAppUiState(isSettingsVisible = false)
            .destination as AndrodexDestinationUiState.Thread
        assertEquals("Git action in progress.", busyRoute.state.git.availabilityMessage)

        val refreshingRoute = gitThreadState(gitRefreshInFlight = true)
            .toAppUiState(isSettingsVisible = false)
            .destination as AndrodexDestinationUiState.Thread
        assertEquals("Refreshing Git status...", refreshingRoute.state.git.availabilityMessage)
        assertFalse(refreshingRoute.state.git.canRunActions)

        val runningRoute = gitThreadState(runningThread = true)
            .toAppUiState(isSettingsVisible = false)
            .destination as AndrodexDestinationUiState.Thread
        assertEquals(
            "Git actions pause while this thread is running.",
            runningRoute.state.git.availabilityMessage,
        )
    }

    @Test
    fun threadRoute_gitDiffStaysUnloadedUntilRequested() {
        val route = gitThreadState()
            .toAppUiState(isSettingsVisible = false)
            .destination as AndrodexDestinationUiState.Thread

        assertTrue(route.state.git.hasWorkingDirectory)
        assertEquals(null, route.state.git.diffPatch)
        assertEquals("main", route.state.git.status?.currentBranch)
    }

    @Test
    fun threadRoute_disablesMaintenanceActionsWhenHostSupportIsMissing() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            selectedThreadId = "thread-9",
            selectedThreadTitle = "Conversation",
            supportsThreadCompaction = false,
            supportsThreadRollback = false,
            supportsBackgroundTerminalCleanup = false,
            messages = listOf(
                ConversationMessage(
                    id = "msg-1",
                    threadId = "thread-9",
                    role = ConversationRole.ASSISTANT,
                    kind = ConversationKind.CHAT,
                    text = "Done.",
                    createdAtEpochMs = 1L,
                )
            ),
        )

        val appState = state.toAppUiState(isSettingsVisible = false)
        val route = appState.destination as AndrodexDestinationUiState.Thread

        assertFalse(route.state.compact.isEnabled)
        assertTrue(route.state.compact.availabilityMessage!!.contains("compaction", ignoreCase = true))
        assertFalse(route.state.rollback.isEnabled)
        assertTrue(route.state.rollback.availabilityMessage!!.contains("rollback", ignoreCase = true))
        assertFalse(route.state.backgroundTerminals.isEnabled)
        assertTrue(route.state.backgroundTerminals.availabilityMessage!!.contains("background terminal", ignoreCase = true))
    }

    @Test
    fun threadRoute_surfacesPendingStructuredToolInputCards() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            selectedThreadId = "thread-9",
            selectedThreadTitle = "Conversation",
            pendingToolInputsByThread = mapOf(
                "thread-9" to mapOf(
                    "request-1" to ToolUserInputRequest(
                        idValue = "request-1",
                        method = "item/tool/requestUserInput",
                        threadId = "thread-9",
                        turnId = "turn-1",
                        itemId = "item-1",
                        title = "Pick a branch",
                        message = "Select which branch to inspect.",
                        questions = listOf(
                            ToolUserInputQuestion(
                                id = "branch",
                                header = "Branch",
                                question = "Which branch should we inspect?",
                                options = listOf(
                                    ToolUserInputOption("main", "Use the default branch."),
                                    ToolUserInputOption("release", "Use the release branch."),
                                ),
                                isOther = true,
                            )
                        ),
                        rawPayload = "{}",
                    )
                )
            ),
            toolInputAnswersByRequest = mapOf(
                "request-1" to mapOf("branch" to "release")
            ),
        )

        val appState = state.toAppUiState(isSettingsVisible = false)
        val route = appState.destination as AndrodexDestinationUiState.Thread

        assertEquals(1, route.state.pendingToolInputs.size)
        val prompt = route.state.pendingToolInputs.single()
        assertEquals("Pick a branch", prompt.title)
        assertTrue(prompt.submitEnabled)
        assertEquals("release", prompt.questions.single().answer)
        assertTrue(prompt.questions.single().options.any { it.label == "release" && it.isSelected })
        assertTrue(prompt.questions.single().allowsCustomAnswer)
    }

    @Test
    fun threadRoute_disablesComposerAndRuntimeWhenTurnStartIsUnsupported() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            selectedThreadId = "thread-9",
            selectedThreadTitle = "Conversation",
            composerText = "Please continue",
            threads = listOf(
                ThreadSummary(
                    id = "thread-9",
                    title = "Conversation",
                    preview = null,
                    cwd = "/workspace/app",
                    createdAtEpochMs = null,
                    updatedAtEpochMs = null,
                    threadCapabilities = ThreadCapabilities(
                        readOnly = true,
                        turnStart = ThreadCapabilityFlag(
                            supported = false,
                            reason = "Open this thread on the host Mac to continue it.",
                        ),
                    ),
                )
            ),
        )

        val route = state.toAppUiState(isSettingsVisible = false).destination as AndrodexDestinationUiState.Thread

        assertFalse(route.state.composer.inputEnabled)
        assertFalse(route.state.composer.submitEnabled)
        assertFalse(route.state.composer.planModeEnabled)
        assertFalse(route.state.composer.subagentsEnabled)
        assertFalse(route.state.composer.runtimeButtonEnabled)
        assertEquals(
            "Open this thread on the host Mac to continue it.",
            route.state.composer.availabilityMessage,
        )
    }

    @Test
    fun threadRoute_disablesStopAndToolInputSubmitWhenCapabilitiesBlockThem() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            selectedThreadId = "thread-9",
            selectedThreadTitle = "Conversation",
            runningThreadIds = setOf("thread-9"),
            threads = listOf(
                ThreadSummary(
                    id = "thread-9",
                    title = "Conversation",
                    preview = null,
                    cwd = "/workspace/app",
                    createdAtEpochMs = null,
                    updatedAtEpochMs = null,
                    threadCapabilities = ThreadCapabilities(
                        readOnly = true,
                        turnInterrupt = ThreadCapabilityFlag(
                            supported = false,
                            reason = "Stop this run from the desktop session.",
                        ),
                        toolInputResponses = ThreadCapabilityFlag(
                            supported = false,
                            reason = "Answer these questions from the desktop session.",
                        ),
                    ),
                )
            ),
            pendingToolInputsByThread = mapOf(
                "thread-9" to mapOf(
                    "request-1" to ToolUserInputRequest(
                        idValue = "request-1",
                        method = "item/tool/requestUserInput",
                        threadId = "thread-9",
                        turnId = "turn-1",
                        itemId = "item-1",
                        title = "Pick a branch",
                        message = "Select which branch to inspect.",
                        questions = listOf(
                            ToolUserInputQuestion(
                                id = "branch",
                                header = "Branch",
                                question = "Which branch should we inspect?",
                                options = listOf(
                                    ToolUserInputOption("main", "Use the default branch."),
                                ),
                                isOther = true,
                            )
                        ),
                        rawPayload = "{}",
                    )
                )
            ),
            toolInputAnswersByRequest = mapOf(
                "request-1" to mapOf("branch" to "main")
            ),
        )

        val route = state.toAppUiState(isSettingsVisible = false).destination as AndrodexDestinationUiState.Thread

        assertTrue(route.state.composer.showStop)
        assertFalse(route.state.composer.stopEnabled)
        assertEquals(
            "Stop this run from the desktop session.",
            route.state.composer.availabilityMessage,
        )
        assertFalse(route.state.pendingToolInputs.single().submitEnabled)
        assertEquals(
            "Answer these questions from the desktop session.",
            route.state.pendingToolInputs.single().availabilityMessage,
        )
    }

    private fun gitThreadState(
        connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTED,
        runningThread: Boolean = false,
        runningGitAction: GitActionKind? = null,
        gitRefreshInFlight: Boolean = false,
    ): AndrodexUiState {
        val workingDirectory = "C:\\Projects\\Androdex"
        return AndrodexUiState(
            connectionStatus = connectionStatus,
            selectedThreadId = "thread-9",
            selectedThreadTitle = "Conversation",
            threads = listOf(
                ThreadSummary(
                    id = "thread-9",
                    title = "Conversation",
                    preview = null,
                    cwd = workingDirectory,
                    createdAtEpochMs = null,
                    updatedAtEpochMs = null,
                )
            ),
            runningThreadIds = if (runningThread) setOf("thread-9") else emptySet(),
            gitStateByThread = mapOf(
                "thread-9" to ThreadGitState(
                    status = GitRepoSyncResult(
                        repoRoot = workingDirectory,
                        currentBranch = "main",
                        trackingBranch = "origin/main",
                        isDirty = true,
                        aheadCount = 1,
                        behindCount = 0,
                        localOnlyCommitCount = 1,
                        state = "dirty",
                        canPush = true,
                        isPublishedToRemote = true,
                        files = emptyList(),
                        repoDiffTotals = null,
                    ),
                    isRefreshing = gitRefreshInFlight,
                    refreshWorkingDirectory = if (gitRefreshInFlight) workingDirectory else null,
                )
            ),
            runningGitActionByThread = runningGitAction?.let { mapOf("thread-9" to it) } ?: emptyMap(),
        )
    }
}
