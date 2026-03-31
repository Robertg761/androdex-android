package io.androdex.android.ui.turn

import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ComposerMentionedFile
import io.androdex.android.model.ComposerMentionedSkill
import io.androdex.android.model.ExecutionContent
import io.androdex.android.model.ExecutionKind
import io.androdex.android.model.ImageAttachment
import io.androdex.android.model.QueuedTurnDraft
import io.androdex.android.ui.state.ToolUserInputOptionUiState
import io.androdex.android.ui.state.ToolUserInputQuestionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadTimelineFormattingTest {
    @Test
    fun buildBubbleContexts_groupsAssistantChatMessagesWithinWindow() {
        val messages = listOf(
            chatMessage(id = "a1", role = ConversationRole.ASSISTANT, createdAt = 1_000L),
            chatMessage(id = "a2", role = ConversationRole.ASSISTANT, createdAt = 61_000L),
            chatMessage(id = "a3", role = ConversationRole.ASSISTANT, createdAt = 400_000L),
            chatMessage(id = "u1", role = ConversationRole.USER, createdAt = 420_000L),
        )

        val contexts = buildBubbleContexts(messages)

        assertEquals(4, contexts.size)
        assertTrue(contexts[0].isFirstInGroup)
        assertFalse(contexts[0].isLastInGroup)
        assertFalse(contexts[1].isFirstInGroup)
        assertTrue(contexts[1].isLastInGroup)
        assertTrue(contexts[2].isFirstInGroup)
        assertTrue(contexts[2].isLastInGroup)
        assertTrue(contexts[3].isFirstInGroup)
        assertTrue(contexts[3].isLastInGroup)
    }

    @Test
    fun buildAgentActivityText_prefersStructuredSystemState() {
        val activity = buildAgentActivityText(
            listOf(
                chatMessage(id = "user", role = ConversationRole.USER, createdAt = 1_000L),
                systemMessage(
                    id = "exec",
                    kind = ConversationKind.EXECUTION,
                    createdAt = 2_000L,
                    isStreaming = true,
                    execution = ExecutionContent(
                        kind = ExecutionKind.COMMAND,
                        title = "npm test",
                        status = "running",
                    ),
                ),
            )
        )

        assertEquals("Command: npm test", activity)
    }

    @Test
    fun parseTimelineBodyBlocks_splitsParagraphsAndCodeFences() {
        val blocks = parseTimelineBodyBlocks(
            """
            Intro paragraph.

            ```kotlin
            val answer = 42
            ```

            [ThreadTimelineScreen.kt](/tmp/ThreadTimelineScreen.kt#L10)
            """.trimIndent()
        )

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is TimelineBodyBlock.Paragraph)
        assertTrue(blocks[1] is TimelineBodyBlock.CodeFence)
        assertTrue(blocks[2] is TimelineBodyBlock.Paragraph)
        assertEquals("kotlin", (blocks[1] as TimelineBodyBlock.CodeFence).language)
        assertTrue(((blocks[2] as TimelineBodyBlock.Paragraph).text).contains("ThreadTimelineScreen.kt"))
    }

    @Test
    fun looksLikeFileReference_matchesMarkdownAndAbsolutePaths() {
        assertTrue(looksLikeFileReference("[Main.kt](/tmp/Main.kt:12)"))
        assertTrue(looksLikeFileReference("/tmp/Main.kt"))
        assertFalse(looksLikeFileReference("Plain assistant response"))
    }

    @Test
    fun normalizeTimelineParagraph_preservesSingleLineBreaks() {
        assertEquals(
            "First line\nSecond line\n\nThird line",
            normalizeTimelineParagraph("First line  \nSecond line\n\nThird line\n"),
        )
    }

    @Test
    fun standaloneFileReferenceParagraph_onlyMatchesSingleReferenceLine() {
        assertTrue(isStandaloneFileReferenceParagraph("[Main.kt](/tmp/Main.kt:12)"))
        assertFalse(
            isStandaloneFileReferenceParagraph(
                """
                [Main.kt](/tmp/Main.kt:12)
                See comment above.
                """.trimIndent()
            )
        )
    }

    @Test
    fun listParagraph_requiresEveryLineToBeAListItem() {
        assertTrue(
            isListParagraph(
                """
                1. Inspect the stream
                2. Verify spacing
                """.trimIndent()
            )
        )
        assertFalse(
            isListParagraph(
                """
                1. Inspect the stream
                Extra note
                """.trimIndent()
            )
        )
    }

    @Test
    fun queuedDraftMetadataLabels_includesModeAttachmentsAndContext() {
        val labels = queuedDraftMetadataLabels(
            QueuedTurnDraft(
                id = "draft-1",
                text = "Follow up",
                attachments = listOf(ImageAttachment(thumbnailBase64Jpeg = "a")),
                createdAtEpochMs = 1L,
                collaborationMode = CollaborationModeKind.PLAN,
                subagentsSelectionEnabled = true,
                mentionedFiles = listOf(
                    ComposerMentionedFile(
                        fileName = "Main.kt",
                        path = "/tmp/Main.kt",
                    )
                ),
                mentionedSkills = listOf(
                    ComposerMentionedSkill(
                        name = "checks",
                    )
                ),
            )
        )

        assertEquals(
            listOf("Plan", "Subagents", "1 photo", "1 file", "1 skill"),
            labels,
        )
    }

    @Test
    fun queuedDraftSummaryText_tracksPauseState() {
        assertEquals("1 queued draft waiting", queuedDraftSummaryText(draftCount = 1, isPaused = false))
        assertEquals("3 queued drafts paused", queuedDraftSummaryText(draftCount = 3, isPaused = true))
    }

    @Test
    fun toolInputHelpers_chooseOtherFieldLabelAndSummaryPluralization() {
        val question = ToolUserInputQuestionUiState(
            id = "branch",
            header = "Branch",
            question = "Which branch?",
            answer = "",
            options = listOf(
                ToolUserInputOptionUiState(
                    label = "main",
                    description = null,
                    isSelected = false,
                )
            ),
            allowsCustomAnswer = true,
            isSecret = false,
        )

        assertEquals("Other answer", toolInputCustomFieldLabel(question))
        assertEquals("1 request waiting", pendingToolInputSummary(1))
        assertEquals("2 requests waiting", pendingToolInputSummary(2))
    }

    private fun chatMessage(
        id: String,
        role: ConversationRole,
        createdAt: Long,
    ): ConversationMessage {
        return ConversationMessage(
            id = id,
            threadId = "thread-1",
            role = role,
            kind = ConversationKind.CHAT,
            text = id,
            createdAtEpochMs = createdAt,
        )
    }

    private fun systemMessage(
        id: String,
        kind: ConversationKind,
        createdAt: Long,
        isStreaming: Boolean = false,
        execution: ExecutionContent? = null,
    ): ConversationMessage {
        return ConversationMessage(
            id = id,
            threadId = "thread-1",
            role = ConversationRole.SYSTEM,
            kind = kind,
            text = id,
            createdAtEpochMs = createdAt,
            isStreaming = isStreaming,
            execution = execution,
        )
    }
}
