package io.androdex.android

import io.androdex.android.ui.state.AndrodexDestinationUiState
import io.androdex.android.ui.state.toAppUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AndrodexAppTransitionTest {
    @Test
    fun threadBackAction_opensSidebarWhenDrawerIsClosed() {
        assertEquals(
            ThreadBackAction.OPEN_SIDEBAR,
            threadBackAction(isDrawerOpen = false),
        )
    }

    @Test
    fun threadBackAction_closesSidebarWhenDrawerIsOpen() {
        assertEquals(
            ThreadBackAction.CLOSE_SIDEBAR,
            threadBackAction(isDrawerOpen = true),
        )
    }

    @Test
    fun connectedShellTransitionKey_staysStableForComposerEditsInSameThread() {
        val initialDestination = buildThreadDestination(
            threadId = "thread-1",
            composerText = "",
        )
        val updatedDestination = buildThreadDestination(
            threadId = "thread-1",
            composerText = "hello",
        )

        assertEquals(
            connectedShellTransitionKey(initialDestination),
            connectedShellTransitionKey(updatedDestination),
        )
    }

    @Test
    fun connectedShellTransitionKey_changesWhenNavigatingToDifferentThread() {
        val initialDestination = buildThreadDestination(
            threadId = "thread-1",
            composerText = "draft",
        )
        val updatedDestination = buildThreadDestination(
            threadId = "thread-2",
            composerText = "draft",
        )

        assertNotEquals(
            connectedShellTransitionKey(initialDestination),
            connectedShellTransitionKey(updatedDestination),
        )
    }

    private fun buildThreadDestination(
        threadId: String,
        composerText: String,
    ): AndrodexDestinationUiState {
        val appState = AndrodexUiState(
            selectedThreadId = threadId,
            composerText = composerText,
        ).toAppUiState(isSettingsVisible = false)
        return appState.destination
    }
}
