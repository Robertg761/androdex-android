package io.androdex.android.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androdex.android.R
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.ui.shared.BusyIndicator
import io.androdex.android.ui.shared.connectionStatusDotColor
import io.androdex.android.ui.state.ConnectionBannerUiState
import io.androdex.android.ui.state.HomeScreenUiState
import io.androdex.android.ui.state.TrustedPairUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    state: HomeScreenUiState,
    onOpenSidebar: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProjects: () -> Unit,
    onCloseProjects: () -> Unit,
    onRefreshProjects: () -> Unit,
    onBrowseWorkspace: (String?) -> Unit,
    onWorkspaceBrowserPathChanged: (String) -> Unit,
    onActivateWorkspace: (String) -> Unit,
) {
    state.projectPicker?.let { projectPicker ->
        ProjectPickerSheet(
            state = projectPicker,
            onDismiss = onCloseProjects,
            onRefresh = onRefreshProjects,
            onBrowse = onBrowseWorkspace,
            onBrowserPathChanged = onWorkspaceBrowserPathChanged,
            onActivateWorkspace = onActivateWorkspace,
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onOpenSidebar) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open sidebar",
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenProjects) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "New chat",
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                    windowInsets = WindowInsets(0),
                )
                BusyIndicator(state = state.busy)
            }
        },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        HomeEmptyState(
            connection = state.connection,
            trustedPair = state.trustedPair,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

// ── HomeEmptyStateView — matches remodex HomeEmptyStateView ────────────────────

@Composable
private fun HomeEmptyState(
    connection: ConnectionBannerUiState,
    trustedPair: TrustedPairUiState?,
    modifier: Modifier = Modifier,
) {
    val isConnecting = connection.status == ConnectionStatus.CONNECTING ||
        connection.status == ConnectionStatus.HANDSHAKING ||
        connection.status == ConnectionStatus.RETRYING_SAVED_PAIRING

    val dotColor = connectionStatusDotColor(connection.status)
    val statusText = when (connection.status) {
        ConnectionStatus.CONNECTED -> "Connected"
        ConnectionStatus.CONNECTING -> "Connecting..."
        ConnectionStatus.HANDSHAKING -> "Handshaking..."
        ConnectionStatus.RETRYING_SAVED_PAIRING -> "Waiting for host..."
        ConnectionStatus.RECONNECT_REQUIRED -> "Reconnect required"
        ConnectionStatus.UPDATE_REQUIRED -> "Update required"
        ConnectionStatus.DISCONNECTED -> "Offline"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            // App logo 88×88
            Surface(
                modifier = Modifier.size(88.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "Androdex",
                    modifier = Modifier.size(88.dp),
                )
            }

            // Pulsing status dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        dotColor.copy(alpha = if (isConnecting) pulseAlpha else 1f),
                    ),
            )

            // Status text
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // TrustedPairSummaryView (Mac pair info)
            trustedPair?.let { pair ->
                TrustedPairSummaryCard(
                    pair = pair,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
            }

            // E2EE label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = "End-to-end encrypted",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

// ── TrustedPairSummaryCard — matches remodex TrustedPairSummaryView ────────────

@Composable
private fun TrustedPairSummaryCard(
    pair: TrustedPairUiState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Desktop icon in small circle (6% opacity)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f),
                modifier = Modifier.size(28.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Outlined.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = pair.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                pair.systemName?.let {
                    Text(
                        text = "\"$it\"",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                pair.detail?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
