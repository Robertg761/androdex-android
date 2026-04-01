package io.androdex.android.ui.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androdex.android.R
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.ui.shared.HostAccountCard
import io.androdex.android.ui.shared.LandingBackdrop
import io.androdex.android.ui.shared.RemodexButton
import io.androdex.android.ui.shared.RemodexButtonStyle
import io.androdex.android.ui.shared.RemodexDivider
import io.androdex.android.ui.shared.RemodexGroupedSurface
import io.androdex.android.ui.shared.RemodexInputField
import io.androdex.android.ui.shared.RemodexPill
import io.androdex.android.ui.shared.RemodexPillStyle
import io.androdex.android.ui.shared.StatusCapsule
import io.androdex.android.ui.state.PairingScreenUiState
import io.androdex.android.ui.state.TrustedPairUiState
import io.androdex.android.ui.theme.RemodexTheme

private val PairingContentMaxWidth = 420.dp

@Composable
internal fun PairingScreen(
    state: PairingScreenUiState,
    onPairingInputChanged: (String) -> Unit,
    onScanQr: () -> Unit,
    onConnect: () -> Unit,
    onReconnectSaved: () -> Unit,
    onRepairWithFreshQr: () -> Unit,
    onForgetTrustedHost: () -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val cardModifier = Modifier
        .fillMaxWidth()
        .widthIn(max = PairingContentMaxWidth)

    Scaffold(containerColor = Color.Transparent) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            LandingBackdrop()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = geometry.pageHorizontalPadding,
                        vertical = geometry.pageVerticalPadding,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(geometry.spacing18),
            ) {
                PairingHeroCard(
                    state = state,
                    onScanQr = onScanQr,
                    onReconnectSaved = onReconnectSaved,
                    modifier = cardModifier,
                )

                StatusCapsule(
                    state = state.connection,
                    modifier = cardModifier,
                )

                if (state.hasSavedPairing || state.trustedPair != null || state.connection.status == ConnectionStatus.TRUST_BLOCKED) {
                    SavedReconnectCard(
                        state = state,
                        onPrimaryAction = if (state.connection.status == ConnectionStatus.TRUST_BLOCKED) {
                            onRepairWithFreshQr
                        } else {
                            onReconnectSaved
                        },
                        onForgetTrustedHost = onForgetTrustedHost,
                        modifier = cardModifier,
                    )
                }

                RecoveryCard(
                    state = state,
                    modifier = cardModifier,
                )

                ManualPairingCard(
                    state = state,
                    onPairingInputChanged = onPairingInputChanged,
                    onConnect = onConnect,
                    modifier = cardModifier,
                )

                if (state.hostAccount != null) {
                    HostAccountCard(
                        state = state.hostAccount,
                        modifier = cardModifier,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PairingHeroCard(
    state: PairingScreenUiState,
    onScanQr: () -> Unit,
    onReconnectSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val buttonPadding = PaddingValues(
        horizontal = geometry.spacing20,
        vertical = geometry.spacing14,
    )

    RemodexGroupedSurface(
        modifier = modifier,
        cornerRadius = geometry.cornerComposer,
        tonalColor = colors.subtleGlassTint,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.spacing24,
                vertical = geometry.spacing24,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(geometry.spacing20),
        ) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = RoundedCornerShape(18.dp),
                color = colors.selectedRowFill.copy(alpha = 0.82f),
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "Androdex",
                    modifier = Modifier.size(72.dp),
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
            ) {
                Text(
                    text = "Androdex",
                    style = MaterialTheme.typography.displayLarge,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Reconnect to a trusted host, or scan a fresh QR from the bridge when you need to repair or replace the current pairing.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp),
                )
            }

            PairingTrustCapsule()

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
            ) {
                PairingFeatureRow(
                    icon = Icons.Outlined.Computer,
                    title = "Recovery stays host-first",
                    description = "Threads, tools, and git actions still stay on your computer while Android repairs or resumes access.",
                )
                PairingFeatureRow(
                    icon = Icons.Outlined.Lock,
                    title = "Trusted pair first",
                    description = "Try the saved pair before falling back to a fresh QR or a pasted payload.",
                )
            }

            if (state.isBusy) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.accentBlue,
                        trackColor = colors.selectedRowFill,
                    )
                    Text(
                        text = state.connection.detail ?: "Connecting to your host...",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing12),
            ) {
                RemodexButton(
                    onClick = onScanQr,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = buttonPadding,
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode2,
                        contentDescription = null,
                        modifier = Modifier.size(geometry.iconSize),
                    )
                    Text(
                        text = "Scan fresh QR",
                        modifier = Modifier.padding(start = geometry.spacing8),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }

                if (state.hasSavedPairing) {
                    RemodexButton(
                        onClick = onReconnectSaved,
                        enabled = state.reconnectEnabled,
                        modifier = Modifier.fillMaxWidth(),
                        style = RemodexButtonStyle.Secondary,
                        contentPadding = buttonPadding,
                    ) {
                        Text(
                            text = state.reconnectButtonLabel,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
            ) {
                if (state.hasSavedPairing) {
                    PairingMetadataChip("Saved trust")
                }
                if (state.defaultRelayUrl != null) {
                    PairingMetadataChip("Relay-ready")
                }
                PairingMetadataChip("Host-local")
            }
        }
    }
}

