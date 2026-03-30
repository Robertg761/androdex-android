package io.androdex.android.ui.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.MissingNotificationThreadPrompt
import io.androdex.android.ui.theme.RemodexMonoFontFamily
import io.androdex.android.ui.theme.RemodexTheme

private enum class OverlayTone {
    Accent,
    Warning,
    Error,
}

@Composable
internal fun ApprovalDialog(
    request: ApprovalRequest?,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val activeRequest = request ?: return

    RemodexAlertDialog(
        onDismissRequest = { },
        title = "Approval required",
        confirmButton = {
            RemodexButton(onClick = onApprove) {
                Text("Approve")
            }
        },
        dismissButton = {
            RemodexButton(
                onClick = onDecline,
                style = RemodexButtonStyle.Secondary,
            ) {
                Text("Decline")
            }
        },
        icon = {
            DialogIconBadge(
                imageVector = Icons.Default.Settings,
                tone = OverlayTone.Accent,
            )
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing8)) {
                DialogEyebrow(
                    text = activeRequest.method,
                    tone = OverlayTone.Accent,
                )
                Text(
                    text = "The host paused because this action needs approval before it can continue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RemodexTheme.colors.textSecondary,
                )
                activeRequest.command?.takeIf { it.isNotBlank() }?.let { command ->
                    DialogMonoBlock(
                        label = "Requested command",
                        value = command,
                        tone = OverlayTone.Accent,
                    )
                }
                activeRequest.reason?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = RemodexTheme.colors.textSecondary,
                    )
                }
            }
        },
    )
}

@Composable
internal fun ErrorMessageDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    RemodexAlertDialog(
        onDismissRequest = onDismiss,
        title = "Something went wrong",
        confirmButton = {
            RemodexButton(
                onClick = onDismiss,
                style = RemodexButtonStyle.Secondary,
            ) {
                Text("OK")
            }
        },
        icon = {
            DialogIconBadge(
                imageVector = Icons.Outlined.WarningAmber,
                tone = OverlayTone.Error,
            )
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(RemodexTheme.geometry.spacing10)) {
                Text(
                    text = "Androdex received an error from the host, bridge, or current runtime action.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RemodexTheme.colors.textSecondary,
                )
                DialogMonoBlock(
                    label = "Message",
                    value = message,
                    tone = OverlayTone.Error,
                )
            }
        },
    )
}

@Composable
internal fun MissingNotificationThreadDialog(
    prompt: MissingNotificationThreadPrompt,
    onDismiss: () -> Unit,
) {
    RemodexAlertDialog(
        onDismissRequest = onDismiss,
        title = "Conversation unavailable",
        confirmButton = {
            RemodexButton(
                onClick = onDismiss,
                style = RemodexButtonStyle.Secondary,
            ) {
                Text("OK")
            }
        },
        icon = {
            DialogIconBadge(
                imageVector = Icons.Outlined.WarningAmber,
                tone = OverlayTone.Warning,
            )
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(RemodexTheme.geometry.spacing10)) {
                Text(
                    text = "The notification targeted a conversation that is no longer available on this host. Androdex kept your current thread open when it could.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RemodexTheme.colors.textSecondary,
                )
                DialogMonoBlock(
                    label = "Missing thread",
                    value = prompt.threadId,
                    tone = OverlayTone.Warning,
                )
            }
        },
    )
}

@Composable
internal fun ThreadMaintenanceConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RemodexAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = {
            RemodexButton(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            RemodexButton(
                onClick = onDismiss,
                style = RemodexButtonStyle.Secondary,
            ) {
                Text("Cancel")
            }
        },
        icon = {
            DialogIconBadge(
                imageVector = Icons.Outlined.WarningAmber,
                tone = OverlayTone.Warning,
            )
        },
        content = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = RemodexTheme.colors.textSecondary,
            )
        },
    )
}

@Composable
private fun DialogIconBadge(
    imageVector: ImageVector,
    tone: OverlayTone,
) {
    val geometry = RemodexTheme.geometry
    val (containerColor, iconTint) = overlayToneColors(tone)

    Surface(
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(1.dp, RemodexTheme.colors.hairlineDivider),
    ) {
        Box(
            modifier = Modifier.size(42.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(geometry.iconSize + 2.dp),
            )
        }
    }
}

@Composable
private fun DialogEyebrow(
    text: String,
    tone: OverlayTone,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val (_, tint) = overlayToneColors(tone)

    Surface(
        color = colors.selectedRowFill,
        shape = CircleShape,
        border = BorderStroke(1.dp, colors.hairlineDivider),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(
                horizontal = geometry.spacing10,
                vertical = geometry.spacing4,
            ),
        )
    }
}

@Composable
private fun DialogMonoBlock(
    label: String,
    value: String,
    tone: OverlayTone,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val (_, tint) = overlayToneColors(tone)

    Surface(
        color = colors.inputBackground,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, colors.hairlineDivider),
    ) {
        Column(
            modifier = Modifier.padding(geometry.spacing12),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing6),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(tint, CircleShape),
                ) {
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = RemodexMonoFontFamily),
                color = colors.textPrimary,
            )
        }
    }
}

@Composable
private fun overlayToneColors(tone: OverlayTone): Pair<Color, Color> {
    val colors = RemodexTheme.colors
    return when (tone) {
        OverlayTone.Accent -> colors.accentBlue.copy(alpha = 0.14f) to colors.accentBlue
        OverlayTone.Warning -> colors.accentOrange.copy(alpha = 0.16f) to colors.accentOrange
        OverlayTone.Error -> colors.errorRed.copy(alpha = 0.14f) to colors.errorRed
    }
}
