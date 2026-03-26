package io.androdex.android.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.androdex.android.model.ApprovalRequest

@Composable
internal fun ApprovalDialog(
    request: ApprovalRequest?,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
) {
    val activeRequest = request ?: return

    AlertDialog(
        onDismissRequest = { },
        confirmButton = {
            Button(onClick = onApprove) {
                Text("Approve")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline) {
                Text("Decline")
            }
        },
        icon = {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Text("Approval Required", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = activeRequest.method,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                activeRequest.command?.takeIf { it.isNotBlank() }?.let { command ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = command,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                activeRequest.reason?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        shape = MaterialTheme.shapes.large,
    )
}

@Composable
internal fun ErrorMessageDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
        title = {
            Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        },
        shape = MaterialTheme.shapes.large,
    )
}
