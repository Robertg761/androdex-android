package io.androdex.android

internal const val subagentsCannedPrompt =
    "Run subagents for different tasks. Delegate distinct work in parallel when helpful and then synthesize the results."

internal data class TrailingSlashCommandToken(
    val query: String,
    val tokenRange: IntRange,
)

internal fun trailingSlashCommandToken(text: String): TrailingSlashCommandToken? {
    if (text.isEmpty()) {
        return null
    }
    val slashIndex = text.lastIndexOf('/')
    if (slashIndex < 0) {
        return null
    }
    if (slashIndex > 0 && !text[slashIndex - 1].isWhitespace()) {
        return null
    }
    val query = text.substring(slashIndex + 1)
    if (query.any { it.isWhitespace() }) {
        return null
    }
    return TrailingSlashCommandToken(
        query = query,
        tokenRange = slashIndex until text.length,
    )
}

internal fun removingTrailingSlashCommandToken(text: String): String? {
    val token = trailingSlashCommandToken(text) ?: return null
    return buildString {
        append(text.substring(0, token.tokenRange.first))
        append(text.substring(token.tokenRange.last + 1))
    }.trim()
}

internal fun applyingSubagentsSelection(text: String, isSelected: Boolean): String {
    val trimmed = text.trim()
    if (!isSelected) {
        return trimmed
    }
    if (trimmed.isEmpty()) {
        return subagentsCannedPrompt
    }
    return "$subagentsCannedPrompt\n\n$trimmed"
}
