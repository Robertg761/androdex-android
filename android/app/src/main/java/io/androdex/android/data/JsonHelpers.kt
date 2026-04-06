package io.androdex.android.data

import io.androdex.android.attachment.buildImageAttachmentFromBytes
import io.androdex.android.attachment.decodeDataUrlImageData
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ExecutionContent
import io.androdex.android.model.ExecutionDetail
import io.androdex.android.model.ExecutionKind
import io.androdex.android.model.FuzzyFileMatch
import io.androdex.android.model.GitBranchesResult
import io.androdex.android.model.GitBranchesWithStatusResult
import io.androdex.android.model.GitChangedFile
import io.androdex.android.model.GitCheckoutResult
import io.androdex.android.model.GitCommitResult
import io.androdex.android.model.GitCreateBranchResult
import io.androdex.android.model.GitCreateWorktreeResult
import io.androdex.android.model.GitDiffTotals
import io.androdex.android.model.GitPullResult
import io.androdex.android.model.GitPushResult
import io.androdex.android.model.GitRemoveWorktreeResult
import io.androdex.android.model.GitRepoDiffResult
import io.androdex.android.model.GitRepoSyncResult
import io.androdex.android.model.HostAccountSnapshot
import io.androdex.android.model.HostAccountStatus
import io.androdex.android.model.HostRateLimitBucket
import io.androdex.android.model.HostRuntimeMetadata
import io.androdex.android.model.ModelOption
import io.androdex.android.model.PlanStep
import io.androdex.android.model.ReasoningEffortOption
import io.androdex.android.model.SkillMetadata
import io.androdex.android.model.SubagentAction
import io.androdex.android.model.SubagentRef
import io.androdex.android.model.SubagentState
import io.androdex.android.model.ThreadTokenUsage
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.WorkspaceActivationStatus
import io.androdex.android.model.WorkspaceBrowseResult
import io.androdex.android.model.WorkspaceDirectoryEntry
import io.androdex.android.model.WorkspacePathSummary
import io.androdex.android.model.WorkspaceRecentState
import org.json.JSONArray
import org.json.JSONObject
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private val isoFormatters = listOf(
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US),
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US),
).onEach { formatter ->
    formatter.timeZone = TimeZone.getTimeZone("UTC")
}

fun JSONObject.stringOrNull(vararg keys: String): String? {
    for (key in keys) {
        val rawValue = opt(key)
        if (rawValue == null || rawValue == JSONObject.NULL) {
            continue
        }
        val value = rawValue.toString().trim()
        if (value.isNotEmpty()) {
            return value
        }
    }
    return null
}

fun JSONObject.objectOrNull(vararg keys: String): JSONObject? {
    for (key in keys) {
        val value = optJSONObject(key)
        if (value != null) {
            return value
        }
    }
    return null
}

fun JSONObject.arrayOrNull(vararg keys: String): JSONArray? {
    for (key in keys) {
        val value = optJSONArray(key)
        if (value != null) {
            return value
        }
    }
    return null
}

fun JSONObject.booleanLikeOrFalse(vararg keys: String): Boolean {
    for (key in keys) {
        val rawValue = opt(key)
        when (rawValue) {
            null, JSONObject.NULL -> Unit
            is Boolean -> return rawValue
            is Number -> return rawValue.toInt() != 0
            is String -> {
                val normalized = rawValue.trim().lowercase(Locale.US)
                if (normalized in setOf("true", "1", "yes")) {
                    return true
                }
                if (normalized in setOf("false", "0", "no")) {
                    return false
                }
            }
        }
    }
    return false
}

fun normalizeItemType(rawValue: String?): String {
    return rawValue
        ?.trim()
        ?.lowercase(Locale.US)
        ?.replace("_", "")
        ?.replace("-", "")
        ?.replace("/", "")
        ?: ""
}

fun parseTimestamp(rawValue: Any?): Long? {
    return when (rawValue) {
        is Number -> {
            val value = rawValue.toDouble()
            if (value > 10_000_000_000) value.toLong() else (value * 1000).toLong()
        }

        is String -> {
            val trimmed = rawValue.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                trimmed.toLongOrNull()?.let { numeric ->
                    if (numeric > 10_000_000_000) numeric else numeric * 1000
                } ?: parseIsoTimestamp(trimmed)
            }
        }

        else -> null
    }
}

private fun parseIsoTimestamp(value: String): Long? {
    for (formatter in isoFormatters) {
        try {
            return formatter.parse(value)?.time
        } catch (_: ParseException) {
        }
    }
    return null
}

fun decodeThreadSummary(json: JSONObject): ThreadSummary? {
    return decodeThreadSummarySpec(json.toRawMap())
}

internal fun decodeThreadSummarySpec(values: Map<String, Any?>): ThreadSummary? {
    val id = values.stringOrNull("id") ?: return null
    val title = values.stringOrNull("name", "title", "preview") ?: "Conversation"
    val preview = values.stringOrNull("preview")
    val cwd = values.stringOrNull("cwd", "current_working_directory", "working_directory")
    val createdAt = parseTimestamp(
        values["createdAt"] ?: values["created_at"]
    )
    val updatedAt = parseTimestamp(
        values["updatedAt"] ?: values["updated_at"]
    )
    return ThreadSummary(
        id = id,
        title = title,
        preview = preview,
        cwd = cwd,
        createdAtEpochMs = createdAt,
        updatedAtEpochMs = updatedAt,
        forkedFromThreadId = values.stringOrNull(
            "forkedFromThreadId",
            "forked_from_thread_id",
            "forkedFromId",
            "forked_from_id",
        ),
        parentThreadId = values.stringOrNull("parentThreadId", "parent_thread_id"),
        agentId = values.stringOrNull("agentId", "agent_id"),
        agentNickname = values.stringOrNull("agentNickname", "agent_nickname"),
        agentRole = values.stringOrNull("agentRole", "agent_role", "agentType", "agent_type"),
        model = values.stringOrNull("model", "modelName", "model_name", "modelProvider", "model_provider"),
    )
}

