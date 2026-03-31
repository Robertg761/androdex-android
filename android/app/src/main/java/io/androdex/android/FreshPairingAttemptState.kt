package io.androdex.android

enum class FreshPairingStage {
    SCANNER_OPEN,
    PAYLOAD_CAPTURED,
    CONNECTING,
}

data class FreshPairingAttemptState(
    val stage: FreshPairingStage,
    val shouldResumeSavedReconnectOnCancel: Boolean = false,
)
