package io.androdex.android.ui.turn

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.androdex.android.ThreadOpenPerfLogger
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.ExecutionContent
import io.androdex.android.model.ExecutionKind
import io.androdex.android.timeline.buildThreadTimelineRenderSnapshot
import io.androdex.android.ui.state.BusyUiState
import io.androdex.android.ui.state.ComposerSubmitMode
import io.androdex.android.ui.state.ComposerUiState
import io.androdex.android.ui.state.RuntimeSettingsOptionUiState
import io.androdex.android.ui.state.ThreadActionUiState
import io.androdex.android.ui.state.ThreadForkUiState
import io.androdex.android.ui.state.ThreadGitUiState
import io.androdex.android.ui.state.ThreadRuntimeUiState
import io.androdex.android.ui.state.ThreadTimelineUiState
import io.androdex.android.ui.theme.AndrodexTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThreadTimelinePerfUiTest {
    private val perfEntries = mutableListOf<ThreadOpenPerfLogger.Entry>()

    @Before
    fun setUp() {
        ThreadOpenPerfLogger.setLogObserverForTests { entry ->
            synchronized(perfEntries) {
                perfEntries += entry
            }
        }
    }

    @After
    fun tearDown() {
        ThreadOpenPerfLogger.setLogObserverForTests(null)
    }

    @Test
    fun phase4Proof_logsCheapComposeWorkForReopenAndIncrementalUpdates() {
        val threadId = "phase4-proof-thread"
        val initialMessages = buildPerfMessages(threadId = threadId, assistantSuffix = "initial")
        val incrementalMessages = initialMessages.dropLast(1) + initialMessages.last().copy(
            id = "exec-stream",
            isStreaming = true,
            text = "Running instrumentation proof",
            execution = ExecutionContent(
                kind = ExecutionKind.COMMAND,
                title = "gradlew connectedDebugAndroidTest",
                status = "running",
            ),
        )
        val threadState = mutableStateOf(
            buildThreadTimelineUiState(
                threadId = threadId,
                title = "Phase 4 proof",
                messageRevision = 1L,
                messages = initialMessages,
            )
        )
        val sidebarOpen = mutableStateOf(false)
        val screenVisible = mutableStateOf(true)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)

        try {
            val initialEntries = runScenario(threadId, "initial_open") {
                scenario.onActivity { activity ->
                    activity.setContent {
                        AndrodexTheme {
                            if (screenVisible.value) {
                                ThreadTimelineScreen(
                                    state = threadState.value,
                                    isConnected = true,
                                    isSidebarOpen = sidebarOpen.value,
                                    onBack = {},
                                    onOpenSidebar = {},
                                    onRefresh = {},
                                    onConsumeFocusTurn = {},
                                    onComposerChanged = {},
                                    onPlanModeChanged = {},
                                    onSubagentsModeChanged = {},
                                    onSelectReviewTarget = {},
                                    onReviewBaseBranchChanged = {},
                                    onRemoveReviewSelection = {},
                                    onSelectFileAutocomplete = {},
                                    onRemoveMentionedFile = {},
                                    onSelectSkillAutocomplete = {},
                                    onRemoveMentionedSkill = {},
                                    onSelectSlashCommand = {},
                                    onAddCamera = {},
                                    onAddGallery = {},
                                    onRemoveComposerAttachment = {},
                                    onOpenRuntime = {},
                                    onOpenGitSheet = {},
                                    onOpenFork = {},
                                    onCompactThread = {},
                                    onRollbackThread = {},
                                    onCleanBackgroundTerminals = {},
                                    onSend = {},
                                    onStop = {},
                                    onPauseQueue = {},
                                    onResumeQueue = {},
                                    onRestoreQueuedDraft = {},
                                    onRemoveQueuedDraft = {},
                                    onToolInputAnswerChanged = { _, _, _ -> },
                                    onSubmitToolInput = {},
                                    onUpdateGitCommitMessage = {},
                                    onDismissGitCommit = {},
                                    onSubmitGitCommit = {},
                                    onUpdateGitBranchName = {},
                                    onDismissGitBranchDialog = {},
                                    onRequestCreateGitBranch = {},
                                    onRequestSwitchGitBranch = {},
                                    onUpdateGitWorktreeBranchName = {},
                                    onUpdateGitWorktreeBaseBranch = {},
                                    onUpdateGitWorktreeTransferMode = {},
                                    onDismissGitWorktreeDialog = {},
                                    onRequestCreateGitWorktree = {},
                                    onRequestRemoveGitWorktree = { _, _ -> },
                                    onDismissGitAlert = {},
                                    onHandleGitAlertAction = {},
                                )
                            }
                        }
                    }
                }
            }
            assertComposeStagesPresent(initialEntries)

            val unrelatedEntries = runScenario(threadId, "unrelated_recompose") {
                scenario.onActivity {
                    sidebarOpen.value = !sidebarOpen.value
                }
            }
            assertNoComposeStageDurations(unrelatedEntries)

            val incrementalEntries = runScenario(threadId, "incremental_update") {
                scenario.onActivity {
                    threadState.value = buildThreadTimelineUiState(
                        threadId = threadId,
                        title = "Phase 4 proof",
                        messageRevision = 2L,
                        messages = incrementalMessages,
                    )
                }
            }
            assertComposeStagesPresent(incrementalEntries)

            scenario.onActivity {
                screenVisible.value = false
            }
            awaitUiIdle()

            val reopenEntries = runScenario(threadId, "reopen") {
                scenario.onActivity {
                    screenVisible.value = true
                }
            }
            assertComposeStagesPresent(reopenEntries)

            val initialUiMs = composeStageDurationTotal(initialEntries)
            val incrementalUiMs = composeStageDurationTotal(incrementalEntries)
            val reopenUiMs = composeStageDurationTotal(reopenEntries)

            Log.d(
                "Phase4Proof",
                "initialUiMs=$initialUiMs unrelatedUiMs=${composeStageDurationTotal(unrelatedEntries)} " +
                    "incrementalUiMs=$incrementalUiMs reopenUiMs=$reopenUiMs " +
                    "incrementalStages=${composeStageSummary(incrementalEntries)} " +
                    "reopenStages=${composeStageSummary(reopenEntries)}",
            )

            assertTrue(incrementalUiMs <= initialUiMs + 2L)
            assertTrue(reopenUiMs <= initialUiMs + 2L)
        } finally {
            scenario.close()
        }
    }

    private fun runScenario(
        threadId: String,
        scenario: String,
        action: () -> Unit,
    ): List<ThreadOpenPerfLogger.Entry> {
        synchronized(perfEntries) {
            perfEntries.clear()
        }
        ThreadOpenPerfLogger.startAttempt(
            threadId = threadId,
            stage = "Phase4Proof.$scenario",
        )
        action()
        awaitUiIdle()
        return synchronized(perfEntries) {
            perfEntries.toList()
        }
    }

    private fun awaitUiIdle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(150L)
        instrumentation.waitForIdleSync()
    }

    private fun assertComposeStagesPresent(entries: List<ThreadOpenPerfLogger.Entry>) {
        val stages = composeStages(entries).map { it.stage }
        assertTrue(stages.contains("ThreadTimelineScreen.buildComposeCache"))
        assertTrue(stages.contains("ThreadTimelineScreen.buildAutoScrollState"))
    }

    private fun assertNoComposeStageDurations(entries: List<ThreadOpenPerfLogger.Entry>) {
        assertEquals(emptyList<ThreadOpenPerfLogger.Entry>(), composeStages(entries))
    }

    private fun composeStageDurationTotal(entries: List<ThreadOpenPerfLogger.Entry>): Long {
        return composeStages(entries).sumOf { it.durationMs ?: 0L }
    }

    private fun composeStageSummary(entries: List<ThreadOpenPerfLogger.Entry>): String {
        return composeStages(entries)
            .joinToString(separator = ",") { entry ->
                "${entry.stage.substringAfterLast('.')}=${entry.durationMs ?: 0L}ms"
            }
    }

    private fun composeStages(entries: List<ThreadOpenPerfLogger.Entry>): List<ThreadOpenPerfLogger.Entry> {
        return entries.filter { entry ->
            entry.stage == "ThreadTimelineScreen.buildComposeCache"
                || entry.stage == "ThreadTimelineScreen.buildScrollTarget"
                || entry.stage == "ThreadTimelineScreen.buildAutoScrollState"
        }
    }
}

