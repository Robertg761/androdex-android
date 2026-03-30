package io.androdex.android.ui.turn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.androdex.android.ComposerReviewTarget
import io.androdex.android.ComposerSlashCommand
import io.androdex.android.model.FuzzyFileMatch
import io.androdex.android.model.SkillMetadata
import io.androdex.android.ui.shared.RemodexDivider
import io.androdex.android.ui.shared.RemodexFloatingComposerShell
import io.androdex.android.ui.shared.RemodexIconButton
import io.androdex.android.ui.shared.remodexExpandVertically
import io.androdex.android.ui.shared.remodexFadeIn
import io.androdex.android.ui.shared.remodexFadeOut
import io.androdex.android.ui.shared.remodexPressedState
import io.androdex.android.ui.shared.remodexShrinkVertically
import io.androdex.android.ui.shared.remodexTween
import io.androdex.android.ui.state.ComposerUiState
import io.androdex.android.ui.theme.RemodexTheme

internal enum class ComposerAccessoryButtonState {
    ADD,
    TUNE,
    CLOSE,
}

internal enum class ComposerSubmitPresentation {
    ICON,
    TEXT,
}

internal fun composerAccessoryButtonState(
    hasActiveModes: Boolean,
    isModePanelVisible: Boolean,
): ComposerAccessoryButtonState = when {
    isModePanelVisible -> ComposerAccessoryButtonState.CLOSE
    hasActiveModes -> ComposerAccessoryButtonState.TUNE
    else -> ComposerAccessoryButtonState.ADD
}

internal fun composerSubmitPresentation(
    submitButtonLabel: String,
    showStop: Boolean,
): ComposerSubmitPresentation {
    return if (!showStop && submitButtonLabel == "Send") {
        ComposerSubmitPresentation.ICON
    } else {
        ComposerSubmitPresentation.TEXT
    }
}

private enum class ComposerAutocompleteKind(val rowHeight: androidx.compose.ui.unit.Dp) {
    FILE(38.dp),
    SKILL(50.dp),
    SLASH(50.dp),
}

private data class ComposerContextChipTone(
    val fill: Color,
    val border: Color,
    val text: Color,
)

