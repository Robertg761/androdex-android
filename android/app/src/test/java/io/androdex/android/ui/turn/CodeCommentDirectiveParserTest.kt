package io.androdex.android.ui.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeCommentDirectiveParserTest {
    @Test
    fun parse_extractsFindingsAndLeavesFallbackText() {
        val parsed = CodeCommentDirectiveParser.parse(
            """
            Review complete.

            ::code-comment{title="[P1] Missing null check" body="This crashes when the response is empty." file="/tmp/app.kt" start=10 end=12 confidence=0.9}
            ::code-comment{title="Use explicit branch" body="Defaulting silently can review the wrong diff." file="/tmp/MainViewModel.kt" start=44}
            """.trimIndent()
        )

        assertEquals(2, parsed.findings.size)
        assertEquals("Missing null check", parsed.findings.first().title)
        assertEquals(1, parsed.findings.first().priority)
        assertEquals("/tmp/app.kt", parsed.findings.first().file)
        assertEquals("Review complete.", parsed.fallbackText)
    }

    @Test
    fun parse_returnsOriginalTextWhenNoDirectivesExist() {
        val parsed = CodeCommentDirectiveParser.parse("Plain assistant response")

        assertTrue(parsed.findings.isEmpty())
        assertEquals("Plain assistant response", parsed.fallbackText)
    }
}
