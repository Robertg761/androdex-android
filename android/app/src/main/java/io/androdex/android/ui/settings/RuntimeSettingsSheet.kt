package io.androdex.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.androdex.android.ui.shared.BridgeStatusCard
import io.androdex.android.ui.shared.HostAccountCard
import io.androdex.android.ui.shared.RemodexButton
import io.androdex.android.ui.shared.RemodexButtonStyle
import io.androdex.android.ui.shared.RemodexDivider
import io.androdex.android.ui.shared.RemodexInlineLinkRow
import io.androdex.android.ui.shared.RemodexModalSheet
import io.androdex.android.ui.shared.RemodexSelectableOptionRow
import io.androdex.android.ui.shared.RemodexSheetCard
import io.androdex.android.ui.shared.RemodexSheetHeaderBlock
import io.androdex.android.ui.shared.RemodexSheetSectionLabel
import io.androdex.android.ui.shared.TrustedPairCard
import io.androdex.android.ui.state.RuntimeSettingsOptionUiState
import io.androdex.android.ui.state.RuntimeSettingsUiState
import io.androdex.android.ui.theme.RemodexMonoFontFamily
import io.androdex.android.ui.theme.RemodexTheme

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
    val geometry = RemodexTheme.geometry
    var aboutOpen by remember { mutableStateOf(false) }

    if (aboutOpen) {
        AboutAndrodexSheet(
            state = state.about,
            onDismiss = { aboutOpen = false },
        )
    }

    RemodexModalSheet(onDismissRequest = onDismiss) {
        RemodexSheetHeaderBlock(
            title = "Runtime Settings",
            subtitle = "Choose the default model, reasoning effort, speed, and access policy the host should use for new turns.",
            trailing = {
                RemodexButton(
                    onClick = onReload,
                    style = RemodexButtonStyle.Ghost,
                ) {
                    Text("Reload")
                }
            },
        )

        if (state.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = RemodexTheme.colors.accentBlue,
                trackColor = RemodexTheme.colors.selectedRowFill,
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
            title = "Runtime defaults",
            summary = "These defaults apply across the app until a thread overrides them.",
        ) {
            SettingsOptionGroup(
                title = "Model",
                options = state.modelOptions,
                onSelect = onSelectModel,
            )
            RemodexDivider()
            SettingsOptionGroup(
                title = "Reasoning",
                options = state.reasoningOptions,
                onSelect = onSelectReasoning,
            )
            RemodexDivider()
            Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing8)) {
                RemodexSheetSectionLabel("Speed")
                if (state.serviceTierSupported) {
                    state.serviceTierOptions.forEach { option ->
                        SettingsOptionRow(state = option, onClick = { onSelectServiceTier(option.value) })
                    }
                } else {
                    Text(
                        text = "This host bridge does not expose service-tier controls yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RemodexTheme.colors.textSecondary,
                    )
                }
            }
            RemodexDivider()
            SettingsOptionGroup(
                title = "Access",
                options = state.accessModeOptions,
                onSelect = onSelectAccessMode,
            )
        }

        SettingsSection(
            title = "Compatibility",
            summary = "Keep Android and the host bridge aligned so reconnect, runtime controls, and newer turn actions stay reliable.",
        ) {
            RemodexSheetSectionLabel("Host update command")
            Text(
                text = state.bridgeStatus.updateCommand,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = RemodexMonoFontFamily),
                color = RemodexTheme.colors.textSecondary,
            )
        }

        SettingsSection(
            title = "About Androdex",
            summary = "Host-local runtime, secure pairing, recovery guidance, and project links.",
        ) {
            RemodexInlineLinkRow(
                title = "How Androdex works",
                subtitle = "Open the product overview, pairing notes, and recovery guidance.",
                trailingLabel = "Open",
                onClick = { aboutOpen = true },
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    summary: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    RemodexSheetCard {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = RemodexTheme.colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(RemodexTheme.geometry.spacing2))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = RemodexTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(RemodexTheme.geometry.spacing6))
        content()
    }
}

@Composable
private fun SettingsOptionGroup(
    title: String,
    options: List<RuntimeSettingsOptionUiState>,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RemodexTheme.geometry.spacing8)) {
        RemodexSheetSectionLabel(title)
        options.forEach { option ->
            SettingsOptionRow(
                state = option,
                onClick = { onSelect(option.value) },
            )
        }
    }
}

@Composable
private fun SettingsOptionRow(
    state: RuntimeSettingsOptionUiState,
    onClick: () -> Unit,
) {
    RemodexSelectableOptionRow(
        title = state.title,
        subtitle = state.subtitle,
        selected = state.selected,
        onClick = onClick,
    )
}