@Composable
internal fun ComposerBar(
    state: ComposerUiState,
    activityText: String? = null,
    onTextChange: (String) -> Unit,
    onPlanModeChanged: (Boolean) -> Unit,
    onSubagentsModeChanged: (Boolean) -> Unit,
    onSelectReviewTarget: (ComposerReviewTarget) -> Unit,
    onReviewBaseBranchChanged: (String) -> Unit,
    onRemoveReviewSelection: () -> Unit,
    onSelectFileAutocomplete: (FuzzyFileMatch) -> Unit,
    onRemoveMentionedFile: (String) -> Unit,
    onSelectSkillAutocomplete: (SkillMetadata) -> Unit,
    onRemoveMentionedSkill: (String) -> Unit,
    onSelectSlashCommand: (ComposerSlashCommand) -> Unit,
    onAddCamera: () -> Unit,
    onAddGallery: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenRuntime: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val motion = RemodexTheme.motion

    var showModePanel by rememberSaveable { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }

    val hasActiveModes =
        state.isPlanModeEnabled || state.isSubagentsEnabled || state.isReviewModeEnabled
    val hasActiveContext =
        state.mentionedFiles.isNotEmpty() ||
            state.mentionedSkills.isNotEmpty() ||
            hasActiveModes
    val hasAutocomplete =
        state.isFileAutocompleteVisible ||
            state.isSkillAutocompleteVisible ||
            state.isSlashCommandAutocompleteVisible
    val accessoryButtonState = composerAccessoryButtonState(
        hasActiveModes = hasActiveModes,
        isModePanelVisible = showModePanel,
    )
    val submitPresentation = composerSubmitPresentation(
        submitButtonLabel = state.submitButtonLabel,
        showStop = state.showStop,
    )

    LaunchedEffect(state.isReviewModeEnabled) {
        if (state.isReviewModeEnabled) {
            showModePanel = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = remodexTween(motion.composerMillis)),
    ) {
        RemodexDivider()

        AnimatedVisibility(
            visible = !activityText.isNullOrBlank(),
            enter = remodexFadeIn(motion.microStateMillis) + remodexExpandVertically(motion.microStateMillis),
            exit = remodexFadeOut(motion.microStateMillis) + remodexShrinkVertically(motion.microStateMillis),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = geometry.spacing14,
                        end = geometry.spacing14,
                        top = geometry.spacing8,
                        bottom = geometry.spacing2,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = colors.accentBlue,
                )
                Text(
                    text = activityText ?: "",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = geometry.spacing12, vertical = geometry.spacing4),
        ) {
            ComposerShell(
                focused = inputFocused || hasAutocomplete,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(geometry.spacing6),
                ) {
                    if (state.attachments.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = geometry.spacing8, vertical = geometry.spacing4),
                            verticalArrangement = Arrangement.spacedBy(geometry.spacing6),
                        ) {
                            ComposerAttachmentStrip(
                                attachments = state.attachments,
                                onRemove = onRemoveAttachment,
                            )
                            Text(
                                text = if (state.remainingAttachmentSlots == 1) {
                                    "1 slot left"
                                } else {
                                    "${state.remainingAttachmentSlots} slots left"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = if (state.hasBlockingAttachmentState) {
                                    colors.errorRed
                                } else {
                                    colors.textSecondary
                                },
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = hasActiveContext,
                        enter = remodexFadeIn(motion.composerMillis) + remodexExpandVertically(motion.composerMillis),
                        exit = remodexFadeOut(motion.composerMillis) + remodexShrinkVertically(motion.composerMillis),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = geometry.spacing8),
                            horizontalArrangement = Arrangement.spacedBy(geometry.spacing6),
                        ) {
                            state.mentionedFiles.forEach { file ->
                                ComposerContextChip(
                                    label = file.fileName,
                                    tone = rememberComposerChipTone(colors.accentBlue),
                                    onRemove = { onRemoveMentionedFile(file.id) },
                                )
                            }
                            state.mentionedSkills.forEach { skill ->
                                ComposerContextChip(
                                    label = "$${skill.name}",
                                    tone = rememberComposerChipTone(colors.accentGreen),
                                    onRemove = { onRemoveMentionedSkill(skill.id) },
                                )
                            }
                            if (state.isPlanModeEnabled) {
                                ComposerContextChip(
                                    label = "Plan",
                                    tone = rememberComposerChipTone(colors.accentBlue),
                                    onRemove = { onPlanModeChanged(false) },
                                )
                            }
                            if (state.isSubagentsEnabled) {
                                ComposerContextChip(
                                    label = "Subagents",
                                    tone = rememberComposerChipTone(colors.accentGreen),
                                    onRemove = { onSubagentsModeChanged(false) },
                                )
                            }
                            if (state.isReviewModeEnabled) {
                                ComposerContextChip(
                                    label = "Review",
                                    tone = rememberComposerChipTone(colors.accentOrange),
                                    onRemove = onRemoveReviewSelection,
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = showModePanel,
                        enter = remodexFadeIn(motion.composerMillis) + remodexExpandVertically(motion.composerMillis),
                        exit = remodexFadeOut(motion.composerMillis) + remodexShrinkVertically(motion.composerMillis),
                    ) {
                        ComposerModePanel(
                            state = state,
                            onPlanModeChanged = onPlanModeChanged,
                            onSubagentsModeChanged = onSubagentsModeChanged,
                            onSelectSlashCommand = onSelectSlashCommand,
                            onRemoveReviewSelection = onRemoveReviewSelection,
                            onSelectReviewTarget = onSelectReviewTarget,
                            onReviewBaseBranchChanged = onReviewBaseBranchChanged,
                            onOpenRuntime = onOpenRuntime,
                            onAddCamera = onAddCamera,
                            onAddGallery = onAddGallery,
                        )
                    }

                    when {
                        state.isFileAutocompleteVisible -> {
                            ComposerAutocompletePanel(
                                kind = ComposerAutocompleteKind.FILE,
                            ) {
                                if (state.isFileAutocompleteLoading) {
                                    AutocompleteStatus(
                                        text = "Searching files...",
                                        rowHeight = ComposerAutocompleteKind.FILE.rowHeight,
                                    )
                                } else {
                                    state.fileAutocompleteItems.take(6).forEach { item ->
                                        AutocompleteRow(
                                            title = item.fileName,
                                            subtitle = item.path,
                                            rowHeight = ComposerAutocompleteKind.FILE.rowHeight,
                                            onClick = { onSelectFileAutocomplete(item) },
                                        )
                                    }
                                }
                            }
                        }

                        state.isSkillAutocompleteVisible -> {
                            ComposerAutocompletePanel(
                                kind = ComposerAutocompleteKind.SKILL,
                            ) {
                                if (state.isSkillAutocompleteLoading) {
                                    AutocompleteStatus(
                                        text = "Searching skills...",
                                        rowHeight = ComposerAutocompleteKind.SKILL.rowHeight,
                                    )
                                } else {
                                    state.skillAutocompleteItems.take(6).forEach { skill ->
                                        AutocompleteRow(
                                            title = skill.name,
                                            subtitle = skill.description ?: skill.path,
                                            rowHeight = ComposerAutocompleteKind.SKILL.rowHeight,
                                            onClick = { onSelectSkillAutocomplete(skill) },
                                        )
                                    }
                                }
                            }
                        }

                        state.isSlashCommandAutocompleteVisible -> {
                            ComposerAutocompletePanel(
                                kind = ComposerAutocompleteKind.SLASH,
                            ) {
                                state.slashCommandItems.take(6).forEach { command ->
                                    AutocompleteRow(
                                        title = command.title,
                                        subtitle = "${command.commandToken} • ${command.subtitle}",
                                        rowHeight = ComposerAutocompleteKind.SLASH.rowHeight,
                                        onClick = { onSelectSlashCommand(command) },
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(animationSpec = remodexTween(motion.composerMillis))
                            .padding(
                                start = 12.dp,
                                end = 12.dp,
                                top = 6.dp,
                                bottom = 10.dp,
                            ),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                    ) {
                        ComposerAccessoryButton(
                            state = accessoryButtonState,
                            active = showModePanel || hasActiveModes,
                            enabled = state.inputEnabled || showModePanel || hasActiveModes,
                            onClick = { showModePanel = !showModePanel },
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 32.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (state.text.isBlank()) {
                                Text(
                                    text = state.placeholderText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.inputPlaceholder,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = state.text,
                                onValueChange = onTextChange,
                                enabled = state.inputEnabled,
                                minLines = 1,
                                maxLines = 5,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = if (state.inputEnabled) colors.textPrimary else colors.disabledForeground,
                                ),
                                cursorBrush = SolidColor(colors.accentBlue),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { inputFocused = it.isFocused },
                            )
                        }

                        AnimatedVisibility(
                            visible = state.showStop,
                            enter = remodexFadeIn(motion.microStateMillis),
                            exit = remodexFadeOut(motion.microStateMillis),
                        ) {
                            ComposerActionButton(
                                label = "Stop",
                                enabled = state.stopEnabled,
                                loading = state.isStopping,
                                primary = false,
                                onClick = onStop,
                            )
                        }

                        ComposerSubmitButton(
                            presentation = submitPresentation,
                            label = state.submitButtonLabel,
                            enabled = state.submitEnabled,
                            loading = state.isSubmitting,
                            onClick = onSend,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerShell(
    focused: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RemodexTheme.colors
    val motion = RemodexTheme.motion
    val borderColor by animateColorAsState(
        targetValue = if (focused) {
            colors.accentBlue.copy(alpha = 0.22f)
        } else {
            colors.hairlineDivider
        },
        animationSpec = remodexTween(motion.composerMillis),
        label = "composerShellBorder",
    )

    RemodexFloatingComposerShell(
        modifier = Modifier.animateContentSize(animationSpec = remodexTween(motion.composerMillis)),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
            shape = RoundedCornerShape(RemodexTheme.geometry.cornerComposer),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun ComposerAccessoryButton(
    state: ComposerAccessoryButtonState,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val icon = when (state) {
        ComposerAccessoryButtonState.ADD -> Icons.Default.Add
        ComposerAccessoryButtonState.TUNE -> Icons.Default.Tune
        ComposerAccessoryButtonState.CLOSE -> Icons.Default.Close
    }

    RemodexIconButton(
        onClick = onClick,
        enabled = enabled,
        selected = active,
        contentDescription = "Toggle composer accessories",
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active && enabled) colors.accentBlue else if (enabled) colors.textSecondary else colors.disabledForeground,
            modifier = Modifier.size(geometry.iconSize),
        )
    }
}

@Composable
private fun ComposerSubmitButton(
    presentation: ComposerSubmitPresentation,
    label: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    if (presentation == ComposerSubmitPresentation.ICON) {
        ComposerActionButton(
            label = label,
            enabled = enabled,
            loading = loading,
            primary = true,
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = onClick,
        )
    } else {
        ComposerActionButton(
            label = label,
            enabled = enabled,
            loading = loading,
            primary = true,
            onClick = onClick,
        )
    }
}

@Composable
private fun ComposerActionButton(
    label: String,
    enabled: Boolean,
    loading: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
    icon: (@Composable BoxScope.() -> Unit)? = null,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val shape = if (icon == null) RoundedCornerShape(18.dp) else CircleShape
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor = when {
        !enabled -> colors.disabledFill
        primary -> colors.accentBlue
        else -> colors.secondarySurface
    }
    val contentColor = when {
        !enabled -> colors.disabledForeground
        primary -> colors.primaryButtonForeground
        else -> colors.textPrimary
    }
    val borderColor = when {
        !enabled -> colors.hairlineDivider
        primary -> colors.accentBlue.copy(alpha = 0.22f)
        else -> colors.hairlineDivider
    }

    Surface(
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .remodexPressedState(
                interactionSource = interactionSource,
                enabled = enabled,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = shape,
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Box(
            modifier = Modifier
                .height(36.dp)
                .widthIn(min = if (icon == null) 0.dp else 36.dp)
                .padding(horizontal = if (icon == null) geometry.spacing12 else 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 1.8.dp,
                    color = contentColor,
                )
            } else if (icon != null) {
                Box(content = icon)
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ComposerModePanel(
    state: ComposerUiState,
    onPlanModeChanged: (Boolean) -> Unit,
    onSubagentsModeChanged: (Boolean) -> Unit,
    onSelectSlashCommand: (ComposerSlashCommand) -> Unit,
    onRemoveReviewSelection: () -> Unit,
    onSelectReviewTarget: (ComposerReviewTarget) -> Unit,
    onReviewBaseBranchChanged: (String) -> Unit,
    onOpenRuntime: () -> Unit,
    onAddCamera: () -> Unit,
    onAddGallery: () -> Unit,
) {
    val geometry = RemodexTheme.geometry

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = geometry.spacing8),
        verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
        ) {
            ComposerModeChip(
                label = "Plan",
                subtitle = if (state.isPlanModeEnabled) "Enabled" else null,
                selected = state.isPlanModeEnabled,
                enabled = state.planModeEnabled,
                onClick = { onPlanModeChanged(!state.isPlanModeEnabled) },
            )
            ComposerModeChip(
                label = "Subagents",
                subtitle = if (state.isSubagentsEnabled) "Enabled" else null,
                selected = state.isSubagentsEnabled,
                enabled = state.subagentsEnabled,
                onClick = { onSubagentsModeChanged(!state.isSubagentsEnabled) },
            )
            ComposerModeChip(
                label = "Review",
                subtitle = state.reviewTarget?.title,
                selected = state.isReviewModeEnabled,
                enabled = state.inputEnabled || state.isReviewModeEnabled,
                onClick = {
                    if (state.isReviewModeEnabled) {
                        onRemoveReviewSelection()
                    } else {
                        onSelectSlashCommand(ComposerSlashCommand.REVIEW)
                    }
                },
            )
            ComposerModeChip(
                label = state.runtimeButtonLabel,
                selected = state.runtimeButtonLabel != "Runtime",
                enabled = state.runtimeButtonEnabled,
                onClick = onOpenRuntime,
            )
            ComposerIconModeChip(
                icon = Icons.Default.PhotoCamera,
                contentDescription = "Take photo",
                enabled = state.inputEnabled && state.remainingAttachmentSlots > 0,
                onClick = onAddCamera,
            )
            ComposerIconModeChip(
                icon = Icons.Default.PhotoLibrary,
                contentDescription = "Choose photo",
                enabled = state.inputEnabled && state.remainingAttachmentSlots > 0,
                onClick = onAddGallery,
            )
        }

        if (state.isReviewModeEnabled) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                ) {
                    ReviewTargetChip(
                        title = ComposerReviewTarget.UNCOMMITTED_CHANGES.title,
                        subtitle = ComposerReviewTarget.UNCOMMITTED_CHANGES.subtitle,
                        selected = state.reviewTarget == ComposerReviewTarget.UNCOMMITTED_CHANGES,
                        onClick = { onSelectReviewTarget(ComposerReviewTarget.UNCOMMITTED_CHANGES) },
                    )
                    ReviewTargetChip(
                        title = state.reviewBaseBranchLabel?.let { "Base branch ($it)" }
                            ?: ComposerReviewTarget.BASE_BRANCH.title,
                        subtitle = ComposerReviewTarget.BASE_BRANCH.subtitle,
                        selected = state.reviewTarget == ComposerReviewTarget.BASE_BRANCH,
                        onClick = { onSelectReviewTarget(ComposerReviewTarget.BASE_BRANCH) },
                    )
                }

                if (state.reviewTarget == ComposerReviewTarget.BASE_BRANCH) {
                    ReviewBaseBranchField(
                        value = state.reviewBaseBranchValue,
                        placeholder = state.reviewBaseBranchLabel ?: "main",
                        enabled = state.inputEnabled,
                        onValueChange = onReviewBaseBranchChanged,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerModeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .remodexPressedState(
                interactionSource = interactionSource,
                enabled = enabled,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(16.dp),
        color = when {
            !enabled -> colors.disabledFill
            selected -> colors.accentBlue.copy(alpha = 0.12f)
            else -> colors.secondarySurface.copy(alpha = 0.94f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
                selected -> colors.accentBlue.copy(alpha = 0.18f)
                else -> colors.hairlineDivider
            }
        ),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.spacing12,
                vertical = 9.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (subtitle == null) 0.dp else 2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    !enabled -> colors.disabledForeground
                    selected -> colors.accentBlue
                    else -> colors.textPrimary
                },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) colors.textSecondary else colors.disabledForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ComposerIconModeChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(16.dp))
            .remodexPressedState(
                interactionSource = interactionSource,
                enabled = enabled,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (enabled) colors.secondarySurface.copy(alpha = 0.94f) else colors.disabledFill,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) colors.textSecondary else colors.disabledForeground,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ReviewTargetChip(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = Modifier
            .widthIn(min = 146.dp)
            .clip(RoundedCornerShape(18.dp))
            .remodexPressedState(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) colors.accentBlue.copy(alpha = 0.1f) else colors.secondarySurface.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) colors.accentBlue.copy(alpha = 0.2f) else colors.hairlineDivider,
        ),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.spacing12,
                vertical = geometry.spacing10,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.accentBlue else colors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReviewBaseBranchField(
    value: String,
    placeholder: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = colors.inputBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = geometry.spacing14, vertical = geometry.spacing12),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Base branch",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.isBlank()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inputPlaceholder,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = if (enabled) colors.textPrimary else colors.disabledForeground,
                    ),
                    cursorBrush = SolidColor(colors.accentBlue),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ComposerContextChip(
    label: String,
    tone: ComposerContextChipTone,
    onRemove: () -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        color = tone.fill,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, tone.border),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(
                start = geometry.spacing8,
                end = 5.dp,
                top = 4.dp,
                bottom = 4.dp,
            ),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = tone.text,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(tone.text.copy(alpha = 0.14f))
                    .remodexPressedState(interactionSource = interactionSource, pressedScale = 0.96f)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onRemove,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove $label",
                    tint = tone.text,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
    }
}

@Composable
private fun rememberComposerChipTone(accent: Color): ComposerContextChipTone {
    val colors = RemodexTheme.colors
    return remember(accent, colors.hairlineDivider) {
        ComposerContextChipTone(
            fill = accent.copy(alpha = 0.11f),
            border = accent.copy(alpha = 0.16f),
            text = accent,
        )
    }
}

@Composable
private fun ComposerAutocompletePanel(
    kind: ComposerAutocompleteKind,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RemodexTheme.colors
    val motion = RemodexTheme.motion

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = remodexTween(motion.composerMillis)),
        color = colors.secondarySurface.copy(alpha = 0.98f),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .heightIn(max = kind.rowHeight * 6 + 8.dp)
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

@Composable
private fun AutocompleteRow(
    title: String,
    subtitle: String?,
    rowHeight: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = rowHeight)
            .clip(RoundedCornerShape(16.dp))
            .remodexPressedState(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AutocompleteStatus(
    text: String,
    rowHeight: androidx.compose.ui.unit.Dp,
) {
    val colors = RemodexTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = rowHeight)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 1.6.dp,
            color = colors.accentBlue,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}
