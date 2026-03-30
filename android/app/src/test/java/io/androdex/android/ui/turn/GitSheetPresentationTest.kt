package io.androdex.android.ui.turn

import io.androdex.android.GitActionKind
import io.androdex.android.model.GitChangedFile
import io.androdex.android.model.GitRepoSyncResult
import io.androdex.android.ui.state.ThreadGitUiState
import io.androdex.android.ui.shared.RemodexPillStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitSheetPresentationTest {
    @Test
    fun affordance_hidden_whenThreadHasNoWorkingDirectory() {
        val affordance = buildGitAffordanceUiState(
            ThreadGitUiState(
                hasWorkingDirectory = false,
                availabilityMessage = null,
                status = null,
                branchTargets = null,
                diffPatch = null,
                isRefreshing = false,
                runningAction = null,
                canRunActions = false,
                commitDialog = null,
                branchDialog = null,
                worktreeDialog = null,
                alert = null,
            )
        )

        assertNull(affordance)
    }

    @Test
    fun affordance_usesBranchAndShortStatus_whenGitStateLoaded() {
        val affordance = buildGitAffordanceUiState(
            ThreadGitUiState(
                hasWorkingDirectory = true,
                availabilityMessage = null,
                status = gitStatus(branch = "feature/mobile-git", isDirty = true),
                branchTargets = null,
                diffPatch = null,
                isRefreshing = false,
                runningAction = null,
                canRunActions = true,
                commitDialog = null,
                branchDialog = null,
                worktreeDialog = null,
                alert = null,
            )
        )

        assertEquals("feature/mobile-git", affordance?.primaryLabel)
        assertEquals("Dirty", affordance?.secondaryLabel)
    }

    @Test
    fun affordance_prefersBusySignal_whileGitActionRuns() {
        val affordance = buildGitAffordanceUiState(
            ThreadGitUiState(
                hasWorkingDirectory = true,
                availabilityMessage = "Git action in progress.",
                status = gitStatus(branch = "main", isDirty = true, aheadCount = 2),
                branchTargets = null,
                diffPatch = null,
                isRefreshing = false,
                runningAction = GitActionKind.PUSH,
                canRunActions = false,
                commitDialog = null,
                branchDialog = null,
                worktreeDialog = null,
                alert = null,
            )
        )

        assertEquals("Busy", affordance?.secondaryLabel)
    }

    @Test
    fun changedFilesPreview_reportsHiddenCount_whenListIsTruncated() {
        val preview = buildGitChangedFilesPreview(
            files = List(GIT_CHANGED_FILES_PREVIEW_LIMIT + 2) { index ->
                GitChangedFile(path = "file-$index.kt", status = "M")
            }
        )

        assertEquals(GIT_CHANGED_FILES_PREVIEW_LIMIT, preview.visibleFiles.size)
        assertEquals(2, preview.hiddenCount)
    }

    @Test
    fun fileStatusPillStyle_mapsCommonGitStates() {
        assertEquals(RemodexPillStyle.Success, gitFileStatusPillStyle("A"))
        assertEquals(RemodexPillStyle.Warning, gitFileStatusPillStyle("M"))
        assertEquals(RemodexPillStyle.Error, gitFileStatusPillStyle("D"))
        assertEquals(RemodexPillStyle.Accent, gitFileStatusPillStyle("R"))
        assertEquals(RemodexPillStyle.Neutral, gitFileStatusPillStyle("X"))
    }

    private fun gitStatus(
        branch: String,
        isDirty: Boolean,
        aheadCount: Int = 0,
    ): GitRepoSyncResult {
        return GitRepoSyncResult(
            repoRoot = "C:\\Projects\\Androdex",
            currentBranch = branch,
            trackingBranch = "origin/$branch",
            isDirty = isDirty,
            aheadCount = aheadCount,
            behindCount = 0,
            localOnlyCommitCount = aheadCount,
            state = if (isDirty) "dirty" else "up_to_date",
            canPush = aheadCount > 0,
            isPublishedToRemote = true,
            files = emptyList(),
            repoDiffTotals = null,
        )
    }
}