fun decodeMessagesFromThreadRead(threadId: String, threadObject: JSONObject): List<ConversationMessage> {
    val turns = threadObject.optJSONArray("turns") ?: return emptyList()
    val baseTime = parseTimestamp(
        threadObject.opt("updatedAt").takeUnless { it == null }
            ?: threadObject.opt("updated_at").takeUnless { it == null }
            ?: threadObject.opt("createdAt").takeUnless { it == null }
            ?: threadObject.opt("created_at").takeUnless { it == null }
    ) ?: 0L

    val messages = mutableListOf<ConversationMessage>()
    var offset = 0L

    for (turnIndex in 0 until turns.length()) {
        val turnObject = turns.optJSONObject(turnIndex) ?: continue
        val turnId = turnObject.stringOrNull("id", "turnId", "turn_id")
        val turnTimestamp = parseTimestamp(
            turnObject.opt("createdAt").takeUnless { it == null }
                ?: turnObject.opt("created_at").takeUnless { it == null }
                ?: turnObject.opt("updatedAt").takeUnless { it == null }
                ?: turnObject.opt("updated_at").takeUnless { it == null }
        ) ?: (baseTime + offset)
        val items = turnObject.optJSONArray("items") ?: continue

        for (itemIndex in 0 until items.length()) {
            val itemObject = items.optJSONObject(itemIndex) ?: continue
            val createdAt = parseTimestamp(
                itemObject.opt("createdAt").takeUnless { it == null }
                    ?: itemObject.opt("created_at").takeUnless { it == null }
            ) ?: (turnTimestamp + offset)
            offset += 1L
            val itemType = normalizeItemType(itemObject.optString("type"))
            val itemId = itemObject.stringOrNull("id", "itemId", "item_id", "messageId", "message_id")

            when (itemType) {
                "usermessage" -> {
                    val attachments = decodeImageAttachments(itemObject)
                    messages += ConversationMessage(
                        id = itemId ?: UUID.randomUUID().toString(),
                        threadId = threadId,
                        role = ConversationRole.USER,
                        kind = ConversationKind.CHAT,
                        text = decodeItemText(itemObject),
                        attachments = attachments,
                        createdAtEpochMs = createdAt,
                        turnId = turnId,
                        itemId = itemId,
                    )
                }

                "agentmessage", "assistantmessage" -> {
                    val attachments = decodeImageAttachments(itemObject)
                    messages += ConversationMessage(
                        id = itemId ?: UUID.randomUUID().toString(),
                        threadId = threadId,
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = decodeItemText(itemObject),
                        attachments = attachments,
                        createdAtEpochMs = createdAt,
                        turnId = turnId,
                        itemId = itemId,
                    )
                }

                "message" -> {
                    val role = when (itemObject.optString("role").lowercase(Locale.US)) {
                        "user" -> ConversationRole.USER
                        "assistant" -> ConversationRole.ASSISTANT
                        else -> ConversationRole.SYSTEM
                    }
                    val attachments = decodeImageAttachments(itemObject)
                    messages += ConversationMessage(
                        id = itemId ?: UUID.randomUUID().toString(),
                        threadId = threadId,
                        role = role,
                        kind = if (role == ConversationRole.SYSTEM) ConversationKind.THINKING else ConversationKind.CHAT,
                        text = decodeItemText(itemObject),
                        attachments = attachments,
                        createdAtEpochMs = createdAt,
                        turnId = turnId,
                        itemId = itemId,
                    )
                }

                "reasoning" -> {
                    messages += ConversationMessage(
                        id = itemId ?: UUID.randomUUID().toString(),
                        threadId = threadId,
                        role = ConversationRole.SYSTEM,
                        kind = ConversationKind.THINKING,
                        text = decodeReasoningText(itemObject),
                        createdAtEpochMs = createdAt,
                        turnId = turnId,
                        itemId = itemId,
                    )
                }

                "filechange", "toolcall", "diff" -> {
                    val fileChange = decodeFileChangeStructured(itemObject)
                    messages += ConversationMessage(
                        id = itemId ?: UUID.randomUUID().toString(),
                        threadId = threadId,
                        role = ConversationRole.SYSTEM,
                        kind = ConversationKind.FILE_CHANGE,
                        text = fileChange.displayText,
                        createdAtEpochMs = createdAt,
                        turnId = turnId,
                        itemId = itemId,
                        filePath = fileChange.path,
                        status = fileChange.status,
                        diffText = fileChange.diff,
                    )
                }

                "plan" -> {
                    val planData = decodePlanContent(itemObject)
                    messages += ConversationMessage(
                        id = itemId ?: UUID.randomUUID().toString(),
                        threadId = threadId,
                        role = ConversationRole.SYSTEM,
                        kind = ConversationKind.PLAN,
                        text = planData.text,
                        createdAtEpochMs = createdAt,
                        turnId = turnId,
                        itemId = itemId,
                        planExplanation = planData.explanation,
                        planSteps = planData.steps,
                    )
                }

                else -> {
                    if (isCommandExecutionItemType(itemType)) {
                        val commandData = decodeCommandExecutionContent(itemObject)
                        messages += ConversationMessage(
                            id = itemId ?: UUID.randomUUID().toString(),
                            threadId = threadId,
                            role = ConversationRole.SYSTEM,
                            kind = ConversationKind.COMMAND,
                            text = commandData.text,
                            createdAtEpochMs = createdAt,
                            turnId = turnId,
                            itemId = itemId,
                            status = commandData.status,
                            command = commandData.command,
                            execution = commandData.execution,
                        )
                        continue
                    }
                    if (isExecutionStyleItemType(itemType)) {
                        val executionData = decodeExecutionStyleContent(itemObject)
                        messages += ConversationMessage(
                            id = itemId ?: UUID.randomUUID().toString(),
                            threadId = threadId,
                            role = ConversationRole.SYSTEM,
                            kind = ConversationKind.EXECUTION,
                            text = executionData.text,
                            createdAtEpochMs = createdAt,
                            turnId = turnId,
                            itemId = itemId,
                            status = executionData.status,
                            execution = executionData.execution,
                        )
                        continue
                    }
                    val action = decodeSubagentActionItem(itemObject) ?: continue
                    messages += ConversationMessage(
                        id = itemId ?: UUID.randomUUID().toString(),
                        threadId = threadId,
                        role = ConversationRole.SYSTEM,
                        kind = ConversationKind.SUBAGENT_ACTION,
                        text = action.summaryText,
                        createdAtEpochMs = createdAt,
                        turnId = turnId,
                        itemId = itemId,
                        subagentAction = action,
                    )
                }

            }
        }
    }

    return messages.sortedBy { it.createdAtEpochMs }
}

fun decodeModelOptions(resultObject: JSONObject): List<ModelOption> {
    val items = resultObject.optJSONArray("items")
        ?: resultObject.optJSONArray("data")
        ?: resultObject.optJSONArray("models")
        ?: JSONArray()

    val decoded = mutableListOf<ModelOption>()
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val model = item.stringOrNull("model", "id") ?: continue
        val id = item.stringOrNull("id") ?: model
        val displayName = item.stringOrNull("displayName", "display_name") ?: model
        val description = item.stringOrNull("description") ?: ""
        val isDefault = item.optBoolean("isDefault", item.optBoolean("is_default", false))
        val supportedEfforts = decodeReasoningEffortOptions(
            item.optJSONArray("supportedReasoningEfforts")
                ?: item.optJSONArray("supported_reasoning_efforts")
                ?: JSONArray()
        )
        val defaultReasoningEffort = item.stringOrNull("defaultReasoningEffort", "default_reasoning_effort")
        decoded += ModelOption(
            id = id,
            model = model,
            displayName = displayName,
            description = description,
            isDefault = isDefault,
            supportedReasoningEfforts = supportedEfforts,
            defaultReasoningEffort = defaultReasoningEffort,
        )
    }
    return decoded
}

fun decodeCollaborationModes(resultObject: JSONObject): Set<CollaborationModeKind> {
    val items = resultObject.arrayOrNull(
        "items",
        "data",
        "modes",
        "collaborationModes",
        "collaboration_modes",
    ) ?: JSONArray()

    val decoded = linkedSetOf<CollaborationModeKind>()
    for (index in 0 until items.length()) {
        val mode = when (val item = items.opt(index)) {
            is JSONObject -> {
                CollaborationModeKind.fromWireValue(
                    item.stringOrNull("mode", "id", "name", "type")
                )
            }

            is String -> CollaborationModeKind.fromWireValue(item)
            else -> null
        } ?: continue
        decoded += mode
    }
    return decoded
}

fun decodeHostAccountSnapshot(resultObject: JSONObject): HostAccountSnapshot? {
    val status = resultObject.decodedHostAccountStatusOrNull() ?: HostAccountStatus.UNKNOWN
    return HostAccountSnapshot(
        status = status,
        authMethod = resultObject.stringOrNull("authMethod", "auth_method"),
        email = resultObject.stringOrNull("email"),
        planType = resultObject.stringOrNull("planType", "plan_type"),
        loginInFlight = resultObject.optBoolean("loginInFlight", resultObject.optBoolean("login_in_flight", false)),
        needsReauth = resultObject.optBoolean("needsReauth", resultObject.optBoolean("needs_reauth", false)),
        tokenReady = resultObject.opt("tokenReady")
            .takeUnless { it == null || it == JSONObject.NULL }
            ?.let {
                when (it) {
                    is Boolean -> it
                    is String -> it.equals("true", ignoreCase = true)
                    is Number -> it.toInt() != 0
                    else -> null
                }
            },
        expiresAtEpochMs = parseTimestamp(
            resultObject.opt("expiresAt").takeUnless { it == null }
                ?: resultObject.opt("expires_at").takeUnless { it == null }
        ),
        bridgeVersion = resultObject.stringOrNull("bridgeVersion", "bridge_version", "bridgePackageVersion", "bridge_package_version"),
        bridgeLatestVersion = resultObject.stringOrNull(
            "bridgeLatestVersion",
            "bridge_latest_version",
            "bridgePublishedVersion",
            "bridge_published_version",
        ),
        rateLimits = decodeHostRateLimitBuckets(resultObject),
    )
}

