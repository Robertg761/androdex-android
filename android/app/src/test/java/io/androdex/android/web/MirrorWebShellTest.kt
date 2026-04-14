package io.androdex.android.web

import org.junit.Assert.assertEquals
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
}
