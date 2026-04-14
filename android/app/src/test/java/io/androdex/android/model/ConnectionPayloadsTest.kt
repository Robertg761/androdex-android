package io.androdex.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPayloadsTest {
    @Test
    fun parseConnectPayloadDescriptor_acceptsMacPairUrl() {
        val descriptor = parseConnectPayloadDescriptor(
            "https://mac.example.com/base/pair#token=pair_123",
        )

        assertTrue(descriptor is ConnectPayloadDescriptor.MacNative)
        val payload = (descriptor as ConnectPayloadDescriptor.MacNative).payload
        assertEquals(macNativePairingPayloadVersion, payload.version)
        assertEquals("https://mac.example.com/base", payload.httpBaseUrl)
        assertEquals("pair_123", payload.credential)
        assertEquals(MacNativePairingSource.PAIR_URL, payload.source)
    }

    @Test
    fun parseConnectPayloadDescriptor_acceptsMacPairUrlWithQueryToken() {
        val descriptor = parseConnectPayloadDescriptor(
            "https://mac.example.com/pair?token=pair_456",
        )

        assertTrue(descriptor is ConnectPayloadDescriptor.MacNative)
        val payload = (descriptor as ConnectPayloadDescriptor.MacNative).payload
        assertEquals("https://mac.example.com", payload.httpBaseUrl)
        assertEquals("pair_456", payload.credential)
        assertEquals(MacNativePairingSource.PAIR_URL, payload.source)
    }
}
