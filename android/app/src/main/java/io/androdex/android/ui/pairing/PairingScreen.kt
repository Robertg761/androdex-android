package io.androdex.android.ui.pairing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.androdex.android.ui.shared.StatusCapsule
import io.androdex.android.ui.state.PairingScreenUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairingScreen(
    state: PairingScreenUiState,
    onPairingInputChanged: (String) -> Unit,
    onScanQr: () -> Unit,
    onConnect: () -> Unit,
    onReconnectSaved: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Androdex", style = MaterialTheme.typography.titleLarge)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            StatusCapsule(state = state.connection)

            Spacer(modifier = Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Connect to your PC",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Run androdex pair once on your host to trust this phone, then reconnect from saved pairing later. Once connected, choose or switch projects directly from Android.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                state.defaultRelayUrl?.let { defaultRelayUrl ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            text = "Default relay: $defaultRelayUrl",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = state.pairingInput,
                        onValueChange = onPairingInputChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        label = { Text("Pairing payload") },
                        placeholder = { Text("{\"v\":3,...}") },
                        shape = MaterialTheme.shapes.medium,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = onScanQr,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.QrCode2,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan QR")
                        }
                        Button(
                            onClick = onConnect,
                            enabled = !state.isBusy,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text("Connect")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(visible = state.hasSavedPairing) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 40.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FilledTonalButton(
                        onClick = onReconnectSaved,
                        enabled = state.reconnectEnabled,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(state.reconnectButtonLabel)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = state.isBusy,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