private fun buildThreadTimelineUiState(
    threadId: String,
    title: String,
    messageRevision: Long,
    messages: List<ConversationMessage>,
): ThreadTimelineUiState {
    return ThreadTimelineUiState(
        threadId = threadId,
        title = title,
        subtitle = "Instrumentation proof",
        timeline = buildThreadTimelineRenderSnapshot(
            threadId = threadId,
            messageRevision = messageRevision,
            messages = messages,
        ),
        focusedTurnId = null,
        runState = null,
        isForkedThread = false,
        busy = BusyUiState(isVisible = false, label = null),
        git = ThreadGitUiState(
            hasWorkingDirectory = true,
            availabilityMessage = null,
            status = null,
            branchTargets = null,
            diffPatch = null,
            isRefreshing = false,
            isBranchContextLoading = false,
            isBranchContextReady = false,
            runningAction = null,
            canRunActions = false,
            commitDialog = null,
            branchDialog = null,
            worktreeDialog = null,
            alert = null,
        ),
        queuedDrafts = emptyList(),
        queuePauseMessage = null,
        canRestoreQueuedDrafts = false,
        canPauseQueue = false,
        canResumeQueue = false,
        pendingToolInputs = emptyList(),
        runtime = ThreadRuntimeUiState(
            reasoningOptions = listOf(
                RuntimeSettingsOptionUiState(
                    value = null,
                    title = "Default",
                    subtitle = null,
                    selected = true,
                )
            ),
            selectedReasoningOverride = null,
            serviceTierOptions = emptyList(),
            selectedServiceTierOverride = null,
            supportsServiceTier = false,
            supportsPlanMode = true,
            collaborationSummary = "Default mode",
            accessModeLabel = "On request",
            hasOverrides = false,
        ),
        compact = ThreadActionUiState(
            isEnabled = false,
            availabilityMessage = null,
        ),
        rollback = ThreadActionUiState(
            isEnabled = false,
            availabilityMessage = null,
        ),
        backgroundTerminals = ThreadActionUiState(
            isEnabled = false,
            availabilityMessage = null,
        ),
        fork = ThreadForkUiState(
            isEnabled = false,
            availabilityMessage = null,
            targets = emptyList(),
        ),
        composer = ComposerUiState(
            text = "",
            attachments = emptyList(),
            remainingAttachmentSlots = 4,
            hasBlockingAttachmentState = false,
            mentionedFiles = emptyList(),
            mentionedSkills = emptyList(),
            fileAutocompleteItems = emptyList(),
            isFileAutocompleteVisible = false,
            isFileAutocompleteLoading = false,
            fileAutocompleteQuery = "",
            skillAutocompleteItems = emptyList(),
            isSkillAutocompleteVisible = false,
            isSkillAutocompleteLoading = false,
            skillAutocompleteQuery = "",
            slashCommandItems = emptyList(),
            isSlashCommandAutocompleteVisible = false,
            slashCommandQuery = "",
            inputEnabled = true,
            submitMode = ComposerSubmitMode.SEND,
            isPlanModeEnabled = true,
            isPlanModeSupported = true,
            planModeEnabled = false,
            planModeLabel = "Plan",
            isSubagentsEnabled = true,
            subagentsEnabled = false,
            reviewSelection = null,
            isReviewModeEnabled = false,
            reviewTarget = null,
            reviewBaseBranchValue = "",
            reviewBaseBranchLabel = null,
            placeholderText = "Ask anything",
            submitButtonLabel = "Send",
            isSubmitting = false,
            submitEnabled = true,
            showStop = false,
            isStopping = false,
            stopEnabled = false,
            queuedCount = 0,
            isQueuePaused = false,
            runtimeButtonLabel = "Runtime",
            runtimeButtonEnabled = true,
        ),
    )
}

