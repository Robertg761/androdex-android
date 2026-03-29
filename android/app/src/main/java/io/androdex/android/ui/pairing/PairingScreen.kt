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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.androdex.android.R
import io.androdex.android.ui.shared.HostAccountCard
import io.androdex.android.ui.shared.LandingBackdrop
import io.androdex.android.ui.shared.LandingSectionSurface
import io.androdex.android.ui.shared.StatusCapsule
import io.androdex.android.ui.shared.TrustedPairCard
import io.androdex.android.ui.state.PairingScreenUiState

@Composable
internal fun PairingScreen(
    state: PairingScreenUiState,
    onPairingInputChanged: (String) -> Unit,
    onScanQr: () -> Unit,
    onConnect: () -> Unit,
    onReconnectSaved: () -> Unit,
) {
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
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
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
    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(76.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                        contentDescription = "Androdex",
                        modifier = Modifier.size(76.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Androdex",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = "Pair once, then keep Codex running on your host while Android stays the remote control.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(9.dp)
                            .size(16.dp),
                    )
                }
                Text(
                    text = "End-to-end encrypted pairing with relay-compatible reconnects",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.isBusy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onScanQr,
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode2,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Scan QR",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Button(
                    onClick = onReconnectSaved,
                    enabled = state.hasSavedPairing && state.reconnectEnabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Text(
                        text = if (state.hasSavedPairing) state.reconnectButtonLabel else "Saved reconnect unavailable",
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (state.hasSavedPairing || state.defaultRelayUrl != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = state.recoveryTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = state.recoveryMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.compatibilityMessage?.let { message ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.hasSavedPairing) {
                Button(
                    onClick = onReconnectSaved,
                    enabled = state.reconnectEnabled,
                    shape = RoundedCornerShape(14.dp),
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
    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(9.dp)
                            .size(16.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Manual pairing payload",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = "Paste a payload only if you are not scanning a QR code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = state.pairingInput,
                onValueChange = onPairingInputChanged,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                label = { Text("Pairing payload") },
                placeholder = { Text("{\"v\":3,...}") },
                shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            )

            Button(
                onClick = onConnect,
                enabled = state.pairingInput.isNotBlank() && !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Connect with payload")
            }
        }
    }
}

@Composable
private fun StatusChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
