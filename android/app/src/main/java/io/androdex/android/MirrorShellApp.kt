package io.androdex.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.androdex.android.pairing.MirrorPairingScreen
import io.androdex.android.web.MirrorWebShell

@Composable
fun MirrorShellApp(viewModel: MirrorShellViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(viewModel::updatePairingInput)
        if (!result.contents.isNullOrBlank()) {
            viewModel.submitPairingInput()
        }
    }

    if (uiState.pairedOrigin == null) {
        MirrorPairingScreen(
            pairingInput = uiState.pairingInput,
            pairingError = uiState.pairingError,
            onPairingInputChange = viewModel::updatePairingInput,
            onSubmitPairing = viewModel::submitPairingInput,
            onScanPairing = {
                scanLauncher.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan the Androdex desktop pairing QR")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            },
        )
    } else {
        MirrorWebShell(
            state = uiState,
            onWebViewReady = viewModel::onWebViewReady,
            onTopLevelUrlChanged = viewModel::onTopLevelUrlChanged,
            onExternalOpenHandled = viewModel::markExternalOpenHandled,
            onClearPairing = viewModel::clearPairing,
        )
    }
}
