package io.androdex.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.androdex.android.ui.state.AboutUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutAndrodexSheet(
    state: AboutUiState,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uriHandler = LocalUriHandler.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("About Androdex", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Androdex keeps Codex running on the host machine while Android stays a paired remote client for threads, approvals, runtime controls, and project switching.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AboutSection(
                title = "How Pairing Works",
                body = "Run `${state.bridgeStartCommand}` on the host to print a QR. After the secure handshake, Android can reconnect from the saved trusted pair without rescanning unless trust or compatibility changes.",
            )
            AboutSection(
                title = "Host-Local Runtime",
                body = "Code execution, file edits, git actions, and runtime selection stay on the host. Android is controlling that session remotely over the paired bridge.",
            )
            AboutSection(
                title = "Recovery",
                body = "If reconnect stops working, update the host package with `${state.bridgeUpdateCommand}` and scan a new QR when the host trust record changes.",
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Version", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Android app ${state.appVersionLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            TextButton(onClick = { uriHandler.openUri(state.projectUrl) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open GitHub Project")
            }
            TextButton(onClick = { uriHandler.openUri(state.issuesUrl) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Support & Issues")
            }
            TextButton(onClick = { uriHandler.openUri(state.privacyPolicyUrl) }, modifier = Modifier.fillMaxWidth()) {
                Text("Privacy Policy")
            }
        }
    }
}

@Composable
private fun AboutSection(
    title: String,
    body: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
