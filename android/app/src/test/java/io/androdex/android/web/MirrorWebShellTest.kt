package io.androdex.android.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorWebShellTest {
    @Test
    fun parseBodyTextFromJsResultDecodesJavascriptStringLiteral() {
        val rawResult = "\"Toggle Sidebar\\nNo active thread\\nPick a thread to continue\""

        assertEquals(
            "Toggle Sidebar\nNo active thread\nPick a thread to continue",
            parseBodyTextFromJsResult(rawResult),
        )
    }

    @Test
    fun parseBodyTextFromJsResultReturnsEmptyStringForNullLiteral() {
        assertEquals("", parseBodyTextFromJsResult("null"))
    }

    @Test
    fun parseJsBooleanResultRecognizesTrueLiteral() {
        assertTrue(parseJsBooleanResult("true"))
    }

    @Test
    fun parseJsBooleanResultRejectsNonTrueValues() {
        assertEquals(false, parseJsBooleanResult("false"))
        assertEquals(false, parseJsBooleanResult("\"true\""))
        assertEquals(false, parseJsBooleanResult(null))
    }

    @Test
    fun parseJsOptionalBooleanResultRecognizesBooleanLiterals() {
        assertEquals(true, parseJsOptionalBooleanResult("true"))
        assertEquals(false, parseJsOptionalBooleanResult("false"))
        assertEquals(null, parseJsOptionalBooleanResult("null"))
    }

    @Test
    fun classifyMirrorNavigationTreatsSameOriginUrlsAsInApp() {
        assertEquals(
            MirrorNavigationTarget.InApp,
            classifyMirrorNavigation(
                url = "https://host.example/threads/thread-123",
                pairedOrigin = "https://host.example",
            ),
        )
    }

    @Test
    fun classifyMirrorNavigationTreatsExternalUrlsAsExternal() {
        assertEquals(
            MirrorNavigationTarget.External,
            classifyMirrorNavigation(
                url = "https://example.org/docs",
                pairedOrigin = "https://host.example",
            ),
        )
    }

    @Test
    fun classifyMirrorNavigationIgnoresPopupPlaceholderUrls() {
        assertEquals(
            MirrorNavigationTarget.Ignore,
            classifyMirrorNavigation(
                url = "about:blank",
                pairedOrigin = "https://host.example",
            ),
        )
    }

    @Test
    fun normalizeMirrorNavigationUrlTrimsUsableValues() {
        assertEquals(
            "https://host.example/thread/new",
            normalizeMirrorNavigationUrl("  https://host.example/thread/new  "),
        )
    }

    @Test
    fun resolveMirrorPageFinishedStateClearsStaleTimeoutError() {
        assertEquals(
            MirrorPageFinishedState(
                activeLoadUrl = "https://host.example/threads/thread-123",
                isLoading = false,
                loadError = null,
            ),
            resolveMirrorPageFinishedState(
                currentActiveLoadUrl = "https://host.example",
                finishedUrl = "https://host.example/threads/thread-123",
            ),
        )
    }

    @Test
    fun resolveMirrorPageFinishedStateKeepsPreviousUrlWhenFinishedUrlMissing() {
        assertEquals(
            MirrorPageFinishedState(
                activeLoadUrl = "https://host.example/threads/thread-123",
                isLoading = false,
                loadError = null,
            ),
            resolveMirrorPageFinishedState(
                currentActiveLoadUrl = "https://host.example/threads/thread-123",
                finishedUrl = null,
            ),
        )
    }

    @Test
    fun androidThreadTapBridgeScriptPinsHeaderAndRepairsLayout() {
        val script = androidThreadTapBridgeScript()

        assertTrue(script.contains("const pinPrimaryHeader = () => {"))
        assertTrue(script.contains("position\", \"sticky\""))
        assertTrue(script.contains("scroll-padding-top"))
        assertTrue(script.contains("MutationObserver"))
        assertTrue(script.contains("if (!event.isTrusted)"))
        assertTrue(script.contains("const bindSidebarInteractions = () => {"))
        assertTrue(script.contains("closeSidebarAfterAction(220)"))
        assertTrue(script.contains("const isSidebarOpen = () => {"))
        assertTrue(script.contains("const attemptSidebarClose = () => {"))
        assertTrue(script.contains("const getToggleSidebarButtons = () => {"))
        assertTrue(script.contains("[\"pointerdown\", \"mousedown\", \"pointerup\", \"mouseup\", \"click\"]"))
        assertTrue(script.contains("const dispatchEscape = () => {"))
        assertTrue(script.contains("const pressOutsideSidebar = () => {"))
        assertTrue(script.contains("const requestSidebarClose = (delayMs, holdMs) => {"))
        assertTrue(script.contains("window.__androdexAndroidRequestSidebarClose = () => {"))
        assertTrue(script.contains("[data-androdex-role=\"thread-header\"]"))
        assertTrue(script.contains("[data-androdex-role=\"thread-shell\"]"))
        assertTrue(script.contains("[data-androdex-role=\"thread-row\"]"))
        assertTrue(script.contains("[data-androdex-role=\"create-thread\"]"))
    }
}
