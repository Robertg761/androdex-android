package io.androdex.android.ui.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androdex.android.ui.state.ThreadListItemUiState
import io.androdex.android.ui.state.ThreadListPaneUiState
import io.androdex.android.ui.state.ThreadRunBadgeUiState

@Composable
internal fun ThreadListPane(
    state: ThreadListPaneUiState,
    onOpenThread: (String) -> Unit,
    onOpenProjects: () -> Unit,
) {
    state.emptyState?.let { emptyState ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = emptyState.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = emptyState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (emptyState.showChooseProjectAction) {
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(onClick = onOpenProjects) {
                        Text("Choose Project")
                    }
                }
            }
        }
        return
    }

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
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun ThreadCard(
    thread: ThreadListItemUiState,
    onOpenThread: (String) -> Unit,
) {
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
                thread.runState?.let { runState ->
                    ThreadRunBadge(
                        state = runState,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                thread.updatedLabel?.let {
                    Text(
                        text = it,
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                if (thread.isForked) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = "Forked",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadRunBadge(
    state: ThreadRunBadgeUiState,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (state) {
        ThreadRunBadgeUiState.RUNNING -> "Running" to Color(0xFFF59E0B)
        ThreadRunBadgeUiState.READY -> "Ready" to Color(0xFF10B981)
        ThreadRunBadgeUiState.FAILED -> "Failed" to Color(0xFFEF4444)
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
