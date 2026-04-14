package io.androdex.android.ui.settings

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import io.androdex.android.ui.shared.RemodexGroupedSurface
import io.androdex.android.ui.shared.RemodexInlineLinkRow
import io.androdex.android.ui.shared.RemodexModalSheet
import io.androdex.android.ui.shared.RemodexPill
import io.androdex.android.ui.shared.RemodexPillStyle
import io.androdex.android.ui.shared.RemodexSelectableOptionRow
import io.androdex.android.ui.shared.RemodexSheetCard
import io.androdex.android.ui.shared.RemodexSheetHeaderBlock
import io.androdex.android.ui.shared.RemodexSheetSectionLabel
import io.androdex.android.ui.shared.TrustedPairCard
import io.androdex.android.ui.state.RuntimeSettingsOptionUiState
import io.androdex.android.ui.state.RuntimeSettingsUiState
import io.androdex.android.ui.theme.RemodexMonoFontFamily
import io.androdex.android.ui.theme.RemodexTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RuntimeSettingsSheet(
    state: RuntimeSettingsUiState,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onOpenPairingSetup: () -> Unit,
    onSelectHostRuntimeTarget: (String) -> Unit,
    onSelectModel: (String?) -> Unit,
    onSelectReasoning: (String?) -> Unit,
    onSelectServiceTier: (String?) -> Unit,
    onSelectAccessMode: (String?) -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors
    var aboutOpen by remember { mutableStateOf(false) }
    val activeRuntime = state.hostRuntimeTargetOptions.firstOrNull { it.selected }
    val activeModel = state.modelOptions.firstOrNull { it.selected }
    val activeReasoning = state.reasoningOptions.firstOrNull { it.selected }
    val activeAccess = state.accessModeOptions.firstOrNull { it.selected }

    if (aboutOpen) {
        AboutAndrodexSheet(
            state = state.about,
            onDismiss = { aboutOpen = false },
        )
    }

    RemodexModalSheet(onDismissRequest = onDismiss) {
        RemodexSheetHeaderBlock(
            title = "Runtime Settings",
            subtitle = "Shape the host before a thread even starts: runtime target, model defaults, speed, and access posture.",
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
                color = colors.accentBlue,
                trackColor = colors.selectedRowFill,
            )
        }

        SettingsSummaryCard(
            activeRuntime = activeRuntime?.title ?: "Codex",
            activeModel = activeModel?.title ?: "Default model",
            activeReasoning = activeReasoning?.title ?: "Default reasoning",
            activeAccess = activeAccess?.title ?: "Default access",
        )

        state.trustedPair?.let {
            TrustedPairCard(state = it)
        }

        state.hostAccount?.let {
            HostAccountCard(state = it)
        }

        BridgeStatusCard(state = state.bridgeStatus)

        SettingsSection(
            title = "Pairing",
            summary = "Set up this phone with a different host without clearing the rest of the app.",
        ) {
            Text(
                text = "This disconnects the current session and opens the pairing flow so you can scan a fresh QR or paste a new pairing payload.",
                style = MaterialTheme.typography.bodySmall,
                color = RemodexTheme.colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(geometry.spacing12))
            RemodexButton(
                onClick = onOpenPairingSetup,
                modifier = Modifier.fillMaxWidth(),
                style = RemodexButtonStyle.Secondary,
            ) {
                Text("Set Up Another Host")
            }
        }

        SettingsSection(
            title = "Host runtime",
            summary = "Choose whether this phone talks to Codex or a local T3 host runtime.",
        ) {
            SettingsRuntimeOptionGroup(
                options = state.hostRuntimeTargetOptions,
                onSelect = { value -> value?.let(onSelectHostRuntimeTarget) },
            )
        }

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
private fun SettingsRuntimeOptionGroup(
    options: List<RuntimeSettingsOptionUiState>,
    onSelect: (String?) -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors
    val activeOption = options.firstOrNull { it.selected }

    Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing10)) {
        RemodexSheetSectionLabel("Runtime")
        RemodexGroupedSurface(
            cornerRadius = geometry.cornerMedium,
            tonalColor = colors.selectedRowFill.copy(alpha = 0.44f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = geometry.spacing14,
                        vertical = geometry.spacing14,
                    ),
                    verticalArrangement = Arrangement.spacedBy(geometry.spacing4),
                ) {
                    Text(
                        text = activeOption?.title?.let { "Currently routed to $it." }
                            ?: "Choose the host runtime target.",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Unavailable runtimes stay visible here so repair guidance is obvious before you switch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                options.forEachIndexed { index, option ->
                    SettingsOptionRow(
                        state = option,
                        onClick = { onSelect(option.value) },
                    )
                    if (index < options.lastIndex) {
                        RemodexDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsSummaryCard(
    activeRuntime: String,
    activeModel: String,
    activeReasoning: String,
    activeAccess: String,
) {
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors

    RemodexSheetCard(
        tint = colors.secondarySurface.copy(alpha = 0.92f),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing8)) {
            Text(
                text = "Control room",
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary,
            )
            Text(
                text = "Your host is ready with a clear default posture.",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            Text(
                text = "These values become the baseline for new turns until a thread chooses overrides of its own.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
        ) {
            SettingsSummaryPill(label = "Runtime", value = activeRuntime)
            SettingsSummaryPill(label = "Model", value = activeModel)
            SettingsSummaryPill(label = "Reasoning", value = activeReasoning)
            SettingsSummaryPill(label = "Access", value = activeAccess)
        }
    }
}

@Composable
private fun SettingsSummaryPill(
    label: String,
    value: String,
) {
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors

    RemodexGroupedSurface(
        cornerRadius = geometry.cornerMedium,
        tonalColor = colors.groupedBackground.copy(alpha = 0.66f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.spacing12, vertical = geometry.spacing10),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing2),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textPrimary,
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
    Column(verticalArrangement = Arrangement.spacedBy(RemodexTheme.geometry.spacing6)) {
        RemodexSelectableOptionRow(
            title = state.title,
            subtitle = settingsOptionPrimarySubtitle(state),
            selected = state.selected,
            enabled = state.enabled,
            onClick = onClick,
            trailing = {
                RemodexPill(
                    label = when {
                        state.selected && !state.enabled -> "Needs repair"
                        state.selected -> "Active"
                        state.enabled -> "Available"
                        else -> "Unavailable"
                    },
                    style = when {
                        state.selected && !state.enabled -> RemodexPillStyle.Warning
                        state.selected -> RemodexPillStyle.Accent
                        state.enabled -> RemodexPillStyle.Success
                        else -> RemodexPillStyle.Warning
                    },
                )
            },
        )
        settingsOptionSupportText(state)?.let { supportText ->
            Text(
                text = supportText,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.enabled || state.selected) {
                    RemodexTheme.colors.textSecondary
                } else {
                    RemodexTheme.colors.accentOrange
                },
            )
        }
    }
}

private fun settingsOptionPrimarySubtitle(state: RuntimeSettingsOptionUiState): String? {
    val subtitle = state.subtitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val availability = settingsOptionSupportText(state) ?: return subtitle
    if (subtitle == availability) {
        return null
    }
    val duplicatedSuffix = "\n$availability"
    return subtitle.removeSuffix(duplicatedSuffix).trimEnd().ifBlank { null }
}

private fun settingsOptionSupportText(state: RuntimeSettingsOptionUiState): String? {
    return state.availabilityMessage?.trim()?.takeIf { it.isNotEmpty() }
}
