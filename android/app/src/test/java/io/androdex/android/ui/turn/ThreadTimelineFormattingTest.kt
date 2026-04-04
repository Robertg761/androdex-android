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
import io.androdex.android.timeline.buildThreadAgentActivityText
import io.androdex.android.timeline.buildThreadTimelineRenderSnapshot
import io.androdex.android.timeline.buildThreadTimelineRenderItems
import io.androdex.android.timeline.timelineScrollTargetIndex
import io.androdex.android.timeline.updateThreadTimelineRenderSnapshotForAssistantTextChange
import io.androdex.android.ui.state.ToolUserInputOptionUiState
import io.androdex.android.ui.state.ToolUserInputQuestionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadTimelineFormattingTest {
    @Test
    fun timelineScrollTargetIndex_prefersFirstMessageInFocusedTurn() {
        val messages = listOf(
            chatMessage(id = "user", role = ConversationRole.USER, createdAt = 1_000L).copy(turnId = "turn-1"),
            systemMessage(id = "thinking", kind = ConversationKind.THINKING, createdAt = 2_000L).copy(turnId = "turn-2"),
            chatMessage(id = "assistant", role = ConversationRole.ASSISTANT, createdAt = 3_000L).copy(turnId = "turn-2"),
        )

        assertEquals(1, timelineScrollTargetIndex(messages, "turn-2"))
    }

    @Test
    fun timelineScrollTargetIndex_fallsBackToLatestWhenTurnMissing() {
        val messages = listOf(
            chatMessage(id = "user", role = ConversationRole.USER, createdAt = 1_000L).copy(turnId = "turn-1"),
            chatMessage(id = "assistant", role = ConversationRole.ASSISTANT, createdAt = 2_000L).copy(turnId = "turn-2"),
        )

        assertEquals(messages.lastIndex, timelineScrollTargetIndex(messages, "turn-missing"))
        assertEquals(messages.lastIndex, timelineScrollTargetIndex(messages, null))
    }

    @Test
    fun buildBubbleContexts_groupsAssistantChatMessagesWithinWindow() {
        val messages = listOf(
            chatMessage(id = "a1", role = ConversationRole.ASSISTANT, createdAt = 1_000L),
            chatMessage(id = "a2", role = ConversationRole.ASSISTANT, createdAt = 61_000L),
            chatMessage(id = "a3", role = ConversationRole.ASSISTANT, createdAt = 400_000L),
            chatMessage(id = "u1", role = ConversationRole.USER, createdAt = 420_000L),
        )

        val contexts = buildThreadTimelineRenderItems(messages)

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
        val activity = buildThreadAgentActivityText(
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
    fun buildThreadTimelineComposeCache_preservesSnapshotDerivedFields() {
        val snapshot = buildThreadTimelineRenderSnapshot(
            threadId = "thread-1",
            messageRevision = 7L,
            messages = listOf(
                chatMessage(id = "user", role = ConversationRole.USER, createdAt = 1_000L).copy(turnId = "turn-1"),
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
                ).copy(turnId = "turn-1"),
            ),
        )

        val cache = buildThreadTimelineComposeCache(snapshot)

        assertEquals("thread-1", cache.threadId)
        assertEquals(7L, cache.messageRevision)
        assertEquals(snapshot.items, cache.items)
        assertEquals(snapshot.latestMessageIndex, cache.latestMessageIndex)
        assertEquals(snapshot.agentActivityText, cache.agentActivityText)
    }

    @Test
    fun buildThreadTimelineScrollTarget_usesSnapshotTurnIndex() {
        val snapshot = buildThreadTimelineRenderSnapshot(
            threadId = "thread-1",
            messageRevision = 3L,
            messages = listOf(
                chatMessage(id = "user", role = ConversationRole.USER, createdAt = 1_000L).copy(turnId = "turn-1"),
                systemMessage(id = "thinking", kind = ConversationKind.THINKING, createdAt = 2_000L).copy(turnId = "turn-2"),
                chatMessage(id = "assistant", role = ConversationRole.ASSISTANT, createdAt = 3_000L).copy(turnId = "turn-2"),
            ),
        )

        assertEquals(1, buildThreadTimelineScrollTarget(snapshot, "turn-2"))
        assertEquals(snapshot.latestMessageIndex, buildThreadTimelineScrollTarget(snapshot, "missing"))
    }

    @Test
    fun buildThreadTimelineAutoScrollState_ignoresStreamingRevisionBumps() {
        val messages = listOf(
            chatMessage(id = "user", role = ConversationRole.USER, createdAt = 1_000L).copy(turnId = "turn-1"),
            chatMessage(id = "assistant", role = ConversationRole.ASSISTANT, createdAt = 2_000L).copy(turnId = "turn-2"),
        )
        val firstSnapshot = buildThreadTimelineRenderSnapshot(
            threadId = "thread-1",
            messageRevision = 1L,
            messages = messages,
        )
        val streamedSnapshot = buildThreadTimelineRenderSnapshot(
            threadId = "thread-1",
            messageRevision = 2L,
            messages = messages.mapIndexed { index, message ->
                if (index == messages.lastIndex) {
                    message.copy(text = "updated", isStreaming = true)
                } else {
                    message
                }
            },
        )

        assertEquals(
            buildThreadTimelineAutoScrollState(firstSnapshot, null),
            buildThreadTimelineAutoScrollState(streamedSnapshot, null),
        )
    }

    @Test
    fun updateThreadTimelineRenderSnapshotForAssistantTextChange_reusesUnchangedRows() {
        val previousMessages = listOf(
            chatMessage(id = "user", role = ConversationRole.USER, createdAt = 1_000L).copy(turnId = "turn-1"),
            chatMessage(id = "assistant", role = ConversationRole.ASSISTANT, createdAt = 2_000L)
                .copy(turnId = "turn-1", itemId = "assistant-1", text = "Hel", isStreaming = true),
        )
        val previousSnapshot = buildThreadTimelineRenderSnapshot(
            threadId = "thread-1",
            messageRevision = 3L,
            messages = previousMessages,
        )

        val updatedSnapshot = updateThreadTimelineRenderSnapshotForAssistantTextChange(
            previousSnapshot = previousSnapshot,
            previousMessages = previousMessages,
            nextMessages = previousMessages.toMutableList().also { messages ->
                messages[1] = messages[1].copy(text = "Hello", isStreaming = true)
            },
            nextMessageRevision = 4L,
        )

        assertNotNull(updatedSnapshot)
        updatedSnapshot ?: return
        assertEquals(4L, updatedSnapshot.messageRevision)
        assertSame(previousSnapshot.items[0], updatedSnapshot.items[0])
        assertSame(previousSnapshot.firstMessageIndexByTurnId, updatedSnapshot.firstMessageIndexByTurnId)
        assertEquals("Hello", updatedSnapshot.items[1].message.text)
        assertTrue(updatedSnapshot.items[1].message.isStreaming)
    }

    @Test
    fun updateThreadTimelineRenderSnapshotForAssistantTextChange_rejectsStructuralChanges() {
        val previousMessages = listOf(
            chatMessage(id = "user", role = ConversationRole.USER, createdAt = 1_000L).copy(turnId = "turn-1"),
            chatMessage(id = "assistant", role = ConversationRole.ASSISTANT, createdAt = 2_000L)
                .copy(turnId = "turn-1", itemId = "assistant-1", text = "Hel", isStreaming = true),
        )
        val previousSnapshot = buildThreadTimelineRenderSnapshot(
            threadId = "thread-1",
            messageRevision = 3L,
            messages = previousMessages,
        )

        val turnIdChanged = previousMessages.toMutableList().also { messages ->
            messages[1] = messages[1].copy(text = "Hello", turnId = "turn-2", isStreaming = false)
        }
        val appendedMessage = previousMessages + chatMessage(
            id = "assistant-2",
            role = ConversationRole.ASSISTANT,
            createdAt = 3_000L,
        ).copy(turnId = "turn-1", itemId = "assistant-2", text = "More")

        assertNull(
            updateThreadTimelineRenderSnapshotForAssistantTextChange(
                previousSnapshot = previousSnapshot,
                previousMessages = previousMessages,
                nextMessages = turnIdChanged,
                nextMessageRevision = 4L,
            )
        )
        assertNull(
            updateThreadTimelineRenderSnapshotForAssistantTextChange(
                previousSnapshot = previousSnapshot,
                previousMessages = previousMessages,
                nextMessages = appendedMessage,
                nextMessageRevision = 4L,
            )
        )
    }

    @Test
    fun updateThreadTimelineRenderSnapshotForAssistantTextChange_largeTimelineStreamingStaysOnFastPath() {
        var messages = buildLargeStreamingTimelineMessages(messagePairs = 600)
        val initialSnapshot = buildThreadTimelineRenderSnapshot(
            threadId = "thread-1",
            messageRevision = 1L,
            messages = messages,
        )
        var snapshot = initialSnapshot
        val firstStableRow = initialSnapshot.items.first()
        val middleStableRow = initialSnapshot.items[initialSnapshot.items.lastIndex / 2]
        val penultimateStableRow = initialSnapshot.items[initialSnapshot.items.lastIndex - 1]
        val startedAtNs = System.nanoTime()

        repeat(200) { iteration ->
            val nextMessages = messages.toMutableList()
            val lastIndex = nextMessages.lastIndex
            nextMessages[lastIndex] = nextMessages[lastIndex].copy(
                text = "assistant-stream-${iteration + 1}",
                isStreaming = true,
            )

            val updatedSnapshot = updateThreadTimelineRenderSnapshotForAssistantTextChange(
                previousSnapshot = snapshot,
                previousMessages = messages,
                nextMessages = nextMessages,
                nextMessageRevision = snapshot.messageRevision + 1L,
            )

            assertNotNull(updatedSnapshot)
            snapshot = requireNotNull(updatedSnapshot)
            messages = nextMessages
        }

        val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
        assertSame(firstStableRow, snapshot.items.first())
        assertSame(middleStableRow, snapshot.items[initialSnapshot.items.lastIndex / 2])
        assertSame(penultimateStableRow, snapshot.items[initialSnapshot.items.lastIndex - 1])
        assertSame(initialSnapshot.firstMessageIndexByTurnId, snapshot.firstMessageIndexByTurnId)
        assertEquals(initialSnapshot.latestMessageIndex, snapshot.latestMessageIndex)
        assertTrue("elapsedMs=$elapsedMs", elapsedMs < 5_000L)
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

    private fun buildLargeStreamingTimelineMessages(messagePairs: Int): List<ConversationMessage> {
        return buildList(messagePairs * 2) {
            repeat(messagePairs) { index ->
                val turnId = "turn-${index + 1}"
                add(
                    chatMessage(
                        id = "user-${index + 1}",
                        role = ConversationRole.USER,
                        createdAt = (index * 2L) + 1L,
                    ).copy(
                        turnId = turnId,
                        itemId = "user-item-${index + 1}",
                        text = "user-prompt-${index + 1}",
                    )
                )
                add(
                    chatMessage(
                        id = "assistant-${index + 1}",
                        role = ConversationRole.ASSISTANT,
                        createdAt = (index * 2L) + 2L,
                    ).copy(
                        turnId = turnId,
                        itemId = "assistant-item-${index + 1}",
                        text = if (index == messagePairs - 1) "assistant-stream-0" else "assistant-reply-${index + 1}",
                        isStreaming = index == messagePairs - 1,
                    )
                )
            }
        }
    }
}
