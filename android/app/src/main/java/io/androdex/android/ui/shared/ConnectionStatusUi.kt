package io.androdex.android.ui.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import io.androdex.android.ui.state.ConnectionBannerOverrideUiState
import io.androdex.android.ui.state.HostAccountUiState
import io.androdex.android.ui.state.SharedStatusTone
import io.androdex.android.ui.state.TrustedPairUiState
import io.androdex.android.ui.theme.RemodexMonoFontFamily
import io.androdex.android.ui.theme.RemodexTheme

internal data class ConnectionBannerPresentation(
    val title: String,
    val badgeLabel: String,
    val tone: SharedStatusTone,
    val guidance: String?,
)

@Composable
internal fun connectionStatusDotColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.CONNECTED -> RemodexTheme.colors.statusDotConnected
    ConnectionStatus.CONNECTING,
    ConnectionStatus.HANDSHAKING,
    ConnectionStatus.RETRYING_SAVED_PAIRING -> RemodexTheme.colors.statusDotSyncing
    ConnectionStatus.TRUST_BLOCKED,
    ConnectionStatus.RECONNECT_REQUIRED,
    ConnectionStatus.UPDATE_REQUIRED -> RemodexTheme.colors.statusDotError
    ConnectionStatus.DISCONNECTED -> RemodexTheme.colors.statusDotOffline
}

internal fun connectionBannerPresentation(
    status: ConnectionStatus,
    detail: String?,
    overridePresentation: ConnectionBannerOverrideUiState? = null,
): ConnectionBannerPresentation {
    overridePresentation?.let {
        return ConnectionBannerPresentation(
            title = it.title,
            badgeLabel = it.badgeLabel,
            tone = it.tone,
            guidance = it.guidance,
        )
    }
    val guidance = when (status) {
        ConnectionStatus.RETRYING_SAVED_PAIRING -> {
            "Saved pairing is still trusted. We'll keep retrying automatically when the host or relay comes back."
        }
        ConnectionStatus.TRUST_BLOCKED -> {
            "This phone cannot read its local trusted identity. Repair with a fresh QR code or forget the trusted host to reset local pairing."
        }
        ConnectionStatus.RECONNECT_REQUIRED -> {
            "Saved pairing needs attention. Reconnect from the trusted pair first, then scan a fresh QR if trust changed."
        }
        ConnectionStatus.UPDATE_REQUIRED -> updateRequiredGuidance(detail)
        else -> null
    }
    return when (status) {
        ConnectionStatus.CONNECTED -> ConnectionBannerPresentation(
            title = "Connected to host",
            badgeLabel = "Live",
            tone = SharedStatusTone.Success,
            guidance = guidance,
        )
        ConnectionStatus.CONNECTING -> ConnectionBannerPresentation(
            title = "Connecting to host",
            badgeLabel = "Syncing",
            tone = SharedStatusTone.Accent,
            guidance = guidance,
        )
        ConnectionStatus.HANDSHAKING -> ConnectionBannerPresentation(
            title = "Completing secure handshake",
            badgeLabel = "Syncing",
            tone = SharedStatusTone.Accent,
            guidance = guidance,
        )
        ConnectionStatus.RETRYING_SAVED_PAIRING -> ConnectionBannerPresentation(
            title = "Waiting for trusted host",
            badgeLabel = "Retrying",
            tone = SharedStatusTone.Accent,
            guidance = guidance,
        )
        ConnectionStatus.TRUST_BLOCKED -> ConnectionBannerPresentation(
            title = "Local trust needs repair",
            badgeLabel = "Blocked",
            tone = SharedStatusTone.Warning,
            guidance = guidance,
        )
        ConnectionStatus.RECONNECT_REQUIRED -> ConnectionBannerPresentation(
            title = "Trusted pair needs repair",
            badgeLabel = "Repair",
            tone = SharedStatusTone.Warning,
            guidance = guidance,
        )
        ConnectionStatus.UPDATE_REQUIRED -> ConnectionBannerPresentation(
            title = "Host and Android are out of sync",
            badgeLabel = "Update",
            tone = SharedStatusTone.Warning,
            guidance = guidance,
        )
        ConnectionStatus.DISCONNECTED -> ConnectionBannerPresentation(
            title = "Host not connected",
            badgeLabel = "Offline",
            tone = SharedStatusTone.Neutral,
            guidance = guidance,
        )
    }
}

@Composable
internal fun StatusCapsule(
    state: ConnectionBannerUiState,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 8.dp,
) {
    val presentation = connectionBannerPresentation(
        status = state.status,
        detail = state.detail,
        overridePresentation = state.presentationOverride,
    )

    SharedStatusCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding),
        title = presentation.title,
        badgeLabel = presentation.badgeLabel,
        tone = presentation.tone,
        leading = {
            SharedStatusDot(state.status)
        },
    ) {
        state.detail?.takeIf { it.isNotBlank() }?.let {
            SharedStatusBodyText(it)
        }
        presentation.guidance?.let {
            SharedStatusBodyText(it)
        }
        state.fingerprint?.takeIf { it.isNotBlank() }?.let {
            SharedMonospaceBlock(
                label = "Trusted host",
                value = it,
            )
        }
    }
}

