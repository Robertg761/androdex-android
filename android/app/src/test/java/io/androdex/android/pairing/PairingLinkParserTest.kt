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
    fun parsesDesktopQrDeepLinkWithHashToken() {
        val link = parsePairingLink(
            "androdex://pair?payload=https%3A%2F%2Frelay.androdex.xyz%2Fdesktop%2Froute-123%2Fpair%23token%3DPAIRCODE",
        )

        assertNotNull(link)
        assertEquals("https://relay.androdex.xyz", link?.origin)
        assertEquals(
            "https://relay.androdex.xyz/desktop/route-123/pair#token=PAIRCODE",
            link?.pairingUrl,
        )
    }

    @Test
    fun parsesMacNativeJsonPayloadAsMirrorPairingUrl() {
        val link = parsePairingLink(
            """{"v":1,"transport":"mac-native","httpBaseUrl":"https://mac.example.com/base","credential":"pair_789"}""",
        )

        assertNotNull(link)
        assertEquals("https://mac.example.com", link?.origin)
        assertEquals("https://mac.example.com/base/pair?token=pair_789", link?.pairingUrl)
    }

    @Test
    fun parsesWrappedMacNativeJsonPayloadAsMirrorPairingUrl() {
        val link = parsePairingLink(
            "androdex://pair?payload=%7B%22v%22%3A1%2C%22transport%22%3A%22mac-native%22%2C%22httpBaseUrl%22%3A%22https%3A%2F%2Fmac.example.com%22%2C%22credential%22%3A%22pair_456%22%7D",
        )

        assertNotNull(link)
        assertEquals("https://mac.example.com", link?.origin)
        assertEquals("https://mac.example.com/pair?token=pair_456", link?.pairingUrl)
    }

    @Test
    fun extractsWrapperPayloadWithoutForcingMirrorPairUrl() {
        val payload = """{"v":3,"relay":"wss://relay.example.com/relay"}"""

        assertEquals(
            payload,
            extractPairingPayloadFromUriString(
                "androdex://pair?payload=%7B%22v%22%3A3%2C%22relay%22%3A%22wss%3A%2F%2Frelay.example.com%2Frelay%22%7D",
            ),
        )
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