private fun buildPerfMessages(
    threadId: String,
    assistantSuffix: String,
    pairCount: Int = 120,
): List<ConversationMessage> {
    val messages = mutableListOf<ConversationMessage>()
    repeat(pairCount) { index ->
        val turnId = "turn-$index"
        val baseTs = index * 2_000L
        messages += ConversationMessage(
            id = "user-$index",
            threadId = threadId,
            role = ConversationRole.USER,
            kind = ConversationKind.CHAT,
            text = "User prompt $index",
            createdAtEpochMs = baseTs,
            turnId = turnId,
        )
        messages += ConversationMessage(
            id = "assistant-$index",
            threadId = threadId,
            role = ConversationRole.ASSISTANT,
            kind = ConversationKind.CHAT,
            text = "Assistant response $index $assistantSuffix",
            createdAtEpochMs = baseTs + 1_000L,
            turnId = turnId,
        )
    }
    messages += ConversationMessage(
        id = "exec-final",
        threadId = threadId,
        role = ConversationRole.SYSTEM,
        kind = ConversationKind.EXECUTION,
        text = "Command finished",
        createdAtEpochMs = pairCount * 2_000L + 500L,
        turnId = "turn-final",
        isStreaming = false,
        execution = ExecutionContent(
            kind = ExecutionKind.COMMAND,
            title = "git status",
            status = "completed",
        ),
    )
    return messages
}
