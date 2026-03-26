package io.androdex.android.model

import java.util.UUID

const val MAX_COMPOSER_IMAGE_ATTACHMENTS = 4

data class ImageAttachment(
    val id: String = UUID.randomUUID().toString(),
    val thumbnailBase64Jpeg: String,
    val payloadDataUrl: String? = null,
    val sourceUrl: String? = null,
)

data class ComposerImageAttachment(
    val id: String = UUID.randomUUID().toString(),
    val state: ComposerImageAttachmentState,
)

sealed class ComposerImageAttachmentState {
    data object Loading : ComposerImageAttachmentState()

    data class Ready(val attachment: ImageAttachment) : ComposerImageAttachmentState()

    data class Failed(val message: String? = null) : ComposerImageAttachmentState()
}

data class ComposerAttachmentIntakePlan(
    val acceptedCount: Int,
    val droppedCount: Int,
) {
    val hasOverflow: Boolean
        get() = droppedCount > 0

    companion object {
        fun make(requestedCount: Int, remainingSlots: Int): ComposerAttachmentIntakePlan {
            val safeRequestedCount = requestedCount.coerceAtLeast(0)
            val safeRemainingSlots = remainingSlots.coerceAtLeast(0)
            val acceptedCount = minOf(safeRequestedCount, safeRemainingSlots)
            val droppedCount = safeRequestedCount - acceptedCount
            return ComposerAttachmentIntakePlan(
                acceptedCount = acceptedCount,
                droppedCount = droppedCount,
            )
        }
    }
}

data class ComposerAttachmentIntakeReservation(
    val threadId: String,
    val acceptedIds: List<String>,
    val droppedCount: Int,
)

data class ComposerSendAvailability(
    val isSending: Boolean,
    val isConnected: Boolean,
    val trimmedInput: String,
    val hasReadyImages: Boolean,
    val hasBlockingAttachmentState: Boolean,
    val hasSubagentsSelection: Boolean,
) {
    val isSendDisabled: Boolean
        get() = isSending
            || !isConnected
            || (trimmedInput.isEmpty() && !hasReadyImages && !hasSubagentsSelection)
            || hasBlockingAttachmentState
}

fun List<ComposerImageAttachment>.hasBlockingState(): Boolean {
    return any { attachment ->
        when (attachment.state) {
            ComposerImageAttachmentState.Loading -> true
            is ComposerImageAttachmentState.Failed -> true
            is ComposerImageAttachmentState.Ready -> false
        }
    }
}

fun List<ComposerImageAttachment>.readyAttachments(): List<ImageAttachment> {
    return mapNotNull { attachment ->
        when (val state = attachment.state) {
            is ComposerImageAttachmentState.Ready -> state.attachment
            ComposerImageAttachmentState.Loading -> null
            is ComposerImageAttachmentState.Failed -> null
        }
    }
}

fun attachmentSignature(attachments: List<ImageAttachment>): String {
    return attachments.joinToString(separator = "\u0001") { attachment ->
        attachment.payloadDataUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: attachment.sourceUrl
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: attachment.thumbnailBase64Jpeg
    }
}
