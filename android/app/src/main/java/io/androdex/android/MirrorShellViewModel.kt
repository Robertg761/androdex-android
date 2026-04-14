package io.androdex.android

import android.content.Intent
import androidx.lifecycle.ViewModel
import io.androdex.android.notifications.decodeNotificationOpenPayload
import io.androdex.android.pairing.PairingLink
import io.androdex.android.pairing.isAllowedAppUrl
import io.androdex.android.pairing.parsePairingLink
import io.androdex.android.pairing.shouldPersistAppUrl
import io.androdex.android.persistence.MirrorShellSnapshot
import io.androdex.android.persistence.MirrorShellStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MirrorShellUiState(
    val pairedOrigin: String? = null,
    val displayLabel: String? = null,
    val initialUrl: String? = null,
    val webViewReady: Boolean = false,
    val externalOpenPending: String? = null,
    val pairingInput: String = "",
    val pairingError: String? = null,
)

class MirrorShellViewModel(
    private val store: MirrorShellStore,
) : ViewModel() {
    private val uiStateFlow = MutableStateFlow(store.loadSnapshot().toUiState())
    val uiState: StateFlow<MirrorShellUiState> = uiStateFlow.asStateFlow()

    fun consumeIntent(intent: Intent?) {
        if (intent == null) {
            return
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        val dataString = intent.dataString?.trim()
        val extras = intent.extras?.keySet().orEmpty().associateWith { key ->
            intent.extras?.getString(key)
        }

        listOfNotNull(sharedText, dataString).forEach { candidate ->
            if (consumeExternalValue(candidate)) {
                return
            }
        }

        if (decodeNotificationOpenPayload(extras) != null) {
            val reopenUrl = uiStateFlow.value.initialUrl ?: uiStateFlow.value.pairedOrigin ?: return
            uiStateFlow.update { it.copy(externalOpenPending = reopenUrl) }
        }
    }

    fun updatePairingInput(value: String) {
        uiStateFlow.update {
            it.copy(
                pairingInput = value,
                pairingError = null,
            )
        }
    }

    fun submitPairingInput() {
        val rawInput = uiStateFlow.value.pairingInput
        val pairingLink = parsePairingLink(rawInput)
        if (pairingLink == null) {
            uiStateFlow.update {
                it.copy(pairingError = "Enter a valid desktop pairing link ending in `/pair` with a token.")
            }
            return
        }

        applyPairingLink(pairingLink)
    }

    fun onWebViewReady() {
        uiStateFlow.update { current ->
            if (current.webViewReady) current else current.copy(webViewReady = true)
        }
    }

    fun onTopLevelUrlChanged(url: String) {
        val pairedOrigin = uiStateFlow.value.pairedOrigin ?: return
        if (!isAllowedAppUrl(url, pairedOrigin)) {
            return
        }

        if (shouldPersistAppUrl(url, pairedOrigin)) {
            store.saveLastOpenedUrl(url)
            uiStateFlow.update { it.copy(initialUrl = url) }
        }
    }

    fun markExternalOpenHandled() {
        uiStateFlow.update { current ->
            if (current.externalOpenPending == null) current else current.copy(externalOpenPending = null)
        }
    }

    fun clearPairing() {
        store.clear()
        uiStateFlow.value = MirrorShellUiState()
    }

    private fun consumeExternalValue(rawValue: String): Boolean {
        parsePairingLink(rawValue)?.let { pairingLink ->
            applyPairingLink(pairingLink)
            return true
        }

        val pairedOrigin = uiStateFlow.value.pairedOrigin ?: return false
        if (!isAllowedAppUrl(rawValue, pairedOrigin)) {
            return false
        }

        uiStateFlow.update { it.copy(externalOpenPending = rawValue) }
        return true
    }

    private fun applyPairingLink(pairingLink: PairingLink) {
        val hadPairedOrigin = uiStateFlow.value.pairedOrigin != null
        store.savePairing(
            origin = pairingLink.origin,
            displayLabel = pairingLink.displayLabel,
            bootstrapPairingUrl = pairingLink.pairingUrl,
        )
        store.saveLastOpenedUrl(null)
        uiStateFlow.update {
            it.copy(
                pairedOrigin = pairingLink.origin,
                displayLabel = pairingLink.displayLabel,
                initialUrl = pairingLink.pairingUrl,
                webViewReady = hadPairedOrigin && it.webViewReady,
                externalOpenPending = if (hadPairedOrigin) pairingLink.pairingUrl else null,
                pairingInput = pairingLink.pairingUrl,
                pairingError = null,
            )
        }
    }
}

private fun MirrorShellSnapshot.toUiState(): MirrorShellUiState {
    val initialUrl = resolveInitialUrl()
    return MirrorShellUiState(
        pairedOrigin = pairedOrigin,
        displayLabel = displayLabel,
        initialUrl = initialUrl,
        pairingInput = pairedOrigin.orEmpty(),
    )
}

internal fun MirrorShellSnapshot.resolveInitialUrl(): String? {
    return lastOpenedUrl ?: bootstrapPairingUrl ?: pairedOrigin
}
