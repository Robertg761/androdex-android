package io.androdex.android.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun resolveMirrorLoadTargetPrefersExplicitExternalOpen() {
        assertEquals(
            "https://host.example/thread/next",
            resolveMirrorLoadTarget(
                externalOpenPending = "https://host.example/thread/next",
                activeLoadUrl = "https://host.example/thread/current",
                initialUrl = "https://host.example",
                pairedOrigin = "https://host.example",
            ),
        )
    }

    @Test
    fun resolveMirrorLoadTargetFallsBackToActiveUrlBeforePersistedInitialUrl() {
        assertEquals(
            "https://host.example/thread/current",
            resolveMirrorLoadTarget(
                externalOpenPending = null,
                activeLoadUrl = "https://host.example/thread/current",
                initialUrl = "https://host.example/thread/stale",
                pairedOrigin = "https://host.example",
            ),
        )
    }

    @Test
    fun parseMirrorDocumentSnapshotDecodesJsonPayload() {
        val snapshot = parseMirrorDocumentSnapshot(
            "\"{\\\"title\\\":\\\"Androdex\\\",\\\"url\\\":\\\"https://host.example\\\",\\\"bodyText\\\":\\\"Ready\\\",\\\"bodyChildCount\\\":3,\\\"imageCount\\\":0,\\\"visibleMediaCount\\\":1}\"",
        )

        assertEquals(
            MirrorDocumentSnapshot(
                title = "Androdex",
                url = "https://host.example",
                bodyText = "Ready",
                bodyChildCount = 3,
                imageCount = 0,
                visibleMediaCount = 1,
            ),
            snapshot,
        )
    }

    @Test
    fun classifyMirrorDocumentFailureFlagsBlankDocuments() {
        val error = classifyMirrorDocumentFailure(
            snapshot = MirrorDocumentSnapshot(
                title = "",
                url = "https://host.example/pair",
                bodyText = "",
                bodyChildCount = 1,
                imageCount = 1,
                visibleMediaCount = 1,
            ),
            fallbackUrl = "https://host.example/pair",
        )

        assertNotNull(error)
        assertEquals(MirrorLoadFailureKind.BlankPage, error?.kind)
    }

    @Test
    fun classifyMirrorDocumentFailureFlagsBrowserErrorText() {
        val error = classifyMirrorDocumentFailure(
            snapshot = MirrorDocumentSnapshot(
                title = "Error",
                url = "https://host.example/pair",
                bodyText = "This site can't be reached. net::ERR_CONNECTION_RESET",
                bodyChildCount = 4,
                imageCount = 0,
                visibleMediaCount = 0,
            ),
            fallbackUrl = "https://host.example/pair",
        )

        assertNotNull(error)
        assertEquals(MirrorLoadFailureKind.BrowserErrorPage, error?.kind)
    }

    @Test
    fun classifyMirrorDocumentFailureAllowsHealthyContent() {
        val error = classifyMirrorDocumentFailure(
            snapshot = MirrorDocumentSnapshot(
                title = "Androdex",
                url = "https://host.example/thread/1",
                bodyText = "Thread title Recent messages",
                bodyChildCount = 8,
                imageCount = 0,
                visibleMediaCount = 0,
            ),
            fallbackUrl = "https://host.example/thread/1",
        )

        assertNull(error)
    }

    @Test
    fun httpLoadErrorExplainsForbiddenSessions() {
        val error = httpLoadError(
            requestUrl = "https://host.example/thread/1",
            statusCode = 403,
            reason = "Forbidden",
        )

        assertEquals(MirrorLoadFailureKind.Http, error.kind)
        assertTrue(error.summary.contains("saved pairing"))
        assertEquals("HTTP 403 (Forbidden)", error.technicalDetails)
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
        assertTrue(script.contains("const forceSidebarProjectCreateButtonsVisible = () => {"))
        assertTrue(script.contains("button.style.setProperty(\"touch-action\", \"manipulation\", \"important\")"))
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
