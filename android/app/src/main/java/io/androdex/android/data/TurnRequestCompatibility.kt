package io.androdex.android.data

import io.androdex.android.model.CollaborationModeKind
import java.util.Locale

internal data class TurnRequestCompatibilityState(
    val imageUrlKey: String = "url",
    val includeStructuredFileItems: Boolean = true,
    val includeStructuredSkillItems: Boolean = true,
    val collaborationMode: CollaborationModeKind? = null,
    val includesServiceTier: Boolean = true,
) {
    fun nextRetryState(errorMessage: String?): TurnRequestCompatibilityState? {
        if (imageUrlKey == "url" && shouldRetryTurnWithImageUrlField(errorMessage)) {
            return copy(imageUrlKey = "image_url")
        }
        if (includeStructuredFileItems && shouldRetryTurnWithoutFileItems(errorMessage)) {
            return copy(includeStructuredFileItems = false)
        }
        if (includeStructuredSkillItems && shouldRetryTurnWithoutSkillItems(errorMessage)) {
            return copy(includeStructuredSkillItems = false)
        }
        if (collaborationMode != null && shouldRetryTurnWithoutCollaborationMode(errorMessage)) {
            return copy(collaborationMode = null)
        }
        return null
    }
}

internal fun shouldRetryTurnWithoutCollaborationMode(errorMessage: String?): Boolean {
    val normalizedMessage = errorMessage?.lowercase(Locale.US).orEmpty()
    return (normalizedMessage.contains("collaborationmode") || normalizedMessage.contains("collaboration_mode"))
        && !normalizedMessage.contains("experimentalapi")
}

internal fun shouldRetryTurnWithImageUrlField(errorMessage: String?): Boolean {
    val normalizedMessage = errorMessage?.lowercase(Locale.US).orEmpty()
    if (!normalizedMessage.contains("image_url")) {
        return false
    }
    return normalizedMessage.contains("missing")
        || normalizedMessage.contains("unknown")
        || normalizedMessage.contains("invalid")
        || normalizedMessage.contains("expected")
        || normalizedMessage.contains("field")
}

internal fun shouldRetryTurnWithoutFileItems(errorMessage: String?): Boolean {
    val normalizedMessage = errorMessage?.lowercase(Locale.US).orEmpty()
    val mentionsStructuredFilePayload = normalizedMessage.contains("type")
        || normalizedMessage.contains("path")
        || normalizedMessage.contains("input")
    if (!normalizedMessage.contains("file") || !mentionsStructuredFilePayload) {
        return false
    }
    return normalizedMessage.contains("unknown")
        || normalizedMessage.contains("unsupported")
        || normalizedMessage.contains("invalid")
        || normalizedMessage.contains("expected")
        || normalizedMessage.contains("unrecognized")
        || normalizedMessage.contains("field")
        || normalizedMessage.contains("type")
}

internal fun shouldRetryTurnWithoutSkillItems(errorMessage: String?): Boolean {
    val normalizedMessage = errorMessage?.lowercase(Locale.US).orEmpty()
    if (!normalizedMessage.contains("skill")) {
        return false
    }
    return normalizedMessage.contains("unknown")
        || normalizedMessage.contains("unsupported")
        || normalizedMessage.contains("invalid")
        || normalizedMessage.contains("expected")
        || normalizedMessage.contains("unrecognized")
        || normalizedMessage.contains("type")
        || normalizedMessage.contains("field")
}
