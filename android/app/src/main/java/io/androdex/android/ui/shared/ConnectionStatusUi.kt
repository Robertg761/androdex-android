package io.androdex.android.ui.shared

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.ui.state.BridgeStatusUiState
import io.androdex.android.ui.state.BusyUiState
import io.androdex.android.ui.state.ConnectionBannerUiState
import io.androdex.android.ui.state.HostAccountUiState
import io.androdex.android.ui.state.TrustedPairUiState
import io.androdex.android.ui.theme.RemodexTheme

@Composable
internal fun connectionStatusDotColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.CONNECTED -> RemodexTheme.colors.statusDotConnected
    ConnectionStatus.CONNECTING,
    ConnectionStatus.HANDSHAKING,
    ConnectionStatus.RETRYING_SAVED_PAIRING -> RemodexTheme.colors.statusDotSyncing
    ConnectionStatus.RECONNECT_REQUIRED,
    ConnectionStatus.UPDATE_REQUIRED -> RemodexTheme.colors.statusDotError
    ConnectionStatus.DISCONNECTED -> RemodexTheme.colors.statusDotOffline
}

@Composable
internal fun StatusCapsule(
    state: ConnectionBannerUiState,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 8.dp,
) {
    val geometry = RemodexTheme.geometry
    val dotColor = connectionStatusDotColor(state.status)
    val label = when (state.status) {
        ConnectionStatus.CONNECTED -> "Connected"
        ConnectionStatus.CONNECTING -> "Connecting"
        ConnectionStatus.HANDSHAKING -> "Handshaking"
        ConnectionStatus.RETRYING_SAVED_PAIRING -> "Waiting for host"
        ConnectionStatus.RECONNECT_REQUIRED -> "Repair Pairing Required"
        ConnectionStatus.UPDATE_REQUIRED -> "Update Required"
        ConnectionStatus.DISCONNECTED -> "Disconnected"
    }
    val guidance = when (state.status) {
        ConnectionStatus.RETRYING_SAVED_PAIRING -> {
            "Saved pairing is still trusted. We'll retry automatically when the host or relay comes back."
        }
        ConnectionStatus.RECONNECT_REQUIRED -> {
            "Saved pairing needs attention. Reconnect from saved pairing or scan a fresh QR code if trust changed."
        }
        ConnectionStatus.UPDATE_REQUIRED -> updateRequiredGuidance(state.detail)
        else -> null
    }

    RemodexGroupedSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding),
        cornerRadius = RemodexTheme.geometry.cornerSmall,
        tonalColor = RemodexTheme.colors.secondarySurface.copy(alpha = 0.85f),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.spacing14,
                vertical = geometry.spacing10,
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
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
                        color = RemodexTheme.colors.textPrimary,
                    )
                }
            }
            state.detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = RemodexTheme.colors.textSecondary,
                        modifier = Modifier.padding(top = geometry.spacing4),
                    )
                }
            guidance?.let {
                Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = RemodexTheme.colors.textSecondary,
                        modifier = Modifier.padding(
                            top = if (state.detail.isNullOrBlank()) geometry.spacing4 else geometry.spacing2,
                        ),
                    )
                }
            state.fingerprint?.takeIf { it.isNotBlank() }?.let {
                Text(
                        text = "Host: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = RemodexTheme.colors.textTertiary,
                        modifier = Modifier.padding(top = geometry.spacing2),
                    )
                }
        }
    }
}

@Composable
internal fun BusyIndicator(state: BusyUiState) {
    val geometry = RemodexTheme.geometry

    AnimatedVisibility(
        visible = state.isVisible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = RemodexTheme.colors.accentBlue,
                trackColor = RemodexTheme.colors.selectedRowFill,
            )
            state.label?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = RemodexTheme.colors.textSecondary,
                    modifier = Modifier.padding(
                        horizontal = geometry.pageHorizontalPadding,
                        vertical = geometry.spacing6,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun TrustedPairCard(
    state: TrustedPairUiState,
    modifier: Modifier = Modifier,
) {
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = RemodexTheme.geometry.cornerLarge,
        tonalColor = RemodexTheme.colors.secondarySurface.copy(alpha = 0.85f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = geometry.spacing14, vertical = geometry.spacing14),
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Desktop icon in small circular background
            Surface(
                color = RemodexTheme.colors.selectedRowFill,
                shape = CircleShape,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Outlined.Computer,
                        contentDescription = null,
                        tint = RemodexTheme.colors.textPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing2),
            ) {
                Text(
                    text = state.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = RemodexTheme.colors.textPrimary,
                )
                state.systemName?.let {
                    Text(
                        text = "\"$it\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = RemodexTheme.colors.textSecondary,
                    )
                }
                state.detail?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = RemodexTheme.colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            StatusPill(label = state.statusLabel)
        }
    }
}

@Composable
private fun StatusPill(label: String) {
    RemodexPill(label = label, style = RemodexPillStyle.Accent)
}

