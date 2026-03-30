package io.androdex.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.androdex.android.ui.shared.RemodexInlineLinkRow
import io.androdex.android.ui.shared.RemodexModalSheet
import io.androdex.android.ui.shared.RemodexPill
import io.androdex.android.ui.shared.RemodexPillStyle
import io.androdex.android.ui.shared.RemodexSheetCard
import io.androdex.android.ui.shared.RemodexSheetHeaderBlock
import io.androdex.android.ui.shared.RemodexSheetSectionLabel
import io.androdex.android.ui.state.AboutUiState
import io.androdex.android.ui.theme.RemodexTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutAndrodexSheet(
    state: AboutUiState,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    RemodexModalSheet(onDismissRequest = onDismiss) {
        RemodexSheetHeaderBlock(
            title = "About Androdex",
            subtitle = "Androdex keeps Codex running on the host while Android stays a paired remote client for threads, approvals, runtime controls, and project switching.",
        )

        HeroCard(
            icon = Icons.Outlined.Computer,
            title = "Host-first by design",
            body = "The bridge runs on your PC. Android stays a secure controller for pairing, thread controls, and recovery workflows.",
            chips = listOf("Host-local Codex", "Relay-compatible", "Saved pairing"),
        )

        AboutSection(
            title = "Pairing and trust",
            body = "Run `${state.bridgeStartCommand}` on the host to print a fresh QR code. After the secure handshake, Android can reconnect from the saved trusted pair without rescanning unless trust or compatibility changes.",
            icon = Icons.Outlined.Shield,
        )
        AboutSection(
            title = "Host-local runtime",
            body = "Code execution, file edits, git actions, and runtime selection stay on the host. Android is controlling that paired session remotely.",
            icon = Icons.Outlined.Computer,
        )
        AboutSection(
            title = "Recovery",
            body = "If reconnect stops working, update the host package with `${state.bridgeUpdateCommand}` and scan a new QR when the host trust record changes.",
            icon = Icons.Outlined.Info,
        )

        RemodexSheetCard {
            RemodexSheetSectionLabel("Version")
            Text(
                text = "Android app ${state.appVersionLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = RemodexTheme.colors.textPrimary,
            )
        }

        RemodexSheetCard {
            RemodexSheetSectionLabel("Links")
            AboutLinkRow(
                label = "Open GitHub project",
                subtitle = "Browse the public repo and current documentation.",
                onClick = { uriHandler.openUri(state.projectUrl) },
                trailingLabel = "GitHub",
            )
            AboutLinkRow(
                label = "Open support and issues",
                subtitle = "Report bugs, view known issues, or follow active discussions.",
                onClick = { uriHandler.openUri(state.issuesUrl) },
                trailingLabel = "Issues",
            )
            AboutLinkRow(
                label = "Privacy policy",
                subtitle = "Read the current privacy and data-handling policy.",
                onClick = { uriHandler.openUri(state.privacyPolicyUrl) },
                trailingLabel = "Open",
            )
        }
    }
}

@Composable
private fun HeroCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    chips: List<String>,
) {
    RemodexSheetCard(
        tint = RemodexTheme.colors.selectedRowFill.copy(alpha = 0.88f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = RemodexTheme.colors.accentBlue.copy(alpha = 0.14f),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = RemodexTheme.colors.accentBlue,
                        modifier = Modifier.padding(10.dp),
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = RemodexTheme.colors.textPrimary,
                    )
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = RemodexTheme.colors.textSecondary,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chips.forEach { chip ->
                    RemodexPill(label = chip, style = RemodexPillStyle.Accent)
                }
            }
        }
    }
}

@Composable
private fun AboutSection(
    title: String,
    body: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    RemodexSheetCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = RemodexTheme.colors.secondarySurface,
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = RemodexTheme.colors.textPrimary,
                    modifier = Modifier.padding(8.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = RemodexTheme.colors.textPrimary,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RemodexTheme.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun AboutLinkRow(
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    trailingLabel: String,
) {
    RemodexInlineLinkRow(
        title = label,
        subtitle = subtitle,
        trailingLabel = trailingLabel,
        onClick = onClick,
    )
}
