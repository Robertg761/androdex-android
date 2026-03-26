package io.androdex.android.ui.state

import io.androdex.android.AndrodexUiState
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.ModelOption
import io.androdex.android.model.ReasoningEffortOption
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.WorkspaceDirectoryEntry
import io.androdex.android.model.WorkspacePathSummary
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
            connectionStatus = ConnectionStatus.RETRYING_SAVED_PAIRING,
            connectionDetail = "Waiting for host",
        )

        val appState = state.toAppUiState(isSettingsVisible = false)
        val route = appState.destination as AndrodexDestinationUiState.Pairing

        assertEquals("Retrying Saved Pairing...", route.state.reconnectButtonLabel)
        assertFalse(route.state.reconnectEnabled)
        assertEquals(ConnectionStatus.RETRYING_SAVED_PAIRING, route.state.connection.status)
    }

    @Test
    fun homeRoute_formatsThreadListAndProjectPickerState() {
        val state = AndrodexUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
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
            protectedRunningFallbackThreadIds = setOf("thread-9"),
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
        assertEquals(ComposerSubmitMode.STEER, route.state.composer.submitMode)
        assertTrue(route.state.composer.submitEnabled)
        assertTrue(route.state.composer.showStop)
        assertEquals("Please continue", route.state.composer.text)
        assertTrue(appState.settings.isVisible)
        assertTrue(appState.settings.modelOptions.any { it.value == "gpt-5.4" && it.selected })
        assertTrue(appState.settings.reasoningOptions.any { it.value == "high" && it.selected })
    }
}