@Composable
internal fun BridgeStatusCard(
    state: BridgeStatusUiState,
    modifier: Modifier = Modifier,
) {
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = RemodexTheme.geometry.cornerLarge,
        tonalColor = RemodexTheme.colors.secondarySurface.copy(alpha = 0.85f),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.sidebarOuterHorizontalPadding,
                vertical = geometry.spacing14,
            ),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SettingsEthernet,
                    contentDescription = null,
                    tint = RemodexTheme.colors.accentBlue,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = RemodexTheme.colors.textPrimary,
                )
            }
            Text(
                text = state.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = RemodexTheme.colors.textSecondary,
            )
            MetadataRow(label = "Speed tiers", value = state.serviceTierMessage)
            MetadataRow(label = "Thread forks", value = state.threadForkMessage)
            Text(
                text = "Update command: ${state.updateCommand}",
                style = MaterialTheme.typography.labelSmall,
                color = RemodexTheme.colors.textTertiary,
            )
        }
    }
}

@Composable
internal fun HostAccountCard(
    state: HostAccountUiState,
    modifier: Modifier = Modifier,
) {
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = RemodexTheme.geometry.cornerLarge,
        tonalColor = RemodexTheme.colors.secondarySurface.copy(alpha = 0.78f),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.sidebarOuterHorizontalPadding,
                vertical = geometry.spacing14,
            ),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Key,
                        contentDescription = null,
                        tint = RemodexTheme.colors.accentBlue,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = RemodexTheme.colors.textPrimary,
                    )
                }
                StatusPill(label = state.statusLabel)
            }

            state.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RemodexTheme.colors.textSecondary,
                )
            }

            state.providerLabel?.let { provider ->
                MetadataRow(label = "Auth", value = provider)
            }
            state.sourceLabel?.let { source ->
                MetadataRow(label = "Source", value = source)
            }
            state.authControlLabel?.let { authControl ->
                MetadataRow(label = "Sign-in", value = authControl)
            }
            state.bridgeVersionLabel?.let { version ->
                MetadataRow(label = "Version", value = version)
            }
            if (state.rateLimits.isNotEmpty()) {
                RemodexDivider()
                Text(
                    text = "Rate limits",
                    style = MaterialTheme.typography.labelLarge,
                    color = RemodexTheme.colors.accentBlue,
                )
                state.rateLimits.forEach { rateLimit ->
                    Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing2)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = rateLimit.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            rateLimit.usageLabel?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = RemodexTheme.colors.textSecondary,
                                )
                            }
                        }
                        rateLimit.resetLabel?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = RemodexTheme.colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgentActivityBanner(messages: List<ConversationMessage>) {
    val geometry = RemodexTheme.geometry
    val isStreaming = messages.any { it.isStreaming }
    val activeSystemMessage = messages.lastOrNull {
        it.role == ConversationRole.SYSTEM && it.isStreaming
    } ?: messages.lastOrNull { it.role == ConversationRole.SYSTEM }

    val activityText = when {
        !isStreaming && activeSystemMessage == null -> null
        isStreaming -> "Agent is writing a response..."
        activeSystemMessage?.kind == ConversationKind.FILE_CHANGE -> {
            val fileName = activeSystemMessage.filePath
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
            if (fileName != null) "Edited $fileName" else "Edited files"
        }
        activeSystemMessage?.kind == ConversationKind.COMMAND -> {
            val title = activeSystemMessage.execution?.title
                ?: activeSystemMessage.command
                ?: "command"
            "Running: ${title.take(40)}"
        }
        activeSystemMessage?.kind == ConversationKind.EXECUTION -> {
            val execution = activeSystemMessage.execution
            when {
                execution == null -> "Running activity..."
                execution.title.isBlank() -> "Running ${execution.label.lowercase()}..."
                else -> "${execution.label}: ${execution.title.take(48)}"
            }
        }
        activeSystemMessage?.kind == ConversationKind.SUBAGENT_ACTION -> "Managing subagents..."
        activeSystemMessage?.kind == ConversationKind.THINKING -> "Thinking..."
        activeSystemMessage?.kind == ConversationKind.PLAN -> "Planning..."
        else -> null
    }

    AnimatedVisibility(
        visible = isStreaming,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Surface(
            color = RemodexTheme.colors.accentBlue.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = geometry.sidebarOuterHorizontalPadding,
                    vertical = geometry.spacing8,
                ),
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = RemodexTheme.colors.accentBlue,
                )
                Text(
                    text = activityText ?: "Working...",
                    style = MaterialTheme.typography.labelMedium,
                    color = RemodexTheme.colors.accentBlue,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun updateRequiredGuidance(detail: String?): String {
    val normalized = detail?.lowercase()
        ?: return "Update the Android app or host bridge, then reconnect with the saved pairing."
    return when {
        normalized.contains("latest compatible androdex mobile app") -> {
            "This host bridge needs a newer Android app build. Update the Android app, then reconnect with the saved pairing."
        }
        normalized.contains("bridge and mobile client are not using the same secure transport version") -> {
            "The Android app and host bridge are out of sync. Update whichever side is older, then reconnect with the saved pairing."
        }
        normalized.contains("bridge version mismatch")
            || normalized.contains("different secure transport version") -> {
            "This saved pairing reached a host bridge with a different secure transport version. Update the host bridge or Android app, then reconnect."
        }
        else -> "Update the Android app or host bridge, then reconnect with the saved pairing."
    }
}

@Composable
private fun MetadataRow(
    label: String,
    value: String,
    icon: (@Composable () -> Unit)? = null,
) {
    val geometry = RemodexTheme.geometry

    Row(
        horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
        } else {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(RemodexTheme.colors.textTertiary, CircleShape),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = RemodexTheme.colors.accentBlue,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = RemodexTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}
