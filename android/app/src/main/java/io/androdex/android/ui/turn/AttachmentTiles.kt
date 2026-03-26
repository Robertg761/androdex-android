package io.androdex.android.ui.turn

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.androdex.android.attachment.decodeAttachmentThumbnailBitmap
import io.androdex.android.model.ComposerImageAttachment
import io.androdex.android.model.ComposerImageAttachmentState
import io.androdex.android.model.ImageAttachment

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
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            ReadyAttachmentThumbnail(
                attachment = attachment,
                modifier = Modifier.size(78.dp),
            )
        }
    }
}

@Composable
private fun ComposerAttachmentTile(
    attachment: ComposerImageAttachment,
    onRemove: () -> Unit,
) {
    Box {
        when (val state = attachment.state) {
            ComposerImageAttachmentState.Loading -> LoadingAttachmentTile()
            is ComposerImageAttachmentState.Failed -> FailedAttachmentTile(state.message)
            is ComposerImageAttachmentState.Ready -> ReadyAttachmentThumbnail(
                attachment = state.attachment,
                modifier = Modifier.size(78.dp),
            )
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(26.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                shape = RoundedCornerShape(999.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove photo",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .padding(5.dp)
                        .size(12.dp),
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
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier,
    ) {
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
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoadingAttachmentTile() {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(78.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                strokeWidth = 2.5.dp,
            )
        }
    }
}

@Composable
private fun FailedAttachmentTile(message: String?) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.size(78.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = message?.takeIf { it.isNotBlank() } ?: "Failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
