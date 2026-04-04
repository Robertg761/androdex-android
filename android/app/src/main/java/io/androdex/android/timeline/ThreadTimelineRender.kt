package io.androdex.android.timeline

import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole

data class ThreadTimelineRenderItem(
    val message: ConversationMessage,
    val isFirstInGroup: Boolean,
    val isLastInGroup: Boolean,
)

data class ThreadTimelineRenderSnapshot(
    val threadId: String,
    val messageRevision: Long,
    val items: List<ThreadTimelineRenderItem>,
    val agentActivityText: String?,
    val firstMessageIndexByTurnId: Map<String, Int>,
) {
    val messageCount: Int
        get() = items.size

    val latestMessageIndex: Int?
        get() = items.lastIndex.takeIf { it >= 0 }

    companion object {
        fun empty(
            threadId: String,
            messageRevision: Long = 0L,
        ): ThreadTimelineRenderSnapshot {
            return ThreadTimelineRenderSnapshot(
                threadId = threadId,
                messageRevision = messageRevision,
                items = emptyList(),
                agentActivityText = null,
                firstMessageIndexByTurnId = emptyMap(),
            )
        }
    }
}

internal fun buildThreadTimelineRenderSnapshot(
    threadId: String,
    messageRevision: Long,
    messages: List<ConversationMessage>,
): ThreadTimelineRenderSnapshot {
    val items = buildThreadTimelineRenderItems(messages)
    val firstMessageIndexByTurnId = linkedMapOf<String, Int>()
    items.forEachIndexed { index, item ->
        val turnId = item.message.turnId?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
        firstMessageIndexByTurnId.putIfAbsent(turnId, index)
    }
    return ThreadTimelineRenderSnapshot(
        threadId = threadId,
        messageRevision = messageRevision,
        items = items,
        agentActivityText = buildThreadAgentActivityText(messages),
        firstMessageIndexByTurnId = firstMessageIndexByTurnId,
    )
}

internal fun buildThreadTimelineRenderItems(messages: List<ConversationMessage>): List<ThreadTimelineRenderItem> {
    if (messages.isEmpty()) return emptyList()
    val result = mutableListOf<ThreadTimelineRenderItem>()
    for (i in messages.indices) {
        val msg = messages[i]
        val isSystem = msg.role == ConversationRole.SYSTEM
        if (isSystem) {
            result.add(
                ThreadTimelineRenderItem(
                    message = msg,
                    isFirstInGroup = true,
                    isLastInGroup = true,
                )
            )
            continue
        }
        val prev = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)
        fun sameGroup(a: ConversationMessage, b: ConversationMessage): Boolean {
            if (a.role == ConversationRole.SYSTEM || b.role == ConversationRole.SYSTEM) return false
            if (a.role != b.role) return false
            if (a.role == ConversationRole.ASSISTANT && (a.kind != ConversationKind.CHAT || b.kind != ConversationKind.CHAT)) {
                return false
            }
            if ((b.createdAtEpochMs - a.createdAtEpochMs) > 180_000L) return false
            return true
        }
        val isSameGroupAsPrev = prev != null && sameGroup(prev, msg)
        val isSameGroupAsNext = next != null && sameGroup(msg, next)
        result.add(
            ThreadTimelineRenderItem(
                message = msg,
                isFirstInGroup = !isSameGroupAsPrev,
                isLastInGroup = !isSameGroupAsNext,
            )
        )
    }
    return result
}

internal fun buildThreadAgentActivityText(messages: List<ConversationMessage>): String? {
    val isStreaming = messages.any { it.isStreaming }
    if (!isStreaming) return null

    val activeSystemMessage = messages.lastOrNull {
        it.role == ConversationRole.SYSTEM && it.isStreaming
    } ?: messages.lastOrNull { it.role == ConversationRole.SYSTEM }

    return when {
        activeSystemMessage == null -> "Agent is writing a response..."
        activeSystemMessage.kind == ConversationKind.FILE_CHANGE -> {
            val fileName = activeSystemMessage.filePath
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
            if (fileName != null) "Edited $fileName" else "Edited files"
        }
        activeSystemMessage.kind == ConversationKind.COMMAND -> {
            val title = activeSystemMessage.execution?.title
                ?: activeSystemMessage.command
                ?: "command"
            "Running: ${title.take(40)}"
        }
        activeSystemMessage.kind == ConversationKind.EXECUTION -> {
            val execution = activeSystemMessage.execution
            when {
                execution == null -> "Running activity..."
                execution.title.isBlank() -> "Running ${execution.label.lowercase()}..."
                else -> "${execution.label}: ${execution.title.take(48)}"
            }
        }
        activeSystemMessage.kind == ConversationKind.SUBAGENT_ACTION -> "Managing subagents..."
        activeSystemMessage.kind == ConversationKind.THINKING -> "Thinking..."
        activeSystemMessage.kind == ConversationKind.PLAN -> "Planning..."
        else -> null
    }
}

internal fun timelineScrollTargetIndex(
    snapshot: ThreadTimelineRenderSnapshot,
    focusedTurnId: String?,
): Int? {
    if (snapshot.messageCount == 0) {
        return null
    }
    val normalizedFocusedTurnId = focusedTurnId?.trim()?.takeIf { it.isNotEmpty() }
    if (normalizedFocusedTurnId == null) {
        return snapshot.latestMessageIndex
    }
    return snapshot.firstMessageIndexByTurnId[normalizedFocusedTurnId]
        ?: snapshot.latestMessageIndex
}

internal fun timelineScrollTargetIndex(
    messages: List<ConversationMessage>,
    focusedTurnId: String?,
): Int? {
    return timelineScrollTargetIndex(
        snapshot = buildThreadTimelineRenderSnapshot(
            threadId = "",
            messageRevision = 0L,
            messages = messages,
        ),
        focusedTurnId = focusedTurnId,
    )
}
