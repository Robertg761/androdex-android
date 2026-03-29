package io.androdex.android.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.androdex.android.ui.state.ThreadListPaneUiState

@Composable
internal fun ThreadListPane(
    state: ThreadListPaneUiState,
    onOpenThread: (String) -> Unit,
    onOpenProjects: () -> Unit,
) {
    var expandedProjects by rememberSaveable { mutableStateOf(setOf<String>()) }

    SidebarThreadCollection(
        threadList = state,
        searchText = "",
        selectedThreadId = null,
        expandedProjects = expandedProjects,
        onToggleProject = { project ->
            expandedProjects = if (project in expandedProjects) {
                expandedProjects - project
            } else {
                expandedProjects + project
            }
        },
        onOpenThread = onOpenThread,
        modifier = Modifier,
        expandAllProjects = true,
    )
}
