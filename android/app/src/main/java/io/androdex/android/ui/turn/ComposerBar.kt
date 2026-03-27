package io.androdex.android.ui.turn

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.androdex.android.ComposerSlashCommand
import io.androdex.android.ComposerReviewTarget
import io.androdex.android.model.ComposerMentionedFile
import io.androdex.android.model.ComposerMentionedSkill
import io.androdex.android.model.FuzzyFileMatch
import io.androdex.android.model.SkillMetadata
import io.androdex.android.ui.state.ComposerUiState

@Composable
internal fun ComposerBar(
    state: ComposerUiState,
    onTextChange: (String) -> Unit,
    onPlanModeChanged: (Boolean) -> Unit,
    onSubagentsModeChanged: (Boolean) -> Unit,
    onSelectReviewTarget: (ComposerReviewTarget) -> Unit,
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.isPlanModeEnabled,
                        onClick = { onPlanModeChanged(!state.isPlanModeEnabled) },
                        enabled = state.planModeEnabled,
                        label = {
                            Text(if (state.isPlanModeEnabled) "Plan mode on" else "Plan mode")
                        },
                    )
                    FilterChip(
                        selected = state.isSubagentsEnabled,
                        onClick = { onSubagentsModeChanged(!state.isSubagentsEnabled) },
                        enabled = state.subagentsEnabled,
                        label = {
                            Text(if (state.isSubagentsEnabled) "Subagents on" else "Subagents")
                        },
                    )
                    FilterChip(
                        selected = state.isReviewModeEnabled,
                        onClick = {
                            if (state.isReviewModeEnabled) {
                                onRemoveReviewSelection()
                            } else {
                                onSelectSlashCommand(ComposerSlashCommand.REVIEW)
                            }
                        },
                        enabled = state.inputEnabled || state.isReviewModeEnabled,
                        label = {
                            Text(if (state.isReviewModeEnabled) "Review on" else "Review")
                        },
                    )
                    FilterChip(
                        selected = state.runtimeButtonLabel != "Runtime",
                        onClick = onOpenRuntime,
                        enabled = state.runtimeButtonEnabled,
                        label = {
                            Text(state.runtimeButtonLabel)
                        },
                    )
                }
            }

            if (!state.runtimeButtonEnabled && state.runtimeButtonLabel != "Runtime") {
                Text(
                    text = state.runtimeButtonLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.mentionedFiles.isNotEmpty()) {
                ComposerChipRow {
                    state.mentionedFiles.forEach { file ->
                        ComposerChip(
                            label = file.fileName,
                            tint = Color(0xFF2563EB),
                            onRemove = { onRemoveMentionedFile(file.id) },
                        )
                    }
                }
            }

            if (state.mentionedSkills.isNotEmpty()) {
                ComposerChipRow {
                    state.mentionedSkills.forEach { skill ->
                        ComposerChip(
                            label = "$ ${skill.name}",
                            tint = Color(0xFF4F46E5),
                            onRemove = { onRemoveMentionedSkill(skill.id) },
                        )
                    }
                }
            }

            if (state.isSubagentsEnabled) {
                ComposerChipRow {
                    ComposerChip(
                        label = "Subagents",
                        tint = Color(0xFF0F766E),
                        onRemove = { onSubagentsModeChanged(false) },
                    )
                }
            }

            if (state.isReviewModeEnabled) {
                ComposerChipRow {
                    ComposerChip(
                        label = "Review",
                        tint = Color(0xFFB45309),
                        onRemove = onRemoveReviewSelection,
                    )
                    FilterChip(
                        selected = state.reviewTarget == ComposerReviewTarget.UNCOMMITTED_CHANGES,
                        onClick = { onSelectReviewTarget(ComposerReviewTarget.UNCOMMITTED_CHANGES) },
                        enabled = true,
                        label = { Text(ComposerReviewTarget.UNCOMMITTED_CHANGES.title) },
                    )
                    FilterChip(
                        selected = state.reviewTarget == ComposerReviewTarget.BASE_BRANCH,
                        onClick = { onSelectReviewTarget(ComposerReviewTarget.BASE_BRANCH) },
                        enabled = !state.reviewBaseBranchLabel.isNullOrEmpty(),
                        label = {
                            Text(
                                state.reviewBaseBranchLabel?.let { "Base branch ($it)" }
                                    ?: ComposerReviewTarget.BASE_BRANCH.title
                            )
                        },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onAddCamera,
                        enabled = state.inputEnabled && state.remainingAttachmentSlots > 0,
                    ) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = "Take photo",
                        )
                    }
                    IconButton(
                        onClick = onAddGallery,
                        enabled = state.inputEnabled && state.remainingAttachmentSlots > 0,
                    ) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = "Choose photo",
                        )
                    }
                }
                Text(
                    text = if (state.remainingAttachmentSlots == 1) {
                        "1 slot left"
                    } else {
                        "${state.remainingAttachmentSlots} slots left"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.hasBlockingAttachmentState) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (state.attachments.isNotEmpty()) {
                ComposerAttachmentStrip(
                    attachments = state.attachments,
                    onRemove = onRemoveAttachment,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(state.placeholderText, style = MaterialTheme.typography.bodyMedium)
                    },
                    enabled = state.inputEnabled,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    maxLines = 5,
                )

                if (state.showStop) {
                    OutlinedButton(
                        onClick = onStop,
                        enabled = state.stopEnabled,
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        if (state.isStopping) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Stop")
                        }
                    }

                    Button(
                        onClick = onSend,
                        enabled = state.submitEnabled,
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(state.submitButtonLabel)
                        }
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = state.submitEnabled,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (state.submitEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            contentColor = if (state.submitEnabled) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ),
                        modifier = Modifier.size(44.dp),
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            if (state.isFileAutocompleteVisible) {
                AutocompletePanel {
                    if (state.isFileAutocompleteLoading) {
                        AutocompleteStatus("Searching files...")
                    } else {
                        state.fileAutocompleteItems.forEach { item ->
                            AutocompleteRow(
                                title = item.fileName,
                                subtitle = item.path,
                                onClick = { onSelectFileAutocomplete(item) },
                            )
                        }
                    }
                }
            }

            if (state.isSkillAutocompleteVisible) {
                AutocompletePanel {
                    if (state.isSkillAutocompleteLoading) {
                        AutocompleteStatus("Searching skills...")
                    } else {
                        state.skillAutocompleteItems.forEach { skill ->
                            AutocompleteRow(
                                title = skill.name,
                                subtitle = skill.description ?: skill.path,
                                onClick = { onSelectSkillAutocomplete(skill) },
                            )
                        }
                    }
                }
            }

            if (state.isSlashCommandAutocompleteVisible) {
                AutocompletePanel {
                    state.slashCommandItems.forEach { command ->
                        AutocompleteRow(
                            title = command.commandToken,
                            subtitle = command.subtitle,
                            onClick = { onSelectSlashCommand(command) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun ComposerChip(
    label: String,
    tint: Color,
    onRemove: () -> Unit,
) {
    Surface(
        color = tint.copy(alpha = 0.1f),
        shape = RoundedCornerShape(999.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = tint,
                fontWeight = FontWeight.Medium,
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(18.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(tint.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove",
                        tint = tint,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AutocompletePanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
private fun AutocompleteRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AutocompleteStatus(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
