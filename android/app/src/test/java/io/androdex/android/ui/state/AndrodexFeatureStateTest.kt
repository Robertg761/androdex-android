package io.androdex.android.ui.state

import io.androdex.android.AndrodexUiState
import io.androdex.android.model.AccessMode
import io.androdex.android.model.ComposerImageAttachment
import io.androdex.android.model.ComposerImageAttachmentState
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.HostAccountSnapshot
import io.androdex.android.model.HostAccountStatus
import io.androdex.android.model.ImageAttachment
import io.androdex.android.model.ModelOption
import io.androdex.android.model.QueuePauseState
import io.androdex.android.model.QueuedTurnDraft
import io.androdex.android.model.ReasoningEffortOption
import io.androdex.android.model.ServiceTier
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ThreadQueuedDraftState
import io.androdex.android.model.ThreadRuntimeOverride
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
    fun pairingRoute_usesReconnectPresentationForSavedPairingRecovery() {
        val state = AndrodexUiState(
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
        assertEquals("host@example.com • pro", route.state.hostAccount?.detail)
        assertEquals("Retrying saved pair", route.state.trustedPair?.statusLabel)
        assertEquals("relay.example.com", route.state.trustedPair?.relayLabel)
        assertTrue(route.state.compatibilityMessage.isNullOrBlank())
    }

    @Test
    fun homeRoute_formatsThreadListAndProjectPickerState() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            trustedPairSnapshot = TrustedPairSnapshot(
                deviceId = "host-1234",
                relayUrl = "wss://relay.example.com/socket",
                fingerprint = "ABCD1234EFGH5678",
                lastPairedAtEpochMs = 1_000L,
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
        assertEquals(ThreadRunBadgeUiState.RUNNING, route.state.threadList.threads.single().runState)
        assertEquals("Bridge Ready", route.state.bridgeStatus.title)
        assertNotNull(route.state.trustedPair)
        assertEquals("Connected pair", route.state.trustedPair?.statusLabel)
        assertNotNull(route.state.projectPicker)
        assertTrue(route.state.projectPicker?.isBrowsing == true)
        assertEquals(
            WorkspaceRowAction.ACTIVATE,
            route.state.projectPicker?.browserEntries?.single()?.action,
        )
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
        assertEquals(2, route.state.fork.targets.size)
        assertEquals("Current project", route.state.fork.targets.first().title)
        assertEquals("Active workspace", route.state.fork.targets.last().title)
        assertTrue(appState.settings.accessModeOptions.any { it.value == "full-access" && it.selected })
        assertTrue(appState.settings.serviceTierOptions.any { it.value == "fast" && it.selected })
        assertTrue(appState.settings.about.projectUrl.contains("github.com"))
        assertTrue(appState.settings.bridgeStatus.serviceTierMessage.contains("available", ignoreCase = true))
    }
}