@Composable
private fun PairingTrustCapsule() {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 332.dp),
        cornerRadius = geometry.cornerLarge,
        tonalColor = colors.secondarySurface.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = geometry.spacing14,
                vertical = geometry.spacing12,
            ),
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                color = colors.accentBlue.copy(alpha = 0.14f),
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = colors.accentBlue,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing2)) {
                Text(
                    text = "End-to-end encrypted pairing",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Relay-compatible reconnects stay tied to your trusted host.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun PairingFeatureRow(
    icon: ImageVector,
    title: String,
    description: String,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = geometry.cornerLarge,
        tonalColor = colors.secondarySurface.copy(alpha = 0.62f),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = geometry.spacing14,
                vertical = geometry.spacing12,
            ),
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                color = colors.selectedRowFill.copy(alpha = 0.88f),
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing2),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedReconnectCard(
    state: PairingScreenUiState,
    onPrimaryAction: () -> Unit,
    onForgetTrustedHost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val reconnectStyle = when (state.connection.status) {
        ConnectionStatus.TRUST_BLOCKED,
        ConnectionStatus.RECONNECT_REQUIRED,
        ConnectionStatus.UPDATE_REQUIRED -> RemodexPillStyle.Warning
        ConnectionStatus.RETRYING_SAVED_PAIRING -> RemodexPillStyle.Accent
        ConnectionStatus.CONNECTED -> RemodexPillStyle.Success
        else -> RemodexPillStyle.Neutral
    }

    RemodexGroupedSurface(
        modifier = modifier,
        cornerRadius = geometry.cornerComposer,
        tonalColor = colors.secondarySurface.copy(alpha = 0.78f),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.sectionPadding,
                vertical = geometry.sectionPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing16),
        ) {
            Text(
                text = "TRUSTED PAIR",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
                color = colors.textSecondary,
            )

            Text(
                text = when {
                    state.connection.status == ConnectionStatus.TRUST_BLOCKED -> "Repair local trust with a fresh QR"
                    state.trustedPair != null && !state.trustedPair.hasSavedRelaySession -> "Resolve a fresh live session"
                    state.trustedPair != null -> "Reconnect without rescanning"
                    else -> "Recovery is ready"
                },
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
            )

            Text(
                text = when (state.connection.status) {
                    ConnectionStatus.RETRYING_SAVED_PAIRING ->
                        "Android still trusts this host. Keep the bridge running and Androdex will keep retrying in the foreground."
                    ConnectionStatus.TRUST_BLOCKED ->
                        "This phone cannot read its saved trusted identity. Use a fresh QR code to repair pairing locally, or forget the trusted host on this phone."
                    ConnectionStatus.RECONNECT_REQUIRED ->
                        "Your previous trust still exists, but the phone identity or host trust needs repair before a clean reconnect."
                    ConnectionStatus.UPDATE_REQUIRED ->
                        "Reconnect from the saved pair after updating the older side of the bridge."
                    ConnectionStatus.DISCONNECTED ->
                        if (state.trustedPair?.hasSavedRelaySession == false) {
                            "The stale live session was cleared, but the trusted host is still known. Resolve a new live session without rescanning."
                        } else {
                            "Jump back into the remembered host first, then fall back to scanning or a payload only if trust changed."
                        }
                    else ->
                        "Jump back into the remembered host first, then fall back to scanning or a payload only if trust changed."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )

            state.trustedPair?.let { trustedPair ->
                TrustedPairSummary(trustedPair = trustedPair)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
            ) {
                RemodexPill(
                    label = state.reconnectButtonLabel,
                    style = reconnectStyle,
                )
                state.trustedPair?.relayLabel?.let {
                    RemodexPill(
                        label = it,
                        style = RemodexPillStyle.Neutral,
                    )
                }
            }

            RemodexButton(
                onClick = onPrimaryAction,
                enabled = state.reconnectEnabled,
                modifier = Modifier.fillMaxWidth(),
                style = RemodexButtonStyle.Secondary,
                contentPadding = PaddingValues(
                    horizontal = geometry.spacing20,
                    vertical = geometry.spacing14,
                ),
            ) {
                Text(
                    text = state.reconnectButtonLabel,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }

            if (state.trustedPair != null) {
                RemodexButton(
                    onClick = onForgetTrustedHost,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    style = RemodexButtonStyle.Secondary,
                    contentPadding = PaddingValues(
                        horizontal = geometry.spacing20,
                        vertical = geometry.spacing14,
                    ),
                ) {
                    Text(
                        text = "Forget Trusted Host",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrustedPairSummary(
    trustedPair: TrustedPairUiState,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = geometry.cornerLarge,
        tonalColor = colors.selectedRowFill.copy(alpha = 0.52f),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.spacing14,
                vertical = geometry.spacing14,
            ),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
        ) {
            Text(
                text = trustedPair.title.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.0.sp),
                color = colors.textSecondary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = CircleShape,
                    color = colors.textPrimary.copy(alpha = 0.06f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Computer,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(geometry.spacing2),
                ) {
                    Text(
                        text = trustedPair.name,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                    )
                    trustedPair.systemName?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = "\"$it\"",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textSecondary,
                        )
                    }
                    trustedPair.detail?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryCard(
    state: PairingScreenUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val noticeColor = when (state.connection.status) {
        ConnectionStatus.TRUST_BLOCKED,
        ConnectionStatus.RECONNECT_REQUIRED,
        ConnectionStatus.UPDATE_REQUIRED -> colors.errorRed
        ConnectionStatus.RETRYING_SAVED_PAIRING -> colors.accentOrange
        else -> colors.accentBlue
    }
    val noticeTone = when (state.connection.status) {
        ConnectionStatus.TRUST_BLOCKED,
        ConnectionStatus.RECONNECT_REQUIRED,
        ConnectionStatus.UPDATE_REQUIRED -> colors.errorRed.copy(alpha = 0.08f)
        ConnectionStatus.RETRYING_SAVED_PAIRING -> colors.accentOrange.copy(alpha = 0.08f)
        else -> colors.secondarySurface.copy(alpha = 0.72f)
    }

    RemodexGroupedSurface(
        modifier = modifier,
        cornerRadius = geometry.cornerComposer,
        tonalColor = noticeTone,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.sectionPadding,
                vertical = geometry.sectionPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing12),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = noticeColor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = state.recoveryTitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                )
            }

            Text(
                text = state.recoveryMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )

            recoveryFootnote(state.connection.status)?.let { footnote ->
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }

            state.compatibilityMessage?.let { message ->
                RemodexDivider()
                Text(
                    text = "Compatibility",
                    style = MaterialTheme.typography.labelLarge,
                    color = noticeColor,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun ManualPairingCard(
    state: PairingScreenUiState,
    onPairingInputChanged: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = modifier,
        cornerRadius = geometry.cornerComposer,
        tonalColor = colors.subtleGlassTint,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.sectionPadding,
                vertical = geometry.sectionPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing16),
        ) {
            Text(
                text = "MANUAL RECOVERY",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
                color = colors.textSecondary,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = colors.selectedRowFill.copy(alpha = 0.82f),
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing2)) {
                    Text(
                        text = "Paste a pairing payload",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Use this when scanning is unavailable or you already copied the bridge payload from the host terminal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }

            RemodexInputField(
                value = state.pairingInput,
                onValueChange = onPairingInputChanged,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                label = "Pairing payload",
                placeholder = "{\"v\":3,...}",
                mono = true,
            )

            RemodexButton(
                onClick = onConnect,
                enabled = state.pairingInput.isNotBlank() && !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = geometry.spacing20,
                    vertical = geometry.spacing14,
                ),
            ) {
                Text(
                    text = "Connect with payload",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@Composable
private fun PairingMetadataChip(label: String) {
    RemodexPill(label = label, style = RemodexPillStyle.Neutral)
}

private fun recoveryFootnote(status: ConnectionStatus): String? {
    return when (status) {
        ConnectionStatus.TRUST_BLOCKED ->
            "Use a fresh QR code to repair local pairing on this phone, or forget the trusted host to reset local trust explicitly."
        ConnectionStatus.RECONNECT_REQUIRED ->
            "If this host was paired to another mobile client, reset trust on the host before scanning a new QR code."
        ConnectionStatus.UPDATE_REQUIRED ->
            "Updating the older app or bridge keeps reconnect, runtime controls, and trust presentation aligned."
        else -> null
    }
}
