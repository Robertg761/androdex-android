package io.androdex.android.transport.macnative

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MacNativeUrlResolverTest {
    @Test
    fun normalizeHttpBaseUrl_trimsPathAndQuery() {
        assertEquals(
            "https://example.com/base/path",
            normalizeMacNativeHttpBaseUrl(" https://Example.com/base/path/?token=ignored#fragment "),
        )
    }

    @Test
    fun resolveWebSocketBaseUrl_mapsHttpsToWss() {
        assertEquals(
            "wss://example.com/base/path",
            resolveMacNativeWebSocketBaseUrl("https://example.com/base/path"),
        )
    }

    @Test
    fun createServerTarget_derivesBothBaseUrls() {
        assertEquals(
            MacNativeServerTarget(
                httpBaseUrl = "http://localhost:4318",
                wsBaseUrl = "ws://localhost:4318",
            ),
            createMacNativeServerTarget("http://localhost:4318/"),
        )
    }

    @Test
    fun normalizeHttpBaseUrl_rejectsUnsupportedScheme() {
        try {
            normalizeMacNativeHttpBaseUrl("ftp://example.com")
            fail("Expected unsupported scheme to throw.")
        } catch (expected: IllegalArgumentException) {
            assertEquals("HTTP base URL must use http or https.", expected.message)
        }
    }
}
