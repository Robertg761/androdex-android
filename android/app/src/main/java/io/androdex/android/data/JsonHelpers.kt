package io.androdex.android.data

import io.androdex.android.attachment.buildImageAttachmentFromBytes
import io.androdex.android.attachment.decodeDataUrlImageData
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
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
import io.androdex.android.model.ModelOption
import io.androdex.android.model.PlanStep
import io.androdex.android.model.ReasoningEffortOption
import io.androdex.android.model.SkillMetadata
import io.androdex.android.model.SubagentAction
import io.androdex.android.model.SubagentRef
import io.androdex.android.model.SubagentState
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

                "commandexecution" -> {
                    val cmdStatus = itemObject.stringOrNull("status") ?: "completed"
                    val cmdText = itemObject.stringOrNull("command", "cmd", "raw_command", "rawCommand", "message") ?: "command"
                    messages += ConversationMessage(
                        id = itemId ?: UUID.randomUUID().toString(),
                        threadId = threadId,
                        role = ConversationRole.SYSTEM,
                        kind = ConversationKind.COMMAND,
                        text = "${cmdStatus.replaceFirstChar(Char::uppercase)}: $cmdText",
                        createdAtEpochMs = createdAt,
                        turnId = turnId,
                        itemId = itemId,
                        status = cmdStatus,
                        command = cmdText,
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
                    if (!isSubagentActionItemType(itemType)) {
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
    return decodeSubagentActionSpec(itemObject.toRawMap())
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

private fun JSONObject.toRawMap(): Map<String, Any?> {
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