@Composable
internal fun BusyIndicator(state: BusyUiState) {
    val motion = RemodexTheme.motion
    AnimatedVisibility(
        visible = state.isVisible,
        enter = remodexSlideInVertically(motion.microStateMillis) { -it / 2 } +
            remodexFadeIn(motion.microStateMillis),
        exit = remodexSlideOutVertically(motion.microStateMillis) { -it / 2 } +
            remodexFadeOut(motion.microStateMillis),
    ) {
        SharedActivityCapsule(
            label = state.label ?: "Syncing with host",
            tone = SharedStatusTone.Accent,
        )
    }
}

@Composable
internal fun TrustedPairCard(
    state: TrustedPairUiState,
    modifier: Modifier = Modifier,
) {
    SharedStatusCard(
        modifier = modifier.fillMaxWidth(),
        title = state.title,
        badgeLabel = state.statusLabel,
        tone = state.tone,
        leading = {
            SharedStatusIconBadge(
                icon = Icons.Outlined.Computer,
                tone = state.tone,
            )
        },
    ) {
        Text(
            text = state.name,
            style = MaterialTheme.typography.titleSmall,
            color = RemodexTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        state.systemName?.takeIf { it.isNotBlank() }?.let {
            SharedStatusBodyText(it)
        }
        state.detail?.takeIf { it.isNotBlank() }?.let {
            SharedStatusBodyText(it)
        }
        state.relayLabel?.takeIf { it.isNotBlank() }?.let {
            SharedMetadataRow(label = "Relay", value = it)
        }
        state.fingerprint?.takeIf { it.isNotBlank() }?.let {
            SharedMonospaceBlock(
                label = "Fingerprint",
                value = it,
            )
        }
    }
}

@Composable
internal fun BridgeStatusCard(
    state: BridgeStatusUiState,
    modifier: Modifier = Modifier,
) {
    SharedStatusCard(
        modifier = modifier.fillMaxWidth(),
        title = state.title,
        badgeLabel = state.statusLabel,
        tone = state.tone,
        leading = {
            SharedStatusIconBadge(
                icon = Icons.Outlined.SettingsEthernet,
                tone = state.tone,
            )
        },
    ) {
        SharedStatusBodyText(state.summary)
        SharedMetadataRow(label = "Speed tiers", value = state.serviceTierMessage)
        SharedMetadataRow(label = "Thread forks", value = state.threadForkMessage)
        SharedMonospaceBlock(
            label = "Update command",
            value = state.updateCommand,
        )
    }
}

@Composable
internal fun HostAccountCard(
    state: HostAccountUiState,
    modifier: Modifier = Modifier,
) {
    val geometry = RemodexTheme.geometry

    SharedStatusCard(
        modifier = modifier.fillMaxWidth(),
        title = state.title,
        badgeLabel = state.statusLabel,
        tone = state.tone,
        leading = {
            SharedStatusIconBadge(
                icon = Icons.Outlined.Key,
                tone = state.tone,
            )
        },
    ) {
        state.detail?.takeIf { it.isNotBlank() }?.let {
            SharedStatusBodyText(it)
        }
        state.providerLabel?.takeIf { it.isNotBlank() }?.let {
            SharedMetadataRow(label = "Auth", value = it)
        }
        state.sourceLabel?.takeIf { it.isNotBlank() }?.let {
            SharedMetadataRow(label = "Source", value = it)
        }
        state.authControlLabel?.takeIf { it.isNotBlank() }?.let {
            SharedMetadataRow(label = "Sign-in", value = it)
        }
        state.bridgeVersionLabel?.takeIf { it.isNotBlank() }?.let {
            SharedMetadataRow(label = "Version", value = it)
        }
        if (state.rateLimits.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing8)) {
                Text(
                    text = "Rate limits",
                    style = MaterialTheme.typography.labelLarge,
                    color = RemodexTheme.colors.textSecondary,
                )
                state.rateLimits.forEach { rateLimit ->
                    Surface(
                        color = RemodexTheme.colors.inputBackground,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, RemodexTheme.colors.hairlineDivider),
                    ) {
                        Column(
                            modifier = Modifier.padding(geometry.spacing12),
                            verticalArrangement = Arrangement.spacedBy(geometry.spacing4),
                        ) {
                            Text(
                                text = rateLimit.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = RemodexTheme.colors.textPrimary,
                                fontWeight = FontWeight.Medium,
                            )
                            rateLimit.usageLabel?.takeIf { it.isNotBlank() }?.let {
                                SharedStatusBodyText(it)
                            }
                            rateLimit.resetLabel?.takeIf { it.isNotBlank() }?.let {
                                SharedStatusBodyText(it)
                            }
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
        isStreaming -> "Agent is writing a response"
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
            "Running ${title.take(40)}"
        }
        activeSystemMessage?.kind == ConversationKind.EXECUTION -> {
            val execution = activeSystemMessage.execution
            when {
                execution == null -> "Running activity"
                execution.title.isBlank() -> execution.label
                else -> "${execution.label}: ${execution.title.take(48)}"
            }
        }
        activeSystemMessage?.kind == ConversationKind.SUBAGENT_ACTION -> "Managing subagents"
        activeSystemMessage?.kind == ConversationKind.THINKING -> "Thinking"
        activeSystemMessage?.kind == ConversationKind.PLAN -> "Planning"
        else -> null
    }

    val motion = RemodexTheme.motion

    AnimatedVisibility(
        visible = isStreaming,
        enter = remodexSlideInVertically(motion.microStateMillis) { -it / 2 } +
            remodexFadeIn(motion.microStateMillis),
        exit = remodexSlideOutVertically(motion.microStateMillis) { -it / 2 } +
            remodexFadeOut(motion.microStateMillis),
    ) {
        SharedActivityCapsule(
            label = activityText ?: "Working on host",
            tone = SharedStatusTone.Accent,
        )
    }
}

@Composable
private fun SharedActivityCapsule(
    label: String,
    tone: SharedStatusTone,
) {
    val geometry = RemodexTheme.geometry

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = geometry.pageHorizontalPadding,
                vertical = geometry.spacing8,
            ),
    ) {
        RemodexGroupedSurface(
            cornerRadius = geometry.cornerLarge,
            tonalColor = sharedStatusCardColor(),
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = geometry.spacing14,
                    vertical = geometry.spacing10,
                ),
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = sharedStatusToneColor(tone),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = RemodexTheme.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SharedStatusCard(
    title: String,
    badgeLabel: String,
    tone: SharedStatusTone,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = modifier,
        cornerRadius = geometry.cornerLarge,
        tonalColor = sharedStatusCardColor(),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.spacing14,
                vertical = geometry.spacing14,
            ),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing12),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading()
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(geometry.spacing2),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = RemodexTheme.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                SharedStatusPill(
                    label = badgeLabel,
                    tone = tone,
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SharedStatusDot(status: ConnectionStatus) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(RemodexTheme.colors.selectedRowFill),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(connectionStatusDotColor(status)),
        )
    }
}

