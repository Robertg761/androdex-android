package io.androdex.android.model

data class GitDiffTotals(
    val additions: Int,
    val deletions: Int,
    val binaryFiles: Int = 0,
) {
    val hasChanges: Boolean
        get() = additions > 0 || deletions > 0 || binaryFiles > 0
}

data class GitChangedFile(
    val path: String,
    val status: String,
)

data class GitRepoSyncResult(
    val repoRoot: String?,
    val currentBranch: String?,
    val trackingBranch: String?,
    val isDirty: Boolean,
    val aheadCount: Int,
    val behindCount: Int,
    val localOnlyCommitCount: Int,
    val state: String,
    val canPush: Boolean,
    val isPublishedToRemote: Boolean,
    val files: List<GitChangedFile>,
    val repoDiffTotals: GitDiffTotals?,
)

data class GitRepoDiffResult(
    val patch: String,
)

data class GitCommitResult(
    val commitHash: String,
    val branch: String,
    val summary: String,
)

data class GitPushResult(
    val branch: String,
    val remote: String?,
    val status: GitRepoSyncResult?,
)

data class GitPullResult(
    val success: Boolean,
    val status: GitRepoSyncResult?,
)

data class GitCheckoutResult(
    val currentBranch: String,
    val tracking: String?,
    val status: GitRepoSyncResult?,
)

data class GitCreateBranchResult(
    val branch: String,
    val status: GitRepoSyncResult?,
)

data class GitCreateWorktreeResult(
    val branch: String,
    val worktreePath: String,
    val alreadyExisted: Boolean,
)

data class GitRemoveWorktreeResult(
    val success: Boolean,
)

data class GitBranchesResult(
    val branches: List<String>,
    val branchesCheckedOutElsewhere: Set<String>,
    val worktreePathByBranch: Map<String, String>,
    val localCheckoutPath: String?,
    val currentBranch: String?,
    val defaultBranch: String?,
)

data class GitBranchesWithStatusResult(
    val branches: List<String>,
    val branchesCheckedOutElsewhere: Set<String>,
    val worktreePathByBranch: Map<String, String>,
    val localCheckoutPath: String?,
    val currentBranch: String?,
    val defaultBranch: String?,
    val status: GitRepoSyncResult?,
)

enum class GitWorktreeChangeTransferMode(
    val wireValue: String,
) {
    MOVE("move"),
    COPY("copy"),
}

data class GitOperationException(
    val code: String?,
    override val message: String,
) : Exception(message)