fun decodeHostRuntimeMetadata(resultObject: JSONObject): HostRuntimeMetadata? {
    val runtimeTarget = resultObject.stringOrNull("runtimeTarget", "runtime_target")
    val runtimeTargetDisplayName = resultObject.stringOrNull(
        "runtimeTargetDisplayName",
        "runtime_target_display_name",
    )
    val backendProvider = resultObject.stringOrNull("backendProvider", "backend_provider")
    val backendProviderDisplayName = resultObject.stringOrNull(
        "backendProviderDisplayName",
        "backend_provider_display_name",
    )
    if (runtimeTarget == null
        && runtimeTargetDisplayName == null
        && backendProvider == null
        && backendProviderDisplayName == null
    ) {
        return null
    }
    return HostRuntimeMetadata(
        runtimeTarget = runtimeTarget,
        runtimeTargetDisplayName = runtimeTargetDisplayName,
        backendProvider = backendProvider,
        backendProviderDisplayName = backendProviderDisplayName,
    )
}

internal fun JSONObject.decodedHostAccountStatusOrNull(): HostAccountStatus? {
    val statusKey = sequenceOf("status", "state").firstOrNull(::has) ?: return null
    val rawValue = opt(statusKey)
    val normalized = when (rawValue) {
        null,
        JSONObject.NULL -> return null
        is String -> rawValue.trim().takeIf { it.isNotEmpty() }
        is Number,
        is Boolean -> rawValue.toString().trim().takeIf { it.isNotEmpty() }
        else -> return null
    } ?: return null
    return decodeHostAccountStatus(normalized)
}

internal fun decodeHostRateLimitBuckets(resultObject: JSONObject): List<HostRateLimitBucket> {
    val directBuckets = resultObject.arrayOrNull("rateLimits", "rate_limits", "buckets", "limits")
    if (directBuckets != null) {
        return decodeHostRateLimitBuckets(directBuckets)
    }

    val nestedBuckets = resultObject.objectOrNull("rateLimits", "rate_limits", "limits")
        ?.arrayOrNull("items", "buckets", "limits")
    if (nestedBuckets != null) {
        return decodeHostRateLimitBuckets(nestedBuckets)
    }

    return emptyList()
}

fun decodeGitRepoSyncResult(resultObject: JSONObject): GitRepoSyncResult {
    return GitRepoSyncResult(
        repoRoot = resultObject.stringOrNull("repoRoot", "repo_root"),
        currentBranch = resultObject.stringOrNull("branch", "currentBranch", "current_branch"),
        trackingBranch = resultObject.stringOrNull("tracking", "trackingBranch", "tracking_branch"),
        isDirty = resultObject.optBoolean("dirty"),
        aheadCount = resultObject.optInt("ahead"),
        behindCount = resultObject.optInt("behind"),
        localOnlyCommitCount = resultObject.optInt("localOnlyCommitCount", resultObject.optInt("local_only_commit_count")),
        state = resultObject.stringOrNull("state") ?: "up_to_date",
        canPush = resultObject.optBoolean("canPush", resultObject.optBoolean("can_push")),
        isPublishedToRemote = resultObject.optBoolean(
            "publishedToRemote",
            resultObject.optBoolean("published_to_remote"),
        ),
        files = decodeGitChangedFiles(resultObject.optJSONArray("files") ?: JSONArray()),
        repoDiffTotals = decodeGitDiffTotals(
            resultObject.optJSONObject("diff")
                ?: resultObject.optJSONObject("repoDiffTotals")
                ?: resultObject.optJSONObject("repo_diff_totals")
        ),
    )
}

fun decodeGitRepoDiffResult(resultObject: JSONObject): GitRepoDiffResult {
    return GitRepoDiffResult(
        patch = resultObject.stringOrNull("patch") ?: "",
    )
}

fun decodeGitCommitResult(resultObject: JSONObject): GitCommitResult {
    return GitCommitResult(
        commitHash = resultObject.stringOrNull("hash", "commitHash", "commit_hash") ?: "",
        branch = resultObject.stringOrNull("branch") ?: "",
        summary = resultObject.stringOrNull("summary") ?: "",
    )
}

fun decodeGitPushResult(resultObject: JSONObject): GitPushResult {
    return GitPushResult(
        branch = resultObject.stringOrNull("branch") ?: "",
        remote = resultObject.stringOrNull("remote"),
        status = resultObject.optJSONObject("status")?.let(::decodeGitRepoSyncResult),
    )
}

fun decodeGitPullResult(resultObject: JSONObject): GitPullResult {
    return GitPullResult(
        success = resultObject.optBoolean("success"),
        status = resultObject.optJSONObject("status")?.let(::decodeGitRepoSyncResult),
    )
}

fun decodeGitCheckoutResult(resultObject: JSONObject): GitCheckoutResult {
    return GitCheckoutResult(
        currentBranch = resultObject.stringOrNull("current", "currentBranch", "current_branch") ?: "",
        tracking = resultObject.stringOrNull("tracking"),
        status = resultObject.optJSONObject("status")?.let(::decodeGitRepoSyncResult),
    )
}

fun decodeGitCreateBranchResult(resultObject: JSONObject): GitCreateBranchResult {
    return GitCreateBranchResult(
        branch = resultObject.stringOrNull("branch") ?: "",
        status = resultObject.optJSONObject("status")?.let(::decodeGitRepoSyncResult),
    )
}

fun decodeGitCreateWorktreeResult(resultObject: JSONObject): GitCreateWorktreeResult {
    return GitCreateWorktreeResult(
        branch = resultObject.stringOrNull("branch") ?: "",
        worktreePath = resultObject.stringOrNull("worktreePath", "worktree_path") ?: "",
        alreadyExisted = resultObject.optBoolean("alreadyExisted", resultObject.optBoolean("already_existed")),
    )
}

fun decodeGitRemoveWorktreeResult(resultObject: JSONObject): GitRemoveWorktreeResult {
    return GitRemoveWorktreeResult(
        success = resultObject.optBoolean("success"),
    )
}

fun decodeGitBranchesResult(resultObject: JSONObject): GitBranchesResult {
    return GitBranchesResult(
        branches = decodeGitBranches(resultObject.optJSONArray("branches") ?: JSONArray()),
        branchesCheckedOutElsewhere = decodeGitBranches(
            resultObject.optJSONArray("branchesCheckedOutElsewhere")
                ?: resultObject.optJSONArray("branches_checked_out_elsewhere")
                ?: JSONArray()
        ).toSet(),
        worktreePathByBranch = decodeStringMap(
            resultObject.optJSONObject("worktreePathByBranch")
                ?: resultObject.optJSONObject("worktree_path_by_branch")
        ),
        localCheckoutPath = resultObject.stringOrNull("localCheckoutPath", "local_checkout_path"),
        currentBranch = resultObject.stringOrNull("current", "currentBranch", "current_branch"),
        defaultBranch = resultObject.stringOrNull("default", "defaultBranch", "default_branch"),
    )
}

fun decodeGitBranchesWithStatusResult(resultObject: JSONObject): GitBranchesWithStatusResult {
    val branches = decodeGitBranchesResult(resultObject)
    return GitBranchesWithStatusResult(
        branches = branches.branches,
        branchesCheckedOutElsewhere = branches.branchesCheckedOutElsewhere,
        worktreePathByBranch = branches.worktreePathByBranch,
        localCheckoutPath = branches.localCheckoutPath,
        currentBranch = branches.currentBranch,
        defaultBranch = branches.defaultBranch,
        status = resultObject.optJSONObject("status")?.let(::decodeGitRepoSyncResult),
    )
}

