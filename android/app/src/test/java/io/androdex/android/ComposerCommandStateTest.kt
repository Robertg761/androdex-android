package io.androdex.android

import io.androdex.android.model.ComposerMentionedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun trailingFileAutocompleteToken_allowsPathLikeQueriesWithSpaces() {
        val token = trailingFileAutocompleteToken("Inspect @src/ui Main")

        assertEquals("src/ui Main", token?.query)
    }

    @Test
    fun trailingSkillAutocompleteToken_rejectsNumericQueries() {
        assertNull(trailingSkillAutocompleteToken("Use $42"))
    }

    @Test
    fun replacingFileMentionAliases_canonicalizesSelectedFilePath() {
        val mention = ComposerMentionedFile(
            fileName = "MainViewModel.kt",
            path = "android/app/src/main/java/io/androdex/android/MainViewModel.kt",
        )

        assertEquals(
            "Inspect @android/app/src/main/java/io/androdex/android/MainViewModel.kt next",
            replacingFileMentionAliases(
                text = "Inspect @MainViewModel next",
                mention = mention,
            ),
        )
    }

    @Test
    fun removingFileMentionAliases_removesMentionTokenFromComposerText() {
        val mention = ComposerMentionedFile(
            fileName = "MainViewModel.kt",
            path = "android/app/src/main/java/io/androdex/android/MainViewModel.kt",
        )

        assertEquals(
            "Inspect ",
            removingFileMentionAliases(
                text = "Inspect @MainViewModel.kt ",
                mention = mention,
            ),
        )
    }

    @Test
    fun hasClosedConfirmedFileMentionPrefix_detectsFinishedMentionBeforeNormalProse() {
        val mention = ComposerMentionedFile(
            fileName = "MainViewModel.kt",
            path = "android/app/src/main/java/io/androdex/android/MainViewModel.kt",
        )

        assertTrue(
            hasClosedConfirmedFileMentionPrefix(
                text = "Inspect @MainViewModel.kt next",
                confirmedMentions = listOf(mention),
            )
        )
        assertFalse(
            hasClosedConfirmedFileMentionPrefix(
                text = "Inspect @Main",
                confirmedMentions = listOf(mention),
            )
        )
    }

    @Test
    fun buildComposerPayloadText_replacesFilesAndPrefixesSubagentsPrompt() {
        val mention = ComposerMentionedFile(
            fileName = "MainViewModel.kt",
            path = "android/app/src/main/java/io/androdex/android/MainViewModel.kt",
        )

        assertEquals(
            "$subagentsCannedPrompt\n\nInspect @android/app/src/main/java/io/androdex/android/MainViewModel.kt",
            buildComposerPayloadText(
                text = "Inspect @MainViewModel.kt",
                mentionedFiles = listOf(mention),
                isSubagentsSelected = true,
            ),
        )
    }
}
