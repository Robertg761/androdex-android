package io.androdex.android

import io.androdex.android.model.GitBranchesWithStatusResult
import io.androdex.android.model.GitRepoSyncResult
import io.androdex.android.model.GitWorktreeChangeTransferMode

data class ThreadGitState(
    val status: GitRepoSyncResult? = null,
    val branchTargets: GitBranchesWithStatusResult? = null,
    val diffPatch: String? = null,
    val isRefreshing: Boolean = false,
    val isLoadingBranchTargets: Boolean = false,
    val loadedWorkingDirectory: String? = null,
    val loadedRefreshRequestId: Long = 0L,
    val refreshWorkingDirectory: String? = null,
    val refreshRequestId: Long = 0L,
)

enum class GitActionKind {
    REFRESH,
    DIFF,
    COMMIT,
    PUSH,
    PULL,
    SWITCH_BRANCH,
    CREATE_BRANCH,
    CREATE_WORKTREE,
    REMOVE_WORKTREE,
}

data class GitCommitDialogState(
    val message: String = "",
)

data class GitBranchDialogState(
    val newBranchName: String = "",
)

data class GitWorktreeDialogState(
    val branchName: String = "",
    val baseBranch: String = "",
    val changeTransfer: GitWorktreeChangeTransferMode = GitWorktreeChangeTransferMode.MOVE,
)

enum class GitAlertAction {
    DISMISS,
    PULL_REBASE,
    CONTINUE_BRANCH_OPERATION,
    COMMIT_AND_CONTINUE_BRANCH_OPERATION,
    REMOVE_WORKTREE,
}

data class GitAlertButton(
    val label: String,
    val action: GitAlertAction,
    val isDestructive: Boolean = false,
)

data class GitAlertState(
    val title: String,
    val message: String,
    val buttons: List<GitAlertButton>,
)

sealed interface GitBranchUserOperation {
    data class Create(val branchName: String) : GitBranchUserOperation

    data class SwitchTo(val branchName: String) : GitBranchUserOperation

    data class CreateWorktree(
        val branchName: String,
        val baseBranch: String,
        val changeTransfer: GitWorktreeChangeTransferMode,
    ) : GitBranchUserOperation
}

data class GitPendingRemoveWorktree(
    val branch: String,
    val worktreePath: String,
)