fun decodeWorkspaceRecentState(resultObject: JSONObject): WorkspaceRecentState {
  return WorkspaceRecentState(
    activeCwd = resultObject.stringOrNull("activeCwd", "active_cwd"),
    recentWorkspaces = decodeWorkspacePathSummaries(
      resultObject.optJSONArray("recentWorkspaces")
        ?: resultObject.optJSONArray("recent_workspaces")
        ?: JSONArray()
    ),
  )
}

fun decodeWorkspaceBrowseResult(resultObject: JSONObject): WorkspaceBrowseResult {
  return WorkspaceBrowseResult(
    requestedPath = resultObject.stringOrNull("requestedPath", "requested_path"),
    parentPath = resultObject.stringOrNull("parentPath", "parent_path"),
    entries = decodeWorkspaceDirectoryEntries(resultObject.optJSONArray("entries") ?: JSONArray()),
    rootEntries = decodeWorkspaceDirectoryEntries(
      resultObject.optJSONArray("rootEntries")
        ?: resultObject.optJSONArray("root_entries")
        ?: JSONArray()
    ),
    activeCwd = resultObject.stringOrNull("activeCwd", "active_cwd"),
    recentWorkspaces = decodeWorkspacePathSummaries(
      resultObject.optJSONArray("recentWorkspaces")
        ?: resultObject.optJSONArray("recent_workspaces")
        ?: JSONArray()
    ),
  )
}

fun decodeWorkspaceActivationStatus(resultObject: JSONObject): WorkspaceActivationStatus {
  return WorkspaceActivationStatus(
    hostId = resultObject.stringOrNull("hostId", "host_id"),
    macDeviceId = resultObject.stringOrNull("macDeviceId", "mac_device_id"),
    relayUrl = resultObject.stringOrNull("relayUrl", "relay_url"),
    relayStatus = resultObject.stringOrNull("relayStatus", "relay_status"),
    currentCwd = resultObject.stringOrNull("currentCwd", "current_cwd"),
    workspaceActive = resultObject.optBoolean("workspaceActive", resultObject.optBoolean("workspace_active")),
    hasTrustedPhone = resultObject.optBoolean("hasTrustedPhone", resultObject.optBoolean("has_trusted_phone")),
  )
}

fun decodeFuzzyFileMatches(resultObject: JSONObject): List<FuzzyFileMatch> {
    val files = resultObject.optJSONArray("files")
        ?: resultObject.optJSONObject("result")?.optJSONArray("files")
        ?: resultObject.optJSONObject("data")?.optJSONArray("files")
        ?: resultObject.optJSONArray("data")
        ?: JSONArray()
    val decoded = mutableListOf<FuzzyFileMatch>()
    for (index in 0 until files.length()) {
        val item = files.optJSONObject(index) ?: continue
        val path = item.stringOrNull("path") ?: continue
        val fileName = item.stringOrNull("fileName", "file_name", "name")
            ?: path.substringAfterLast('/').substringAfterLast('\\')
        decoded += FuzzyFileMatch(
            root = item.stringOrNull("root"),
            path = path,
            fileName = fileName,
            score = ((item.opt("score") as? Number) ?: (item.opt("rank") as? Number))
                ?.toDouble(),
            indices = decodeIndices(item.optJSONArray("indices")),
        )
    }
    return decoded
}

fun decodeSkillMetadata(resultObject: JSONObject): List<SkillMetadata> {
    val candidates = mutableListOf<JSONObject>()
    resultObject.optJSONArray("data")?.let { topLevel ->
        for (index in 0 until topLevel.length()) {
            topLevel.optJSONObject(index)?.let(candidates::add)
        }
    }
    resultObject.optJSONArray("skills")?.let { direct ->
        for (index in 0 until direct.length()) {
            direct.optJSONObject(index)?.let(candidates::add)
        }
    }
    resultObject.optJSONObject("result")?.optJSONArray("data")?.let { nested ->
        for (index in 0 until nested.length()) {
            nested.optJSONObject(index)?.let(candidates::add)
        }
    }

    val decoded = mutableListOf<SkillMetadata>()
    candidates.forEach { candidate ->
        val nestedSkills = candidate.optJSONArray("skills")
        if (nestedSkills != null) {
            for (skillIndex in 0 until nestedSkills.length()) {
                nestedSkills.optJSONObject(skillIndex)?.let { skill ->
                    decodeSingleSkillMetadata(skill)?.let(decoded::add)
                }
            }
        } else {
            decodeSingleSkillMetadata(candidate)?.let(decoded::add)
        }
    }
    return decoded
        .groupBy { it.normalizedName }
        .values
        .mapNotNull { bucket -> bucket.firstOrNull { it.enabled } ?: bucket.firstOrNull() }
        .sortedBy { it.name.lowercase(Locale.US) }
}

private fun decodeWorkspacePathSummaries(items: JSONArray): List<WorkspacePathSummary> {
  val decoded = mutableListOf<WorkspacePathSummary>()
  for (index in 0 until items.length()) {
    val item = items.optJSONObject(index) ?: continue
    val path = item.stringOrNull("path") ?: continue
    decoded += WorkspacePathSummary(
      path = path,
      name = item.stringOrNull("name") ?: path,
      isActive = item.optBoolean("isActive", item.optBoolean("is_active")),
    )
  }
  return decoded
}

private fun decodeWorkspaceDirectoryEntries(items: JSONArray): List<WorkspaceDirectoryEntry> {
  val decoded = mutableListOf<WorkspaceDirectoryEntry>()
  for (index in 0 until items.length()) {
    val item = items.optJSONObject(index) ?: continue
    val path = item.stringOrNull("path") ?: continue
    decoded += WorkspaceDirectoryEntry(
      path = path,
      name = item.stringOrNull("name") ?: path,
      isDirectory = item.optBoolean("isDirectory", item.optBoolean("is_directory", true)),
      isActive = item.optBoolean("isActive", item.optBoolean("is_active")),
      source = item.stringOrNull("source") ?: "browse",
    )
  }
  return decoded
}

private fun decodeGitDiffTotals(resultObject: JSONObject?): GitDiffTotals? {
    resultObject ?: return null
    val totals = GitDiffTotals(
        additions = resultObject.optInt("additions"),
        deletions = resultObject.optInt("deletions"),
        binaryFiles = resultObject.optInt("binaryFiles", resultObject.optInt("binary_files")),
    )
    return totals.takeIf { it.hasChanges }
}

private fun decodeGitChangedFiles(items: JSONArray): List<GitChangedFile> {
    val decoded = mutableListOf<GitChangedFile>()
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val path = item.stringOrNull("path") ?: continue
        decoded += GitChangedFile(
            path = path,
            status = item.stringOrNull("status") ?: "",
        )
    }
    return decoded
}

private fun decodeGitBranches(items: JSONArray): List<String> {
    val decoded = mutableListOf<String>()
    for (index in 0 until items.length()) {
        val branch = items.optString(index).trim()
        if (branch.isNotEmpty()) {
            decoded += branch
        }
    }
    return decoded
}

private fun decodeStringMap(resultObject: JSONObject?): Map<String, String> {
    resultObject ?: return emptyMap()
    val decoded = linkedMapOf<String, String>()
    val keys = resultObject.keys()
    while (keys.hasNext()) {
        val key = keys.next().trim()
        val value = resultObject.optString(key).trim()
        if (key.isNotEmpty() && value.isNotEmpty()) {
            decoded[key] = value
        }
    }
    return decoded
}

private fun decodeReasoningEffortOptions(items: JSONArray): List<ReasoningEffortOption> {
    val decoded = mutableListOf<ReasoningEffortOption>()
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val effort = item.stringOrNull("reasoningEffort", "reasoning_effort") ?: continue
        decoded += ReasoningEffortOption(
            reasoningEffort = effort,
            description = item.stringOrNull("description") ?: "",
        )
    }
    return decoded
}

