package io.androdex.android.ui.turn

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androdex.android.applyingSubagentsSelection
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.PlanStep
import io.androdex.android.model.QueuedTurnDraft
import io.androdex.android.model.SubagentThreadPresentation
import io.androdex.android.ui.shared.AgentActivityBanner
import io.androdex.android.ui.shared.BusyIndicator
import io.androdex.android.ui.state.ThreadTimelineUiState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadTimelineScreen(
    state: ThreadTimelineUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onComposerChanged: (String) -> Unit,
    onPlanModeChanged: (Boolean) -> Unit,
    onSubagentsModeChanged: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPauseQueue: () -> Unit,
    onResumeQueue: () -> Unit,
    onRestoreQueuedDraft: (String) -> Unit,
    onRemoveQueuedDraft: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val listState = remember { LazyListState() }
    val coroutineScope = rememberCoroutineScope()
    val showJumpToLatest by remember {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems == 0) {
                false
            } else {
                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisibleIndex < totalItems - 1
            }
        }
    }

    LaunchedEffect(state.threadId, state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding(),
        ) {
            BusyIndicator(state = state.busy)
            AgentActivityBanner(messages = state.messages)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }
                }

                if (showJumpToLatest) {
                    SmallFloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                if (state.messages.isNotEmpty()) {
                                    listState.animateScrollToItem(state.messages.lastIndex)
                                }
                            }
                        },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Jump to latest",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(visible = state.queuedDrafts.isNotEmpty()) {
                QueuedDraftsCard(
                    drafts = state.queuedDrafts,
                    queuePauseMessage = state.queuePauseMessage,
                    canRestoreDrafts = state.canRestoreQueuedDrafts,
                    canPauseQueue = state.canPauseQueue,
                    canResumeQueue = state.canResumeQueue,
                    onPauseQueue = onPauseQueue,
                    onResumeQueue = onResumeQueue,
                    onRestoreDraft = onRestoreQueuedDraft,
                    onRemoveDraft = onRemoveQueuedDraft,
                )
            }

            ComposerBar(
                state = state.composer,
                onTextChange = onComposerChanged,
                onPlanModeChanged = onPlanModeChanged,
                onSubagentsModeChanged = onSubagentsModeChanged,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun QueuedDraftsCard(
    drafts: List<QueuedTurnDraft>,
    queuePauseMessage: String?,
    canRestoreDrafts: Boolean,
    canPauseQueue: Boolean,
    canResumeQueue: Boolean,
    onPauseQueue: () -> Unit,
    onResumeQueue: () -> Unit,
    onRestoreDraft: (String) -> Unit,
    onRemoveDraft: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Queued follow-ups",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${drafts.size} waiting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when {
                    canResumeQueue -> {
                        TextButton(onClick = onResumeQueue) {
                            Text("Resume")
                        }
                    }

                    canPauseQueue -> {
                        TextButton(onClick = onPauseQueue) {
                            Text("Pause")
                        }
                    }
                }
            }

            queuePauseMessage?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }

            drafts.forEachIndexed { index, draft ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = CircleShape,
                        modifier = Modifier.size(22.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (draft.collaborationMode == CollaborationModeKind.PLAN) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text(
                                        text = "PLAN",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (draft.subagentsSelectionEnabled) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text(
                                        text = "SUBAGENTS",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = applyingSubagentsSelection(draft.text, draft.subagentsSelectionEnabled),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    TextButton(
                        onClick = { onRestoreDraft(draft.id) },
                        enabled = canRestoreDrafts,
                    ) {
                        Text("Restore")
                    }

                    TextButton(
                        onClick = { onRemoveDraft(draft.id) },
                    ) {
                        Text("Remove")
                    }
                }

                if (index < drafts.lastIndex) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ConversationMessage) {
    val isUser = message.role == ConversationRole.USER
    val isSystem = message.role == ConversationRole.SYSTEM

    if (isSystem) {
        when (message.kind) {
            ConversationKind.FILE_CHANGE -> FileChangeBubble(message)
            ConversationKind.COMMAND -> CommandBubble(message)
            ConversationKind.SUBAGENT_ACTION -> SubagentActionBubble(message)
            ConversationKind.PLAN -> PlanBubble(message)
            ConversationKind.THINKING -> ThinkingBubble(message)
            else -> SystemCapsule(message)
        }
        return
    }

    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleShape = if (isUser) {
        RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    }
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Surface(
            shape = bubbleShape,
            color = containerColor,
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (message.isStreaming) {
                    StreamingIndicator()
                }

                Text(
                    text = message.text.ifBlank { " " },
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                )

                Text(
                    text = DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(Date(message.createdAtEpochMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun SystemCapsule(message: ConversationMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = message.text.ifBlank { " " },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ThinkingBubble(message: ConversationMessage) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "thinkingPulse",
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { index ->
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            tween(600, delayMillis = index * 150),
                            RepeatMode.Reverse,
                        ),
                        label = "dot$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha)),
                    )
                }
            }
            Text(
                text = message.text.ifBlank { "Thinking..." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FileChangeBubble(message: ConversationMessage) {
    val isCompleted = message.status?.lowercase() == "completed"
    val statusColor = if (isCompleted) Color(0xFF34D399) else Color(0xFFFBBF24)
    var expanded by remember { mutableStateOf(true) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    Text(
                        text = message.filePath
                            ?.substringAfterLast('/')
                            ?.substringAfterLast('\\')
                            ?: "File Change",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = (message.status ?: "changed").replaceFirstChar(Char::uppercase),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            message.filePath?.let { path ->
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                if (!message.diffText.isNullOrBlank()) {
                    DiffView(
                        diffText = message.diffText,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                } else {
                    val summaryText = message.text
                        .removePrefix("Status: ${message.status ?: "completed"}")
                        .removePrefix("\n\n")
                        .removePrefix("Path: ${message.filePath ?: ""}")
                        .removePrefix("\n\n")
                        .trim()
                    if (summaryText.isNotBlank()) {
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffView(
    diffText: String,
    modifier: Modifier = Modifier,
) {
    val addedBg = Color(0xFF22C55E).copy(alpha = 0.12f)
    val removedBg = Color(0xFFEF4444).copy(alpha = 0.12f)
    val addedText = Color(0xFF16A34A)
    val removedText = Color(0xFFDC2626)
    val hunkText = Color(0xFF6366F1)
    val contextText = MaterialTheme.colorScheme.onSurfaceVariant
    val scrollState = rememberScrollState()

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(0.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(vertical = 4.dp),
        ) {
            diffText.lines().forEach { line ->
                val (bgColor, fgColor) = when {
                    line.startsWith("+++") || line.startsWith("---") -> {
                        Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    line.startsWith("+") -> addedBg to addedText
                    line.startsWith("-") -> removedBg to removedText
                    line.startsWith("@@") -> Color.Transparent to hunkText
                    else -> Color.Transparent to contextText
                }

                Text(
                    text = line.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
                    color = fgColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                        .padding(horizontal = 12.dp, vertical = 1.dp),
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun CommandBubble(message: ConversationMessage) {
    val commandStatus = message.status?.lowercase() ?: "completed"
    val isRunning = commandStatus == "running" || commandStatus == "in_progress"
    val isFailed = commandStatus == "failed" || commandStatus == "error"
    val statusColor = when {
        isRunning -> Color(0xFFFBBF24)
        isFailed -> Color(0xFFEF4444)
        else -> Color(0xFF34D399)
    }
    val statusIcon = when {
        isRunning -> ">"
        isFailed -> "x"
        else -> "$"
    }

    Surface(
        color = Color(0xFF1E1E2E),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF5F57)),
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFFBD2E)),
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF28C840)),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    color = statusColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = commandStatus.replaceFirstChar(Char::uppercase),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = statusIcon,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = statusColor,
                )
                Text(
                    text = message.command ?: message.text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = Color(0xFFCDD6F4),
                )
            }

            if (isRunning) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = statusColor,
                    trackColor = Color(0xFF313244),
                )
            }
        }
    }
}

@Composable
private fun PlanBubble(message: ConversationMessage) {
    val planSteps = message.planSteps.orEmpty()
    val completedCount = planSteps.count { it.isCompleted() }
    val activeCount = planSteps.count { it.isInProgress() }
    val planStatus = when {
        planSteps.isNotEmpty() && completedCount == planSteps.size -> "Completed"
        activeCount > 0 -> "In Progress"
        planSteps.isNotEmpty() -> "Pending"
        else -> "Updated"
    }
    val summary = message.planExplanation
        ?.takeIf { it.isNotBlank() }
        ?: message.text.takeIf { it.isNotBlank() && it != "Planning..." }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = "PLAN",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }

                Text(
                    text = planStatus,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )

                Spacer(modifier = Modifier.weight(1f))

                if (planSteps.isNotEmpty()) {
                    Text(
                        text = "$completedCount/${planSteps.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (planSteps.isNotEmpty()) {
                LinearProgressIndicator(
                    progress = { completedCount.toFloat() / planSteps.size.coerceAtLeast(1).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }

            summary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (planSteps.isNotEmpty()) {
                planSteps.forEachIndexed { index, step ->
                    PlanStepRow(index = index + 1, step = step)
                    if (index < planSteps.lastIndex) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            } else if (summary == null) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PlanStepRow(
    index: Int,
    step: PlanStep,
) {
    val isComplete = step.isCompleted()
    val isActive = step.isInProgress()
    val stepColor = when {
        isComplete -> MaterialTheme.colorScheme.primary
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val indicator = when {
        isComplete -> "✓"
        isActive -> "•"
        else -> "$index"
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            color = stepColor.copy(alpha = 0.15f),
            shape = CircleShape,
            modifier = Modifier.size(22.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Text(
                    text = indicator,
                    style = MaterialTheme.typography.labelSmall,
                    color = stepColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isComplete) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            step.status?.takeIf { it.isNotBlank() && !isComplete }?.let {
                Text(
                    text = it.normalizedPlanStatusLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = stepColor,
                )
            }
        }
    }
}

@Composable
private fun SubagentActionBubble(message: ConversationMessage) {
    val action = message.subagentAction
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (message.isStreaming) {
                StreamingIndicator()
            }

            Text(
                text = action?.summaryText ?: message.text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.SemiBold,
            )

            action?.let { subagentAction ->
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CapsuleLabel(text = subagentAction.tool, emphasized = true)
                    CapsuleLabel(text = subagentAction.status)
                    subagentAction.model?.let { CapsuleLabel(text = it) }
                }

                subagentAction.prompt?.let { prompt ->
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    )
                }

                subagentAction.agentRows.forEach { row ->
                    SubagentAgentRow(row)
                }
            }
        }
    }
}

@Composable
private fun SubagentAgentRow(row: SubagentThreadPresentation) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.displayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                row.fallbackStatus?.let { CapsuleLabel(text = it) }
            }

            val detailParts = listOfNotNull(
                row.model?.let { model ->
                    if (row.modelIsRequestedHint) "$model requested" else model
                },
                row.fallbackMessage,
            )
            if (detailParts.isNotEmpty()) {
                Text(
                    text = detailParts.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CapsuleLabel(text: String, emphasized: Boolean = false) {
    Surface(
        color = if (emphasized) {
            MaterialTheme.colorScheme.secondary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) {
                MaterialTheme.colorScheme.onSecondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun PlanStep.isCompleted(): Boolean {
    return status?.trim()?.lowercase() in setOf("completed", "done", "complete")
}

private fun PlanStep.isInProgress(): Boolean {
    return status?.trim()?.lowercase() in setOf("in_progress", "active", "running")
}

private fun String.normalizedPlanStatusLabel(): String {
    return trim()
        .replace('_', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase()
                } else {
                    char.toString()
                }
            }
        }
}

@Composable
private fun StreamingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "streaming")

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { index ->
                val dotScale by infiniteTransition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(
                        tween(500, delayMillis = index * 120),
                        RepeatMode.Reverse,
                    ),
                    label = "streamDot$index",
                )
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .scale(dotScale)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Writing",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