@Composable
private fun SharedStatusIconBadge(
    icon: ImageVector,
    tone: SharedStatusTone,
) {
    val geometry = RemodexTheme.geometry

    Surface(
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        color = sharedStatusToneColor(tone).copy(alpha = 0.12f),
        border = BorderStroke(1.dp, RemodexTheme.colors.hairlineDivider),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = sharedStatusToneColor(tone),
                modifier = Modifier.size(geometry.iconSize),
            )
        }
    }
}

@Composable
private fun SharedStatusPill(
    label: String,
    tone: SharedStatusTone,
) {
    RemodexPill(
        label = label,
        style = tone.toPillStyle(),
    )
}

@Composable
private fun SharedStatusBodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = RemodexTheme.colors.textSecondary,
    )
}

@Composable
private fun SharedMetadataRow(
    label: String,
    value: String,
) {
    val geometry = RemodexTheme.geometry

    Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing2)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = RemodexTheme.colors.textTertiary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = RemodexTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun SharedMonospaceBlock(
    label: String,
    value: String,
) {
    val geometry = RemodexTheme.geometry

    Surface(
        color = RemodexTheme.colors.inputBackground,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, RemodexTheme.colors.hairlineDivider),
    ) {
        Column(
            modifier = Modifier.padding(geometry.spacing12),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing6),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = RemodexTheme.colors.textTertiary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = RemodexMonoFontFamily),
                color = RemodexTheme.colors.textPrimary,
            )
        }
    }
}

@Composable
private fun sharedStatusCardColor(): Color {
    return RemodexTheme.colors.secondarySurface.copy(alpha = 0.82f)
}

@Composable
private fun sharedStatusToneColor(tone: SharedStatusTone): Color {
    val colors = RemodexTheme.colors
    return when (tone) {
        SharedStatusTone.Neutral -> colors.textSecondary
        SharedStatusTone.Accent -> colors.accentBlue
        SharedStatusTone.Success -> colors.accentGreen
        SharedStatusTone.Warning -> colors.accentOrange
        SharedStatusTone.Error -> colors.errorRed
    }
}

private fun SharedStatusTone.toPillStyle(): RemodexPillStyle {
    return when (this) {
        SharedStatusTone.Neutral -> RemodexPillStyle.Neutral
        SharedStatusTone.Accent -> RemodexPillStyle.Accent
        SharedStatusTone.Success -> RemodexPillStyle.Success
        SharedStatusTone.Warning -> RemodexPillStyle.Warning
        SharedStatusTone.Error -> RemodexPillStyle.Error
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
