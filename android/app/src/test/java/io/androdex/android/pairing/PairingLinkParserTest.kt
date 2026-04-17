package io.androdex.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingLinkParserTest {
    @Test
    fun parsesDirectPairingUrl() {
        val link = parsePairingLink("https://mac.example.com:8443/pair?token=pair_123")

        assertNotNull(link)
        assertEquals("https://mac.example.com:8443", link?.origin)
        assertEquals("mac.example.com", link?.displayLabel)
    }

    @Test
    fun parsesAndrodexWrapperUrl() {
        val link = parsePairingLink(
            "androdex://pair?payload=https%3A%2F%2Fmac.example.com%2Fpair%3Ftoken%3Dpair_456",
        )

        assertNotNull(link)
        assertEquals("https://mac.example.com", link?.origin)
        assertEquals("https://mac.example.com/pair?token=pair_456", link?.pairingUrl)
    }

    @Test
    fun rejectsNonPairUrls() {
        assertNull(parsePairingLink("https://mac.example.com/settings"))
        assertNull(parsePairingLink("not a url"))
    }

    @Test
    fun sameOriginValidationUsesExactOrigin() {
        assertTrue(isAllowedAppUrl("https://mac.example.com/chat/env/thread", "https://mac.example.com"))
        assertFalse(isAllowedAppUrl("https://other.example.com/chat/env/thread", "https://mac.example.com"))
    }

    @Test
    fun persistenceSkipsPairingScreens() {
        assertFalse(shouldPersistAppUrl("https://mac.example.com/pair?token=pair_123", "https://mac.example.com"))
        assertTrue(shouldPersistAppUrl("https://mac.example.com/chat/env/thread", "https://mac.example.com"))
    }
}