private fun decodeHostRateLimitBuckets(items: JSONArray): List<HostRateLimitBucket> {
    val decoded = mutableListOf<HostRateLimitBucket>()
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val name = item.stringOrNull("name", "id", "bucket", "scope", "label")
            ?: "limit-${index + 1}"
        decoded += HostRateLimitBucket(
            name = name,
            remaining = item.optNumber("remaining", "remaining_tokens", "remainingRequests"),
            limit = item.optNumber("limit", "max", "quota"),
            used = item.optNumber("used", "consumed", "used_tokens"),
            resetsAtEpochMs = parseTimestamp(
                item.opt("resetsAt").takeUnless { it == null }
                    ?: item.opt("resets_at").takeUnless { it == null }
                    ?: item.opt("resetAt").takeUnless { it == null }
                    ?: item.opt("reset_at").takeUnless { it == null }
            ),
        )
    }
    return decoded
}

private fun decodeHostAccountStatus(rawValue: String?): HostAccountStatus {
    return when (rawValue?.trim()?.lowercase(Locale.US)) {
        "authenticated", "logged_in", "loggedin", "connected" -> HostAccountStatus.AUTHENTICATED
        "pending_login", "pending", "login_pending", "loginpending" -> HostAccountStatus.LOGIN_PENDING
        "expired", "needs_reauth", "needsreauth", "reauth_required" -> HostAccountStatus.EXPIRED
        "not_logged_in", "notloggedin", "signed_out", "logged_out", "unauthenticated" -> {
            HostAccountStatus.NOT_LOGGED_IN
        }
        "unavailable", "offline" -> HostAccountStatus.UNAVAILABLE
        else -> HostAccountStatus.UNKNOWN
    }
}

private fun decodeSingleSkillMetadata(item: JSONObject): SkillMetadata? {
    val name = item.stringOrNull("name") ?: return null
    return SkillMetadata(
        name = name,
        description = item.stringOrNull("description"),
        path = item.stringOrNull("path"),
        scope = item.stringOrNull("scope"),
        enabled = item.optBoolean("enabled", true),
    )
}

private fun JSONObject.optNumber(vararg keys: String): Int? {
    keys.forEach { key ->
        val value = opt(key)
        when (value) {
            is Number -> return value.toInt()
            is String -> value.trim().toIntOrNull()?.let { return it }
        }
    }
    return null
}

private fun decodeIndices(items: JSONArray?): List<Int> {
    if (items == null) {
        return emptyList()
    }
    val decoded = mutableListOf<Int>()
    for (index in 0 until items.length()) {
        val value = items.opt(index)
        when (value) {
            is Number -> decoded += value.toInt()
        }
    }
    return decoded
}

private fun decodeItemText(itemObject: JSONObject): String {
    val content = itemObject.optJSONArray("content")
    val parts = mutableListOf<String>()
    if (content != null) {
        for (index in 0 until content.length()) {
            val value = content.optJSONObject(index) ?: continue
            when (normalizeItemType(value.optString("type"))) {
                "text", "inputtext", "outputtext", "message" -> {
                    value.stringOrNull("text", "delta")?.let(parts::add)
                }

                "file", "inputfile", "filepath" -> {
                    val filePath = value.stringOrNull("path", "file", "file_path", "filePath", "name")
                    if (!filePath.isNullOrBlank()) {
                        parts += "@$filePath"
                    }
                }

                "mention" -> {
                    val mentionPath = value.stringOrNull("path", "name")
                    if (!mentionPath.isNullOrBlank()) {
                        parts += "@$mentionPath"
                    }
                }

                "skill" -> {
                    val skill = value.stringOrNull("id", "name")
                    if (!skill.isNullOrBlank()) {
                        parts += "\$$skill"
                    }
                }
            }
        }
    }
    val combined = parts.joinToString("\n").trim()
    if (combined.isNotEmpty()) {
        return combined
    }
    return itemObject.stringOrNull("text", "message", "summary") ?: ""
}

private fun decodeImageAttachments(itemObject: JSONObject): List<io.androdex.android.model.ImageAttachment> {
    val content = itemObject.optJSONArray("content") ?: return emptyList()
    val attachments = mutableListOf<io.androdex.android.model.ImageAttachment>()
    for (index in 0 until content.length()) {
        val value = content.optJSONObject(index) ?: continue
        when (normalizeItemType(value.optString("type"))) {
            "image", "localimage", "inputimage" -> {
                val sourceUrl = value.stringOrNull("url", "image_url", "path")
                val payloadDataUrl = sourceUrl
                    ?.trim()
                    ?.takeIf { it.startsWith("data:image", ignoreCase = true) }
                val attachment = when {
                    payloadDataUrl != null -> {
                        val payloadBytes = decodeDataUrlImageData(payloadDataUrl) ?: continue
                        buildImageAttachmentFromBytes(payloadBytes, sourceUrl = sourceUrl)
                            ?.copy(payloadDataUrl = payloadDataUrl, sourceUrl = sourceUrl)
                    }

                    !value.stringOrNull("thumbnailBase64JPEG", "thumbnail_base64_jpeg").isNullOrBlank() -> {
                        io.androdex.android.model.ImageAttachment(
                            thumbnailBase64Jpeg = value.stringOrNull(
                                "thumbnailBase64JPEG",
                                "thumbnail_base64_jpeg",
                            ).orEmpty(),
                            payloadDataUrl = null,
                            sourceUrl = sourceUrl,
                        )
                    }

                    sourceUrl != null -> {
                        io.androdex.android.model.ImageAttachment(
                            thumbnailBase64Jpeg = "",
                            payloadDataUrl = null,
                            sourceUrl = sourceUrl,
                        )
                    }

                    else -> null
                } ?: continue
                attachments += attachment
            }
        }
    }
    return attachments
}

internal fun decodeReasoningText(itemObject: JSONObject): String {
    return itemObject.stringOrNull("summary", "text", "message")
        ?: itemObject.optJSONObject("summary")?.stringOrNull("text")
        ?: "Thinking..."
}

private data class FileChangeData(
    val status: String,
    val path: String?,
    val diff: String?,
    val displayText: String,
)

internal data class DecodedPlanContent(
    val text: String,
    val explanation: String?,
    val steps: List<PlanStep>?,
)

internal data class DecodedCommandExecutionContent(
    val status: String,
    val command: String?,
    val text: String,
    val execution: ExecutionContent,
)

internal data class DecodedExecutionStyleContent(
    val status: String,
    val text: String,
    val execution: ExecutionContent,
)

private fun decodeFileChangeStructured(itemObject: JSONObject): FileChangeData {
    val status = itemObject.stringOrNull("status") ?: "completed"
    val path = itemObject.stringOrNull("path", "file", "file_path", "filePath")
    val directDiff = itemObject.stringOrNull("diff", "unified_diff", "unifiedDiff", "patch")
    val summary = itemObject.stringOrNull("summary", "message", "text")

    val displayText = if (!directDiff.isNullOrBlank()) {
        "Status: $status\n\n$directDiff"
    } else {
        listOfNotNull(
            "Status: $status",
            path?.let { "Path: $it" },
            summary,
        ).joinToString("\n\n")
    }

    return FileChangeData(
        status = status,
        path = path,
        diff = directDiff,
        displayText = displayText,
    )
}

internal fun decodePlanContent(itemObject: JSONObject): DecodedPlanContent {
    val explanation = itemObject.stringOrNull("explanation", "summary", "message", "text")
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "Planning..." }
    val steps = itemObject.arrayOrNull("steps", "items")
    if (steps != null && steps.length() > 0) {
        val planSteps = decodePlanSteps(steps)
        if (planSteps.isNotEmpty()) {
            val fallbackText = planSteps.joinToString("\n") { step ->
                if (step.status.isNullOrBlank()) step.text else "[${step.status}] ${step.text}"
            }
            return DecodedPlanContent(
                text = explanation ?: fallbackText,
                explanation = explanation,
                steps = planSteps,
            )
        }
    }
    return DecodedPlanContent(
        text = explanation ?: "Plan updated",
        explanation = explanation,
        steps = null,
    )
}

