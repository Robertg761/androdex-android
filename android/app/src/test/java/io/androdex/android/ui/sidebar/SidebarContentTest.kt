package io.androdex.android.ui.sidebar

import io.androdex.android.ui.state.ThreadListItemUiState
import io.androdex.android.ui.state.ThreadListPaneUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarContentTest {
    @Test
    fun buildSidebarProjectGroups_keepsDuplicateFolderNamesSeparate() {
        val threadList = ThreadListPaneUiState(
            activeWorkspacePath = null,
            threads = listOf(
                sidebarThread(
                    id = "thread-1",
                    title = "Alpha",
                    projectName = "AppA",
                    projectPath = "/Users/robert/work/client-a/AppA",
                ),
                sidebarThread(
                    id = "thread-2",
                    title = "Beta",
                    projectName = "AppA",
                    projectPath = "/Users/robert/work/client-b/AppA",
                ),
            ),
            isLoading = false,
            showLoadingOverlay = false,
            emptyState = null,
        )

        val groups = buildSidebarProjectGroups(threadList, searchText = "")

        assertEquals(2, groups.size)
        assertEquals(listOf("AppA", "AppA"), groups.map { it.displayName })
        assertEquals(
            listOf("/Users/robert/work/client-a", "/Users/robert/work/client-b"),
            groups.map { it.disambiguationLabel },
        )
    }

    @Test
    fun buildSidebarProjectGroups_addsActiveWorkspaceHeaderWhenNoThreadsExistYet() {
        val threadList = ThreadListPaneUiState(
            activeWorkspacePath = "/Users/robert/Documents/Projects/androdex",
            threads = emptyList(),
            isLoading = false,
            showLoadingOverlay = false,
            emptyState = null,
        )

        val groups = buildSidebarProjectGroups(threadList, searchText = "")

        assertEquals(1, groups.size)
        assertEquals("androdex", groups.single().displayName)
        assertEquals("/Users/robert/Documents/Projects/androdex", groups.single().projectPath)
        assertEquals(0, groups.single().threadCount)
        assertTrue(groups.single().canCreateThread)
    }

    @Test
    fun buildSidebarProjectGroups_keepsNoProjectBucketNonCreatable() {
        val threadList = ThreadListPaneUiState(
            activeWorkspacePath = null,
            threads = listOf(
                sidebarThread(
                    id = "thread-1",
                    title = "Loose chat",
                    projectName = "No Project",
                    projectPath = null,
                )
            ),
            isLoading = false,
            showLoadingOverlay = false,
            emptyState = null,
        )

        val groups = buildSidebarProjectGroups(threadList, searchText = "")

        assertEquals(1, groups.size)
        assertEquals("No Project", groups.single().displayName)
        assertNull(groups.single().projectPath)
        assertFalse(groups.single().canCreateThread)
        assertNull(groups.single().disambiguationLabel)
    }

    @Test
    fun buildSidebarProjectGroups_keepsUnavailableWorkspaceGroupsNonCreatable() {
        val threadList = ThreadListPaneUiState(
            activeWorkspacePath = null,
            threads = listOf(
                sidebarThread(
                    id = "thread-1",
                    title = "Fallback needed",
                    projectName = "Project C",
                    projectPath = "/tmp/project-c",
                    projectPathAvailable = false,
                )
            ),
            isLoading = false,
            showLoadingOverlay = false,
            emptyState = null,
        )

        val groups = buildSidebarProjectGroups(threadList, searchText = "")

        assertEquals(1, groups.size)
        assertEquals("/tmp/project-c", groups.single().projectPath)
        assertFalse(groups.single().canCreateThread)
    }

    @Test
    fun buildSidebarProjectGroups_keepsProjectRootFallbackGroupsCreatable() {
        val threadList = ThreadListPaneUiState(
            activeWorkspacePath = null,
            threads = listOf(
                sidebarThread(
                    id = "thread-1",
                    title = "Fallback thread",
                    projectName = "Project B",
                    projectPath = "/tmp/project-b",
                    projectPathAvailable = true,
                )
            ),
            isLoading = false,
            showLoadingOverlay = false,
            emptyState = null,
        )

        val groups = buildSidebarProjectGroups(threadList, searchText = "")

        assertEquals(1, groups.size)
        assertEquals("/tmp/project-b", groups.single().projectPath)
        assertTrue(groups.single().canCreateThread)
    }

    @Test
    fun buildSidebarProjectGroups_disablesCreateAcrossGroupsWhenRuntimeIsReadOnly() {
        val threadList = ThreadListPaneUiState(
            activeWorkspacePath = "/tmp/project-a",
            threads = listOf(
                sidebarThread(
                    id = "thread-1",
                    title = "Existing T3 chat",
                    projectName = "Project A",
                    projectPath = "/tmp/project-a",
                    projectPathAvailable = true,
                )
            ),
            isLoading = false,
            showLoadingOverlay = false,
            emptyState = null,
            createThreadSupported = false,
            createThreadBlockedReason = "Read-only runtime",
        )

        val groups = buildSidebarProjectGroups(threadList, searchText = "")

        assertEquals(1, groups.size)
        assertFalse(groups.single().canCreateThread)
    }
}

private fun sidebarThread(
    id: String,
    title: String,
    projectName: String,
    projectPath: String?,
    projectPathAvailable: Boolean = projectPath != null,
): ThreadListItemUiState {
    return ThreadListItemUiState(
        id = id,
        title = title,
        preview = null,
        projectName = projectName,
        projectPath = projectPath,
        projectPathAvailable = projectPathAvailable,
        updatedLabel = null,
        runState = null,
        isForked = false,
    )
}
