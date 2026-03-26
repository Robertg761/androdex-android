package io.androdex.android.ui.turn

import android.content.ContentResolver
import android.graphics.Bitmap
import io.androdex.android.attachment.buildImageAttachmentFromBytes
import io.androdex.android.model.ComposerImageAttachmentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object TurnAttachmentPipeline {
    suspend fun loadGalleryAttachment(
        contentResolver: ContentResolver,
        uriString: String,
    ): ComposerImageAttachmentState {
        return withContext(Dispatchers.IO) {
            val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull()
                ?: return@withContext ComposerImageAttachmentState.Failed("Couldn't open that photo.")
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                return@withContext ComposerImageAttachmentState.Failed("Couldn't load that photo.")
            }
            val attachment = buildImageAttachmentFromBytes(bytes)
                ?: return@withContext ComposerImageAttachmentState.Failed("That photo couldn't be prepared for sending.")
            ComposerImageAttachmentState.Ready(attachment)
        }
    }

    suspend fun loadCameraAttachment(bitmap: Bitmap): ComposerImageAttachmentState {
        return withContext(Dispatchers.Default) {
            val output = java.io.ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
                return@withContext ComposerImageAttachmentState.Failed("Couldn't capture that photo.")
            }
            val attachment = buildImageAttachmentFromBytes(output.toByteArray())
                ?: return@withContext ComposerImageAttachmentState.Failed("That photo couldn't be prepared for sending.")
            ComposerImageAttachmentState.Ready(attachment)
        }
    }
}