internal fun isCommandExecutionItemType(rawType: String?): Boolean {
    val normalized = normalizeItemType(rawType)
    return normalized == "commandexecution"
        || normalized == "commandexec"
        || normalized == "terminalcommand"
        || normalized == "terminalcommandexecution"
        || normalized.startsWith("commandexecution")
        || normalized.startsWith("commandexec")
}

internal fun isExecutionStyleItemType(rawType: String?): Boolean {
    return executionKindFromRawType(rawType)?.let { it != ExecutionKind.COMMAND } == true
}

internal fun decodeCommandExecutionContent(itemObject: JSONObject): DecodedCommandExecutionContent {
    val status = itemObject.stringOrNull("status") ?: "completed"
    val command = itemObject.stringOrNull(
        "command",
        "cmd",
        "raw_command",
        "rawCommand",
        "commandLine",
        "command_line",
    )
    val textPayload = decodeStructuredExecutionText(itemObject)
    val normalizedCommand = command?.trim()?.takeIf { it.isNotEmpty() }
        ?: textPayload.output?.trim()?.takeIf {
            it.isNotEmpty() && !it.contains('\n')
        }
    val execution = ExecutionContent(
        kind = ExecutionKind.COMMAND,
        title = normalizedCommand ?: "command",
        status = status,
        summary = textPayload.summary,
        output = textPayload.output,
        details = buildExecutionDetails(itemObject, includeCommand = false),
    )
    val text = buildExecutionDisplayText(
        label = execution.label,
        title = execution.title,
        summary = execution.summary,
        output = execution.output,
        status = status,
    )
    return DecodedCommandExecutionContent(
        status = status,
        command = normalizedCommand,
        text = text,
        execution = execution,
    )
}

internal fun decodeExecutionStyleContent(itemObject: JSONObject): DecodedExecutionStyleContent {
    val rawType = itemObject.stringOrNull("type")
    val kind = executionKindFromRawType(rawType) ?: ExecutionKind.ACTIVITY
    val status = itemObject.stringOrNull("status") ?: "completed"
    val textPayload = decodeStructuredExecutionText(itemObject)
    val title = buildExecutionTitle(
        kind = kind,
        rawType = rawType,
        itemObject = itemObject,
        fallbackSummary = textPayload.summary,
    )
    val execution = ExecutionContent(
        kind = kind,
        title = title,
        status = status,
        summary = textPayload.summary?.takeUnless { it.equals(title, ignoreCase = true) },
        output = textPayload.output,
        details = buildExecutionDetails(itemObject, includeCommand = true),
    )
    return DecodedExecutionStyleContent(
        status = status,
        text = buildExecutionDisplayText(
            label = execution.label,
            title = execution.title,
            summary = execution.summary,
            output = execution.output,
            status = status,
        ),
        execution = execution,
    )
}

private data class StructuredExecutionText(
    val summary: String?,
    val output: String?,
)

private fun decodeStructuredExecutionText(itemObject: JSONObject): StructuredExecutionText {
    val summary = itemObject.stringOrNull("summary", "message")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val explicitOutput = itemObject.stringOrNull("output", "delta")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val contentText = decodeItemText(itemObject)
        .trim()
        .takeIf { it.isNotEmpty() }
    val output = explicitOutput ?: when {
        contentText == null -> itemObject.stringOrNull("output", "delta")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        summary == null -> contentText
        contentText.equals(summary, ignoreCase = false) -> null
        else -> contentText
    }
    val fallbackSummary = itemObject.stringOrNull("text")
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals(output, ignoreCase = false) }
    return StructuredExecutionText(
        summary = summary ?: fallbackSummary,
        output = output,
    )
}

private fun buildExecutionDisplayText(
    label: String,
    title: String,
    summary: String?,
    output: String?,
    status: String,
): String {
    val statusLabel = status.replace('_', ' ')
        .trim()
        .replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
    return listOfNotNull(
        "$statusLabel: $title",
        summary?.takeIf { !it.equals(title, ignoreCase = true) },
        output,
    ).joinToString("\n\n")
        .trim()
        .ifBlank { "$label updated" }
}

private fun executionKindFromRawType(rawType: String?): ExecutionKind? {
    val normalized = normalizeItemType(rawType)
    return when {
        normalized.isEmpty() -> null
        isCommandExecutionItemType(normalized) -> ExecutionKind.COMMAND
        normalized.contains("review") -> ExecutionKind.REVIEW
        normalized.contains("compaction") || normalized.contains("compact") -> ExecutionKind.COMPACTION
        normalized.contains("rollback") -> ExecutionKind.ROLLBACK
        normalized.contains("backgroundterminal") || normalized.contains("terminalclean") || normalized.contains("clean") -> ExecutionKind.CLEANUP
        normalized.contains("execution") || normalized.contains("activity") -> ExecutionKind.ACTIVITY
        else -> null
    }
}

private fun buildExecutionTitle(
    kind: ExecutionKind,
    rawType: String?,
    itemObject: JSONObject,
    fallbackSummary: String?,
): String {
    if (kind == ExecutionKind.REVIEW) {
        val target = itemObject.objectOrNull("target")
        val targetType = target?.stringOrNull("type")
            ?: itemObject.stringOrNull("targetType", "target_type")
        val branch = target?.stringOrNull("branch", "baseBranch", "base_branch")
            ?: itemObject.stringOrNull("branch", "baseBranch", "base_branch")
        return when {
            targetType.equals("baseBranch", ignoreCase = true) && !branch.isNullOrBlank() -> "Review against $branch"
            targetType.equals("uncommittedChanges", ignoreCase = true) -> "Review current changes"
            !fallbackSummary.isNullOrBlank() -> fallbackSummary
            else -> "Code review"
        }
    }

    return itemObject.stringOrNull("title", "name")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: fallbackSummary
        ?: when (kind) {
            ExecutionKind.COMMAND -> "command"
            ExecutionKind.REVIEW -> "Code review"
            ExecutionKind.COMPACTION -> "Compact thread"
            ExecutionKind.ROLLBACK -> "Rollback thread"
            ExecutionKind.CLEANUP -> "Clean background terminals"
            ExecutionKind.ACTIVITY -> humanizeExecutionType(rawType)
        }
}

private fun buildExecutionDetails(
    itemObject: JSONObject,
    includeCommand: Boolean,
): List<ExecutionDetail> {
    val details = mutableListOf<ExecutionDetail>()

    fun append(label: String, value: String?, isMonospace: Boolean = false) {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (details.any { it.label == label && it.value == normalized }) {
            return
        }
        details += ExecutionDetail(label = label, value = normalized, isMonospace = isMonospace)
    }

    if (includeCommand) {
        append(
            label = "Command",
            value = itemObject.stringOrNull(
                "command",
                "cmd",
                "raw_command",
                "rawCommand",
                "commandLine",
                "command_line",
            ),
            isMonospace = true,
        )
    }
    append("Working directory", itemObject.stringOrNull("cwd", "workingDirectory", "working_directory"), true)
    append(
        "Exit code",
        itemObject.stringOrNull("exitCode", "exit_code"),
        true,
    )
    append("Branch", itemObject.stringOrNull("branch", "baseBranch", "base_branch"), true)
    append("Reason", itemObject.stringOrNull("reason"))
    append("Target", itemObject.stringOrNull("targetType", "target_type"))
    append("Duration", decodeExecutionDuration(itemObject))

    itemObject.objectOrNull("target")?.let { target ->
        append("Target", target.stringOrNull("type"))
        append("Branch", target.stringOrNull("branch", "baseBranch", "base_branch"), true)
    }

    return details
}

