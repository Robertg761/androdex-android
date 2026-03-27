package io.androdex.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.androdex.android.ui.shared.BridgeStatusCard
import io.androdex.android.ui.shared.HostAccountCard
import io.androdex.android.ui.shared.TrustedPairCard
import io.androdex.android.ui.state.RuntimeSettingsOptionUiState
import io.androdex.android.ui.state.RuntimeSettingsUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuntimeSettingsSheet(
    state: RuntimeSettingsUiState,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onSelectModel: (String?) -> Unit,
    onSelectReasoning: (String?) -> Unit,
    onSelectServiceTier: (String?) -> Unit,
    onSelectAccessMode: (String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    var aboutOpen by remember { mutableStateOf(false) }

    if (aboutOpen) {
        AboutAndrodexSheet(
            state = state.about,
            onDismiss = { aboutOpen = false },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onReload) {
                    Text("Reload")
                }
            }

            if (state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
            }

            state.trustedPair?.let {
                TrustedPairCard(state = it)
            }

            state.hostAccount?.let {
                HostAccountCard(state = it)
            }

            BridgeStatusCard(state = state.bridgeStatus)

            SettingsSection(
                title = "Runtime Defaults",
                summary = "Choose the default model, reasoning effort, service tier, and access level for new turns on the host.",
            ) {
                SectionHeader("Model")
                Spacer(modifier = Modifier.height(8.dp))
                state.modelOptions.forEach { option ->
                    SettingsOptionRow(state = option, onClick = { onSelectModel(option.value) })
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                SectionHeader("Reasoning")
                Spacer(modifier = Modifier.height(8.dp))
                state.reasoningOptions.forEach { option ->
                    SettingsOptionRow(state = option, onClick = { onSelectReasoning(option.value) })
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                SectionHeader("Speed")
                Spacer(modifier = Modifier.height(8.dp))
                if (state.serviceTierSupported) {
                    state.serviceTierOptions.forEach { option ->
                        SettingsOptionRow(state = option, onClick = { onSelectServiceTier(option.value) })
                    }
                } else {
                    Text(
                        text = "This host bridge does not expose service-tier controls yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                SectionHeader("Access")
                Spacer(modifier = Modifier.height(8.dp))
                state.accessModeOptions.forEach { option ->
                    SettingsOptionRow(state = option, onClick = { onSelectAccessMode(option.value) })
                }
            }

            SettingsSection(
                title = "Compatibility",
                summary = "Keep Android and the host bridge in sync so reconnect, runtime controls, and newer thread actions stay reliable.",
            ) {
                Text(
                    text = "Host update command",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = state.bridgeStatus.updateCommand,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection(
                title = "About",
                summary = "Host-local runtime, secure pairing, and recovery notes for Androdex.",
            ) {
                TextButton(
                    onClick = { aboutOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("How Androdex Works")
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    summary: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 1.5f,
    )
}

@Composable
private fun SettingsOptionRow(
    state: RuntimeSettingsOptionUiState,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = state.selected, onClick = onClick)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(state.title, style = MaterialTheme.typography.bodyLarge)
            state.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
