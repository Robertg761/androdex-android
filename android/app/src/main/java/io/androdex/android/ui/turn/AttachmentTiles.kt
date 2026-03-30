package io.androdex.android.ui.turn

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.androdex.android.attachment.decodeAttachmentThumbnailBitmap
import io.androdex.android.model.ComposerImageAttachment
import io.androdex.android.model.ComposerImageAttachmentState
import io.androdex.android.model.ImageAttachment
import io.androdex.android.ui.shared.RemodexPill
import io.androdex.android.ui.shared.RemodexPillStyle
import io.androdex.android.ui.theme.RemodexTheme

private val ComposerAttachmentTileSize = 84.dp
private val MessageAttachmentTileSize = 78.dp

@Composable
internal fun ComposerAttachmentStrip(
    attachments: List<ComposerImageAttachment>,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            ComposerAttachmentTile(
                attachment = attachment,
                onRemove = { onRemove(attachment.id) },
            )
        }
    }
}

@Composable
internal fun MessageAttachmentStrip(
    attachments: List<ImageAttachment>,
    tileSize: androidx.compose.ui.unit.Dp = MessageAttachmentTileSize,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            ReadyAttachmentThumbnail(
                attachment = attachment,
                modifier = Modifier.size(tileSize),
            )
        }
    }
}

@Composable
private fun ComposerAttachmentTile(
    attachment: ComposerImageAttachment,
    onRemove: () -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors
    Box {
        when (val state = attachment.state) {
            ComposerImageAttachmentState.Loading -> LoadingAttachmentTile()
            is ComposerImageAttachmentState.Failed -> FailedAttachmentTile(state.message)
            is ComposerImageAttachmentState.Ready -> ReadyAttachmentThumbnail(
                attachment = state.attachment,
                modifier = Modifier.size(ComposerAttachmentTileSize),
            )
        }

        Surface(
            onClick = onRemove,
            shape = RoundedCornerShape(999.dp),
            color = colors.groupedBackground.copy(alpha = 0.88f),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(geometry.spacing6)
                .size(22.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove photo",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

@Composable
private fun ReadyAttachmentThumbnail(
    attachment: ImageAttachment,
    modifier: Modifier,
) {
    AttachmentTileShell(modifier = modifier) {
        val bitmap = remember(attachment.thumbnailBase64Jpeg) {
            decodeAttachmentThumbnailBitmap(attachment.thumbnailBase64Jpeg)?.asImageBitmap()
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Attached photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(RemodexTheme.colors.secondarySurface),
                contentAlignment = Alignment.Center,
            ) {
                AttachmentTileFallback(icon = Icons.Default.Image, label = "Photo")
            }
        }
    }
}

@Composable
private fun LoadingAttachmentTile() {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    AttachmentTileShell(
        modifier = Modifier.size(ComposerAttachmentTileSize),
        fill = colors.secondarySurface.copy(alpha = 0.92f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = geometry.spacing10, vertical = geometry.spacing10),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            RemodexPill(
                label = "Pending",
                style = RemodexPillStyle.Neutral,
            )
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = colors.accentBlue,
                )
            }
            Text(
                text = "Syncing",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FailedAttachmentTile(message: String?) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    AttachmentTileShell(
        modifier = Modifier.size(ComposerAttachmentTileSize),
        fill = colors.errorRed.copy(alpha = 0.12f),
        borderColor = colors.errorRed.copy(alpha = 0.2f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = geometry.spacing8, vertical = geometry.spacing10),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AttachmentTileFallback(
                icon = Icons.Default.BrokenImage,
                label = "Failed",
                tint = colors.errorRed,
            )
            Text(
                text = message?.takeIf { it.isNotBlank() } ?: "Could not load",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun AttachmentTileShell(
    modifier: Modifier,
    fill: androidx.compose.ui.graphics.Color = RemodexTheme.colors.raisedSurface,
    borderColor: androidx.compose.ui.graphics.Color = RemodexTheme.colors.hairlineDivider,
    content: @Composable () -> Unit,
) {
    val geometry = RemodexTheme.geometry
    Surface(
        shape = RoundedCornerShape(geometry.cornerMedium),
        color = fill,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = modifier,
    ) {
        content()
    }
}

@Composable
private fun AttachmentTileFallback(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color = RemodexTheme.colors.textSecondary,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