private fun decodeExecutionDuration(itemObject: JSONObject): String? {
    val durationMs = itemObject.optLong("durationMs", itemObject.optLong("duration_ms", Long.MIN_VALUE))
        .takeIf { it != Long.MIN_VALUE }
    val durationSeconds = itemObject.optDouble("durationSeconds", itemObject.optDouble("duration_seconds", Double.NaN))
        .takeIf { !it.isNaN() }
    val totalMs = when {
        durationMs != null -> durationMs
        durationSeconds != null -> (durationSeconds * 1000).toLong()
        else -> null
    } ?: return null
    return when {
        totalMs >= 60_000L -> {
            val minutes = totalMs / 60_000L
            val seconds = (totalMs % 60_000L) / 1000L
            "${minutes}m ${seconds}s"
        }
        totalMs >= 1_000L -> String.format(Locale.US, "%.1fs", totalMs / 1000.0)
        else -> "${totalMs}ms"
    }
}

private fun humanizeExecutionType(rawType: String?): String {
    val value = rawType?.trim()?.takeIf { it.isNotEmpty() } ?: return "Activity"
    val builder = StringBuilder()
    value.forEachIndexed { index, char ->
        when {
            char == '_' || char == '-' || char == '/' -> builder.append(' ')
            char.isUpperCase() && index > 0 && builder.lastOrNull()?.isWhitespace() == false -> {
                builder.append(' ')
                builder.append(char.lowercaseChar())
            }
            else -> builder.append(char.lowercaseChar())
        }
    }
    return builder.toString()
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
        .ifBlank { "Activity" }
}

internal fun decodeThreadTokenUsage(resultObject: JSONObject): ThreadTokenUsage? {
    val usageObject = resultObject.objectOrNull("usage") ?: resultObject
    val tokenLimit = usageObject.optInt("tokenLimit", usageObject.optInt("token_limit", -1))
        .takeIf { it >= 0 }
    val tokensUsed = usageObject.optInt("tokensUsed", usageObject.optInt("tokens_used", -1))
        .takeIf { it >= 0 }
    if (tokenLimit == null || tokensUsed == null) {
        return null
    }
    return ThreadTokenUsage(
        tokensUsed = tokensUsed.coerceAtMost(tokenLimit),
        tokenLimit = tokenLimit,
    )
}

internal fun decodePlanSteps(items: JSONArray): List<PlanStep> {
    val planSteps = mutableListOf<PlanStep>()
    for (index in 0 until items.length()) {
        val step = items.optJSONObject(index) ?: continue
        val text = step.stringOrNull("step", "title", "text") ?: continue
        val status = step.stringOrNull("status")
        planSteps += PlanStep(text = text, status = status)
    }
    return planSteps
}

internal fun extractProtocolItemCandidate(params: JSONObject?): JSONObject? {
    if (params == null) {
        return null
    }

    params.objectOrNull("item")?.let { return it }
    (params.optJSONObject("msg")?.optJSONObject("event") ?: params.optJSONObject("event"))?.let { event ->
        event.optJSONObject("item")?.let { return it }
        if (!event.stringOrNull("type").isNullOrBlank()) {
            return event
        }
    }
    if (!params.stringOrNull("type").isNullOrBlank()) {
        return params
    }
    return null
}

internal fun isSubagentActionItemType(rawType: String?): Boolean {
    val normalized = normalizeItemType(rawType)
    return normalized == "collabagenttoolcall"
        || normalized == "collabtoolcall"
        || normalized.startsWith("collabagentspawn")
        || normalized.startsWith("collabwaiting")
        || normalized.startsWith("collabclose")
        || normalized.startsWith("collabresume")
        || normalized.startsWith("collabagentinteraction")
}

internal fun decodeSubagentActionItem(itemObject: JSONObject): SubagentAction? {
    return decodeSubagentActionItem(itemObject.toRawMap())
}

internal fun decodeSubagentActionSpec(values: Map<String, Any?>): SubagentAction? {
    val receiverThreadIds = decodeSubagentReceiverThreadIds(values)
    val receiverAgents = decodeSubagentReceiverAgents(values, receiverThreadIds)
    val agentStates = decodeSubagentAgentStates(values)
    val tool = values.stringOrNull("tool", "name")
        ?: inferSubagentToolFromType(values)
        ?: "spawnAgent"
    val status = values.stringOrNull("status") ?: "in_progress"
    val prompt = normalizedIdentifier(
        values.stringOrNull("prompt", "task", "message", "instructions", "instruction")
    )
    val model = normalizedIdentifier(
        values.stringOrNull(
            "model",
            "modelName",
            "model_name",
            "requestedModel",
            "requested_model",
            "modelProvider",
            "model_provider",
            "modelProviderId",
            "model_provider_id",
        )
    )

    if (receiverThreadIds.isEmpty() && receiverAgents.isEmpty() && agentStates.isEmpty() && prompt == null && model == null) {
        return null
    }

    return SubagentAction(
        tool = tool,
        status = status,
        prompt = prompt,
        model = model,
        receiverThreadIds = receiverThreadIds,
        receiverAgents = receiverAgents,
        agentStates = agentStates,
    )
}

internal fun decodeSubagentActionItem(itemObject: Map<String, Any?>): SubagentAction? {
    val normalizedType = normalizeItemType(itemObject["type"]?.toString())
    if (isSubagentActionItemType(normalizedType)) {
        return decodeSubagentActionSpec(itemObject)
    }

    itemObject["collaboration"]
        ?.let { it as? Map<*, *> }
        ?.entries
        ?.associate { (key, value) -> key.toString() to value }
        ?.let(::decodeSubagentActionSpec)
        ?.let { return it }

    if (!looksLikeExplicitSubagentPayload(itemObject)) {
        return null
    }

    return decodeSubagentActionSpec(itemObject)
}

private fun looksLikeExplicitSubagentPayload(itemObject: Map<String, Any?>): Boolean {
    if (isRecognizedSubagentToolName(itemObject.stringOrNull("tool"))) {
        return true
    }
    return hasExplicitSubagentRoutingOrStateShape(itemObject)
}

private fun hasExplicitSubagentRoutingOrStateShape(itemObject: Map<String, Any?>): Boolean {
    if (itemObject.stringOrNull(
            "receiverThreadId",
            "receiver_thread_id",
            "newThreadId",
            "new_thread_id",
            "receiverAgentId",
            "receiver_agent_id",
            "newAgentId",
            "new_agent_id",
            "receiverAgentNickname",
            "receiver_agent_nickname",
            "newAgentNickname",
            "new_agent_nickname",
            "receiverAgentRole",
            "receiver_agent_role",
            "newAgentRole",
            "new_agent_role",
        ) != null
    ) {
        return true
    }

    if (!itemObject.listOrNull("receiverThreadIds", "receiver_thread_ids").isNullOrEmpty()) {
        return true
    }
    if (!itemObject.listOrNull("receiverAgents", "receiver_agents").isNullOrEmpty()) {
        return true
    }
    if (itemObject.mapOrNull("agentStates", "agent_states", "agentsStates", "agents_states") != null) {
        return true
    }
    if (!itemObject.listOrNull("agentStatuses", "agent_statuses").isNullOrEmpty()) {
        return true
    }
    return false
}

private fun isRecognizedSubagentToolName(rawValue: String?): Boolean {
    return normalizeItemType(rawValue) in setOf(
        "spawnagent",
        "wait",
        "waitagent",
        "closeagent",
        "resumeagent",
        "sendinput",
    )
}

private fun decodeSubagentReceiverThreadIds(itemObject: Map<String, Any?>): List<String> {
    val plural = itemObject.listOrNull("receiverThreadIds", "receiver_thread_ids", "threadIds", "thread_ids")
    if (plural != null) {
        val values = mutableListOf<String>()
        plural.forEach { rawThreadId ->
            val threadId = normalizedIdentifier(rawThreadId?.toString())
            if (threadId != null && threadId !in values) {
                values += threadId
            }
        }
        if (values.isNotEmpty()) {
            return values
        }
    }

    return listOfNotNull(
        normalizedIdentifier(
            itemObject.stringOrNull(
                "receiverThreadId",
                "receiver_thread_id",
                "threadId",
                "thread_id",
                "newThreadId",
                "new_thread_id",
            )
        )
    )
}

