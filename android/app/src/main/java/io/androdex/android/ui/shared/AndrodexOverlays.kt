package io.androdex.android.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.MissingNotificationThreadPrompt
import io.androdex.android.ui.theme.RemodexTheme

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
        title = "Approval Required",
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
            Surface(
                modifier = Modifier.size(36.dp),
                shape = MaterialTheme.shapes.medium,
                color = RemodexTheme.colors.selectedRowFill,
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = RemodexTheme.colors.accentBlue,
                        modifier = Modifier
                            .padding(geometry.spacing8)
                            .size(geometry.iconSize),
                    )
                }
            },
            content = {
            Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing8)) {
                Text(
                    text = activeRequest.method,
                    style = MaterialTheme.typography.labelMedium,
                    color = RemodexTheme.colors.accentBlue,
                )
                activeRequest.command?.takeIf { it.isNotBlank() }?.let { command ->
                    Surface(
                        color = RemodexTheme.colors.inputBackground,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = command,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = RemodexTheme.colors.textPrimary,
                            modifier = Modifier.padding(geometry.spacing12),
                        )
                    }
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
        content = {
            Text(
                "The notification opened thread ${prompt.threadId}, but that thread is no longer available on this host. Androdex kept your current conversation open when possible.",
                style = MaterialTheme.typography.bodyMedium,
                color = RemodexTheme.colors.textSecondary,
            )
        },
    )
}
