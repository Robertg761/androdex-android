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
    fun resolveHttpEndpointUrl_preservesBasePaths() {
        assertEquals(
            "https://example.com/base/path/api/auth/session",
            resolveMacNativeHttpEndpointUrl(
                httpBaseUrl = "https://example.com/base/path",
                endpointPath = "/api/auth/session",
            ),
        )
    }

    @Test
    fun macNativeSocketUrl_appendsWsPathAndTokenForWebSocketSchemes() {
        assertEquals(
            "ws://localhost:4318/ws?wsToken=token-123",
            macNativeSocketUrl(
                wsBaseUrl = "ws://localhost:4318",
                token = "token-123",
            ),
        )
        assertEquals(
            "wss://example.com/base/path/ws?wsToken=token-456",
            macNativeSocketUrl(
                wsBaseUrl = "wss://example.com/base/path",
                token = "token-456",
            ),
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