private fun decodeSubagentReceiverAgents(
    itemObject: Map<String, Any?>,
    fallbackThreadIds: List<String>,
): List<SubagentRef> {
    val values = itemObject.listOrNull("receiverAgents", "receiver_agents", "agents")
    if (values.isNullOrEmpty()) {
        return buildSyntheticAgentRefs(itemObject, fallbackThreadIds)
    }

    val agents = mutableListOf<SubagentRef>()
    values.forEachIndexed { index, rawValue ->
        @Suppress("UNCHECKED_CAST")
        val value = rawValue as? Map<String, Any?> ?: return@forEachIndexed
        val threadId = normalizedIdentifier(
            value.stringOrNull(
                "threadId",
                "thread_id",
                "receiverThreadId",
                "receiver_thread_id",
                "newThreadId",
                "new_thread_id",
            ) ?: fallbackThreadIds.getOrNull(index)
        ) ?: return@forEachIndexed

        agents += SubagentRef(
            threadId = threadId,
            agentId = normalizedIdentifier(
                value.stringOrNull(
                    "agentId",
                    "agent_id",
                    "receiverAgentId",
                    "receiver_agent_id",
                    "newAgentId",
                    "new_agent_id",
                    "id",
                )
            ),
            nickname = normalizedIdentifier(
                value.stringOrNull(
                    "agentNickname",
                    "agent_nickname",
                    "receiverAgentNickname",
                    "receiver_agent_nickname",
                    "newAgentNickname",
                    "new_agent_nickname",
                    "nickname",
                    "name",
                )
            ),
            role = normalizedIdentifier(
                value.stringOrNull(
                    "agentRole",
                    "agent_role",
                    "receiverAgentRole",
                    "receiver_agent_role",
                    "newAgentRole",
                    "new_agent_role",
                    "agentType",
                    "agent_type",
                )
            ),
            model = normalizedIdentifier(
                value.stringOrNull(
                    "modelProvider",
                    "model_provider",
                    "modelProviderId",
                    "model_provider_id",
                    "modelName",
                    "model_name",
                    "model",
                )
            ),
            prompt = normalizedIdentifier(
                value.stringOrNull("prompt", "instructions", "instruction", "task", "message")
            ),
        )
    }
    return agents
}

private fun decodeSubagentAgentStates(itemObject: Map<String, Any?>): Map<String, SubagentState> {
    val objectCandidate = itemObject.mapOrNull(
        "statuses",
        "agentsStates",
        "agents_states",
        "agentStates",
        "agent_states",
    )
    if (objectCandidate != null) {
        val states = mutableMapOf<String, SubagentState>()
        objectCandidate.forEach { (rawThreadId, rawStateObject) ->
            @Suppress("UNCHECKED_CAST")
            val stateObject = rawStateObject as? Map<String, Any?> ?: return@forEach
            val threadId = normalizedIdentifier(rawThreadId)
                ?: normalizedIdentifier(stateObject.stringOrNull("threadId", "thread_id"))
                ?: return@forEach
            states[threadId] = SubagentState(
                threadId = threadId,
                status = stateObject.stringOrNull("status") ?: "unknown",
                message = stateObject.stringOrNull("message", "text", "delta", "summary"),
            )
        }
        return states
    }

    val arrayCandidate = itemObject.listOrNull("agentStatuses", "agent_statuses")
        ?: itemObject.listOrNull("statuses", "agentStates", "agent_states")
    if (arrayCandidate != null) {
        val states = mutableMapOf<String, SubagentState>()
        arrayCandidate.forEach { rawEntry ->
            @Suppress("UNCHECKED_CAST")
            val entry = rawEntry as? Map<String, Any?> ?: return@forEach
            val threadId = normalizedIdentifier(
                entry.stringOrNull("threadId", "thread_id", "receiverThreadId", "receiver_thread_id")
            ) ?: return@forEach
            states[threadId] = SubagentState(
                threadId = threadId,
                status = entry.stringOrNull("status") ?: "unknown",
                message = entry.stringOrNull("message", "text", "delta", "summary"),
            )
        }
        return states
    }

    return emptyMap()
}

private fun buildSyntheticAgentRefs(
    itemObject: Map<String, Any?>,
    fallbackThreadIds: List<String>,
): List<SubagentRef> {
    val threadId = fallbackThreadIds.firstOrNull()
        ?: normalizedIdentifier(
            itemObject.stringOrNull(
                "receiverThreadId",
                "receiver_thread_id",
                "threadId",
                "thread_id",
                "newThreadId",
                "new_thread_id",
            )
        )
        ?: return emptyList()

    return listOf(
        SubagentRef(
            threadId = threadId,
            agentId = normalizedIdentifier(
                itemObject.stringOrNull("newAgentId", "new_agent_id", "agentId", "agent_id")
            ),
            nickname = normalizedIdentifier(
                itemObject.stringOrNull(
                    "newAgentNickname",
                    "new_agent_nickname",
                    "agentNickname",
                    "agent_nickname",
                    "receiverAgentNickname",
                    "receiver_agent_nickname",
                )
            ),
            role = normalizedIdentifier(
                itemObject.stringOrNull(
                    "receiverAgentRole",
                    "receiver_agent_role",
                    "newAgentRole",
                    "new_agent_role",
                    "agentRole",
                    "agent_role",
                    "agentType",
                    "agent_type",
                )
            ),
            model = normalizedIdentifier(
                itemObject.stringOrNull(
                    "modelProvider",
                    "model_provider",
                    "modelProviderId",
                    "model_provider_id",
                    "modelName",
                    "model_name",
                    "model",
                )
            ),
            prompt = normalizedIdentifier(
                itemObject.stringOrNull("prompt", "instructions", "instruction", "task", "message")
            ),
        )
    )
}

private fun inferSubagentToolFromType(itemObject: Map<String, Any?>): String? {
    val normalized = itemObject.stringOrNull("type")
        ?.lowercase(Locale.US)
        ?.replace("_", "")
        ?.replace("-", "")
        ?: return null
    return when {
        normalized.contains("spawn") -> "spawnAgent"
        normalized.contains("waiting") || normalized.contains("wait") -> "wait"
        normalized.contains("close") -> "closeAgent"
        normalized.contains("resume") -> "resumeAgent"
        normalized.contains("sendinput") || normalized.contains("interaction") -> "sendInput"
        else -> null
    }
}

private fun normalizedIdentifier(value: String?): String? {
    return value?.trim()?.takeIf { it.isNotEmpty() }
}

private fun Map<String, Any?>.stringOrNull(vararg keys: String): String? {
    keys.forEach { key ->
        val value = this[key] ?: return@forEach
        val normalized = when (value) {
            is String -> value.trim()
            is Number, is Boolean -> value.toString().trim()
            else -> null
        }
        if (!normalized.isNullOrEmpty()) {
            return normalized
        }
    }
    return null
}

private fun Map<String, Any?>.mapOrNull(vararg keys: String): Map<String, Any?>? {
    keys.forEach { key ->
        val value = this[key]
        if (value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, Any?>
        }
    }
    return null
}

private fun Map<String, Any?>.listOrNull(vararg keys: String): List<Any?>? {
    keys.forEach { key ->
        val value = this[key]
        if (value is List<*>) {
            return value
        }
    }
    return null
}

internal fun JSONObject.toRawMap(): Map<String, Any?> {
    val result = linkedMapOf<String, Any?>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        result[key] = opt(key).toRawValue()
    }
    return result
}

private fun JSONArray.toRawList(): List<Any?> {
    val result = mutableListOf<Any?>()
    for (index in 0 until length()) {
        result += opt(index).toRawValue()
    }
    return result
}

private fun Any?.toRawValue(): Any? {
    return when (this) {
        null, JSONObject.NULL -> null
        is JSONObject -> toRawMap()
        is JSONArray -> toRawList()
        else -> this
    }
}
