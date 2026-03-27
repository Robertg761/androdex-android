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

@Composable
internal fun StatusCapsule(
    state: ConnectionBannerUiState,
    modifier: Modifier = Modifier,
) {
    val (dotColor, label) = when (state.status) {
        ConnectionStatus.CONNECTED -> Color(0xFF34D399) to "Connected"
        ConnectionStatus.CONNECTING -> Color(0xFFFBBF24) to "Connecting"
        ConnectionStatus.HANDSHAKING -> Color(0xFFFBBF24) to "Handshaking"
        ConnectionStatus.RETRYING_SAVED_PAIRING -> Color(0xFFF59E0B) to "Waiting For Host"
        ConnectionStatus.RECONNECT_REQUIRED -> Color(0xFFF87171) to "Repair Pairing Required"
        ConnectionStatus.UPDATE_REQUIRED -> Color(0xFFF87171) to "Update Required"
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline to "Disconnected"
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

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    )
                }
            }
            state.detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            guidance?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (state.detail.isNullOrBlank()) 4.dp else 2.dp),
                )
            }
            state.fingerprint?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "Host: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
internal fun BusyIndicator(state: BusyUiState) {
    AnimatedVisibility(
        visible = state.isVisible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            )
            state.label?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                StatusPill(label = state.statusLabel)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = state.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.systemName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.detail?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            state.relayLabel?.let {
                MetadataRow(
                    label = "Relay",
                    value = it,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
            state.fingerprint?.let {
                MetadataRow(label = "Fingerprint", value = it)
            }
        }
    }
}

@Composable
private fun StatusPill(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
internal fun BridgeStatusCard(
    state: BridgeStatusUiState,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SettingsEthernet,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = state.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetadataRow(label = "Speed tiers", value = state.serviceTierMessage)
            MetadataRow(label = "Thread forks", value = state.threadForkMessage)
            Text(
                text = "Update command: ${state.updateCommand}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
internal fun HostAccountCard(
    state: HostAccountUiState,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                StatusPill(label = state.statusLabel)
            }

            state.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Rate limits",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                state.rateLimits.forEach { rateLimit ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        rateLimit.resetLabel?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = activityText ?: "Working...",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
        } else {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
