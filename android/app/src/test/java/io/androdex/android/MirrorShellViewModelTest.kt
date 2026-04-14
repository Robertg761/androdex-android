package io.androdex.android

import io.androdex.android.persistence.MirrorShellSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorShellViewModelTest {
    @Test
    fun resolveInitialUrlPrefersLastOpenedUrl() {
        val snapshot = MirrorShellSnapshot(
            pairedOrigin = "https://mac.example.com",
            displayLabel = "mac.example.com",
            bootstrapPairingUrl = "https://mac.example.com/pair?token=pair_123",
            lastOpenedUrl = "https://mac.example.com/chat/thread_123",
        )

        assertEquals("https://mac.example.com/chat/thread_123", snapshot.resolveInitialUrl())
    }

    @Test
    fun resolveInitialUrlFallsBackToBootstrapPairingUrl() {
        val snapshot = MirrorShellSnapshot(
            pairedOrigin = "https://mac.example.com",
            displayLabel = "mac.example.com",
            bootstrapPairingUrl = "https://mac.example.com/pair?token=pair_123",
            lastOpenedUrl = null,
        )

        assertEquals("https://mac.example.com/pair?token=pair_123", snapshot.resolveInitialUrl())
    }

    @Test
    fun resolveInitialUrlFallsBackToPairedOriginWhenNeeded() {
        val snapshot = MirrorShellSnapshot(
            pairedOrigin = "https://mac.example.com",
            displayLabel = "mac.example.com",
            bootstrapPairingUrl = null,
            lastOpenedUrl = null,
        )

        assertEquals("https://mac.example.com", snapshot.resolveInitialUrl())
    }
}
