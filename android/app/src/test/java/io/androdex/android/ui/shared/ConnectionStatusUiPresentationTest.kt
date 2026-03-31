package io.androdex.android.ui.shared

import io.androdex.android.model.ConnectionStatus
import io.androdex.android.ui.state.ConnectionBannerOverrideUiState
import io.androdex.android.ui.state.SharedStatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatusUiPresentationTest {
    @Test
    fun retryingSavedPairingUsesAccentRetryPresentation() {
        val presentation = connectionBannerPresentation(
            status = ConnectionStatus.RETRYING_SAVED_PAIRING,
            detail = "Waiting for host",
        )

        assertEquals("Waiting for trusted host", presentation.title)
        assertEquals("Retrying", presentation.badgeLabel)
        assertEquals(SharedStatusTone.Accent, presentation.tone)
        assertTrue(presentation.guidance?.contains("retrying automatically") == true)
    }

    @Test
    fun updateRequiredUsesWarningUpdatePresentation() {
        val presentation = connectionBannerPresentation(
            status = ConnectionStatus.UPDATE_REQUIRED,
            detail = "Bridge and mobile client are not using the same secure transport version.",
        )

        assertEquals("Host and Android are out of sync", presentation.title)
        assertEquals("Update", presentation.badgeLabel)
        assertEquals(SharedStatusTone.Warning, presentation.tone)
        assertTrue(presentation.guidance?.contains("out of sync") == true)
    }

    @Test
    fun overridePresentation_replacesRetryingSavedPairingCopy() {
        val presentation = connectionBannerPresentation(
            status = ConnectionStatus.RETRYING_SAVED_PAIRING,
            detail = "Waiting for host",
            overridePresentation = ConnectionBannerOverrideUiState(
                title = "Fresh pairing ready",
                badgeLabel = "Scanner",
                tone = SharedStatusTone.Accent,
                guidance = "Saved reconnect is paused during this handoff.",
            ),
        )

        assertEquals("Fresh pairing ready", presentation.title)
        assertEquals("Scanner", presentation.badgeLabel)
        assertEquals(SharedStatusTone.Accent, presentation.tone)
        assertTrue(presentation.guidance?.contains("paused") == true)
    }
}
