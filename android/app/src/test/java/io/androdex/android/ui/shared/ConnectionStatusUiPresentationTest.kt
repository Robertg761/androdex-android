package io.androdex.android.ui.shared

import io.androdex.android.model.ConnectionStatus
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
}
