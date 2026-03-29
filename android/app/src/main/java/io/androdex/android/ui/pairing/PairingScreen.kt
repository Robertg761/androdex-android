package io.androdex.android.ui.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.outlined.Lock
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.androdex.android.R
import io.androdex.android.ui.shared.HostAccountCard
import io.androdex.android.ui.shared.LandingBackdrop
import io.androdex.android.ui.shared.LandingSectionSurface
import io.androdex.android.ui.shared.RemodexButton
import io.androdex.android.ui.shared.RemodexButtonStyle
import io.androdex.android.ui.shared.RemodexDivider
import io.androdex.android.ui.shared.RemodexInputField
import io.androdex.android.ui.shared.RemodexPill
import io.androdex.android.ui.shared.RemodexPillStyle
import io.androdex.android.ui.shared.StatusCapsule
import io.androdex.android.ui.shared.TrustedPairCard
import io.androdex.android.ui.state.PairingScreenUiState
import io.androdex.android.ui.theme.RemodexTheme

@Composable
internal fun PairingScreen(
    state: PairingScreenUiState,
    onPairingInputChanged: (String) -> Unit,
    onScanQr: () -> Unit,
    onConnect: () -> Unit,
    onReconnectSaved: () -> Unit,
) {
    val geometry = RemodexTheme.geometry

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
                verticalArrangement = Arrangement.spacedBy(geometry.spacing16),
            ) {
                PairingHeroCard(
                    state = state,
                    onScanQr = onScanQr,
                    onReconnectSaved = onReconnectSaved,
                )

                StatusCapsule(state = state.connection)

                RecoveryCard(
                    state = state,
                    onReconnectSaved = onReconnectSaved,
                )

                if (state.trustedPair != null) {
                    TrustedPairCard(state = state.trustedPair)
                }

                if (state.hostAccount != null) {
                    HostAccountCard(state = state.hostAccount)
                }

                ManualPairingCard(
                    state = state,
                    onPairingInputChanged = onPairingInputChanged,
                    onConnect = onConnect,
                )
            }
        }
    }
}

@Composable
private fun PairingHeroCard(
    state: PairingScreenUiState,
    onScanQr: () -> Unit,
    onReconnectSaved: () -> Unit,
) {
    val geometry = RemodexTheme.geometry

    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.pageHorizontalPadding,
                vertical = geometry.pageVerticalPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing18),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing14),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(76.dp),
                    shape = RoundedCornerShape(RemodexTheme.geometry.cornerXLarge),
                    color = RemodexTheme.colors.selectedRowFill,
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                        contentDescription = "Androdex",
                        modifier = Modifier.size(76.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing6)) {
                    Text(
                        text = "Androdex",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = RemodexTheme.colors.textPrimary,
                    )
                    Text(
                        text = "Pair once, then keep Codex running on your host while Android stays the remote control.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RemodexTheme.colors.textSecondary,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = RemodexTheme.colors.accentBlue.copy(alpha = 0.14f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = RemodexTheme.colors.accentBlue,
                        modifier = Modifier
                            .padding(geometry.spacing8)
                            .size(geometry.spacing16),
                    )
                }
                Text(
                    text = "End-to-end encrypted pairing with relay-compatible reconnects",
                    style = MaterialTheme.typography.bodySmall,
                    color = RemodexTheme.colors.textSecondary,
                )
            }

            if (state.isBusy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = RemodexTheme.colors.accentBlue,
                    trackColor = RemodexTheme.colors.selectedRowFill,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
            ) {
                RemodexButton(
                    onClick = onScanQr,
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode2,
                        contentDescription = null,
                        modifier = Modifier.size(geometry.iconSize),
                    )
                    Text(
                        text = "Scan QR",
                        modifier = Modifier.padding(start = geometry.spacing8),
                    )
                }
                RemodexButton(
                    onClick = onReconnectSaved,
                    enabled = state.hasSavedPairing && state.reconnectEnabled,
                    modifier = Modifier.weight(1f),
                    style = RemodexButtonStyle.Secondary,
                ) {
                    Text(
                        text = if (state.hasSavedPairing) state.reconnectButtonLabel else "Saved reconnect unavailable",
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (state.hasSavedPairing || state.defaultRelayUrl != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.hasSavedPairing) {
                        StatusChip(label = "Saved pair")
                    }
                    if (state.defaultRelayUrl != null) {
                        StatusChip(label = "Relay-ready")
                    }
                    StatusChip(label = "Host-local runtime")
                }
            }
        }
    }
}

@Composable
private fun RecoveryCard(
    state: PairingScreenUiState,
    onReconnectSaved: () -> Unit,
) {
    val geometry = RemodexTheme.geometry

    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.sectionPadding, vertical = geometry.sectionPadding),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
        ) {
            Text(
                text = state.recoveryTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = RemodexTheme.colors.textPrimary,
            )
            Text(
                text = state.recoveryMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = RemodexTheme.colors.textSecondary,
            )
            state.compatibilityMessage?.let { message ->
                RemodexDivider()
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = RemodexTheme.colors.accentBlue,
                )
            }
            if (state.hasSavedPairing) {
                RemodexButton(
                    onClick = onReconnectSaved,
                    enabled = state.reconnectEnabled,
                ) {
                    Text(state.reconnectButtonLabel)
                }
            }
        }
    }
}

@Composable
private fun ManualPairingCard(
    state: PairingScreenUiState,
    onPairingInputChanged: (String) -> Unit,
    onConnect: () -> Unit,
) {
    val geometry = RemodexTheme.geometry

    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.sectionPadding, vertical = geometry.sectionPadding),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing12),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = RemodexTheme.colors.selectedRowFill,
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = RemodexTheme.colors.textPrimary,
                        modifier = Modifier
                            .padding(geometry.spacing8)
                            .size(geometry.spacing16),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing2)) {
                    Text(
                        text = "Manual pairing payload",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = RemodexTheme.colors.textPrimary,
                    )
                    Text(
                        text = "Paste a payload only if you are not scanning a QR code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RemodexTheme.colors.textSecondary,
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
            ) {
                Text("Connect with payload")
            }
        }
    }
}

@Composable
private fun StatusChip(label: String) {
    RemodexPill(label = label, style = RemodexPillStyle.Neutral)
}
