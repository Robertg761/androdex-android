package io.androdex.android.ui.sidebar

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import io.androdex.android.ui.state.ThreadListItemUiState
import io.androdex.android.ui.state.ThreadListPaneUiState
import io.androdex.android.ui.theme.AndrodexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SidebarContentUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun projectHeaderTap_expandsWithoutCreatingThread() {
        val createdProjects = mutableListOf<String>()

        composeRule.setContent {
            AndrodexTheme {
                var expandedProjects by remember { mutableStateOf(setOf<String>()) }
                SidebarThreadCollection(
                    threadList = sidebarThreadList(),
                    searchText = "",
                    selectedThreadId = null,
                    expandedProjects = expandedProjects,
                    onToggleProject = { key ->
                        expandedProjects = if (key in expandedProjects) {
                            expandedProjects - key
                        } else {
                            expandedProjects + key
                        }
                    },
                    onCreateThread = { createdProjects += it },
                    onOpenThread = { _ -> },
                )
            }
        }

        assertEquals(0, composeRule.onAllNodesWithText("Alpha thread").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("Expand AppA").performClick()
        assertEquals(1, composeRule.onAllNodesWithText("Alpha thread").fetchSemanticsNodes().size)
        assertEquals(emptyList<String>(), createdProjects)
    }

    @Test
    fun projectCreateButton_createsThreadWithoutExpandingProject() {
        val createdProjects = mutableListOf<String>()

        composeRule.setContent {
            AndrodexTheme {
                var expandedProjects by remember { mutableStateOf(setOf<String>()) }
                SidebarThreadCollection(
                    threadList = sidebarThreadList(),
                    searchText = "",
                    selectedThreadId = null,
                    expandedProjects = expandedProjects,
                    onToggleProject = { key ->
                        expandedProjects = if (key in expandedProjects) {
                            expandedProjects - key
                        } else {
                            expandedProjects + key
                        }
                    },
                    onCreateThread = { createdProjects += it },
                    onOpenThread = { _ -> },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Create chat in AppA").performClick()
        assertEquals(0, composeRule.onAllNodesWithText("Alpha thread").fetchSemanticsNodes().size)
        assertEquals(listOf("/tmp/client/AppA"), createdProjects)
    }

    @Test
    fun selectedThread_autoExpandsItsProjectGroup() {
        composeRule.setContent {
            AndrodexTheme {
                SidebarThreadCollection(
                    threadList = sidebarThreadList(),
                    searchText = "",
                    selectedThreadId = "thread-1",
                    expandedProjects = emptySet(),
                    onToggleProject = { _ -> },
                    onCreateThread = { _ -> },
                    onOpenThread = { _ -> },
                )
            }
        }

        assertEquals(1, composeRule.onAllNodesWithText("Alpha thread").fetchSemanticsNodes().size)
    }
}

private fun sidebarThreadList(): ThreadListPaneUiState {
    return ThreadListPaneUiState(
        activeWorkspacePath = "/tmp/client/AppA",
        threads = listOf(
            ThreadListItemUiState(
                id = "thread-1",
                title = "Alpha thread",
                preview = "Preview",
                projectName = "AppA",
                projectPath = "/tmp/client/AppA",
                updatedLabel = "1m ago",
                runState = null,
                isForked = false,
            )
        ),
        isLoading = false,
        showLoadingOverlay = false,
        emptyState = null,
    )
}
