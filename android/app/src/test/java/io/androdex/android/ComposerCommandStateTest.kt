package io.androdex.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerCommandStateTest {
    @Test
    fun trailingSlashCommandToken_parsesTrailingSubagentsCommand() {
        val token = trailingSlashCommandToken("compare /subagents")

        assertEquals("subagents", token?.query)
    }

    @Test
    fun trailingSlashCommandToken_ignoresEmbeddedSlashText() {
        assertNull(trailingSlashCommandToken("C:/Projects/Androdex"))
    }

    @Test
    fun removingTrailingSlashCommandToken_removesOnlyTrailingToken() {
        assertEquals("compare", removingTrailingSlashCommandToken("compare /subagents"))
    }

    @Test
    fun applyingSubagentsSelection_prefixesCannedPrompt() {
        assertEquals(
            "$subagentsCannedPrompt\n\nInspect the open PR",
            applyingSubagentsSelection("Inspect the open PR", isSelected = true),
        )
    }
}
