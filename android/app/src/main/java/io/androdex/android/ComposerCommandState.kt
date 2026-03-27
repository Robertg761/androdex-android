package io.androdex.android

import io.androdex.android.model.ComposerMentionedFile
import io.androdex.android.model.ComposerMentionedSkill
import io.androdex.android.model.TurnSkillMention

internal const val subagentsCannedPrompt =
    "Run subagents for different tasks. Delegate distinct work in parallel when helpful and then synthesize the results."

enum class ComposerSlashCommand(
    val title: String,
    val subtitle: String,
    val commandToken: String,
) {
    REVIEW(
        title = "Review",
        subtitle = "Start an inline code review",
        commandToken = "/review",
    ),
    SUBAGENTS(
        title = "Subagents",
        subtitle = "Insert a canned prompt that asks Codex to delegate work",
        commandToken = "/subagents",
    ),
    ;

    private val searchBlob: String
        get() = "$title $subtitle $commandToken".lowercase()

    companion object {
        val all = entries.toList()

        fun filtered(query: String): List<ComposerSlashCommand> {
            val trimmedQuery = query.trim().lowercase()
            if (trimmedQuery.isEmpty()) {
                return all
            }
            return all.filter { it.searchBlob.contains(trimmedQuery) }
        }
    }
}

enum class ComposerReviewTarget(
    val title: String,
    val subtitle: String,
) {
    UNCOMMITTED_CHANGES(
        title = "Current changes",
        subtitle = "Review uncommitted changes in the working tree",
    ),
    BASE_BRANCH(
        title = "Base branch",
        subtitle = "Review against the selected base branch",
    ),
}

data class ComposerReviewSelection(
    val target: ComposerReviewTarget,
    val baseBranch: String? = null,
)

internal data class TrailingFileAutocompleteToken(
    val query: String,
    val tokenRange: IntRange,
)

internal data class TrailingSkillAutocompleteToken(
    val query: String,
    val tokenRange: IntRange,
)

internal data class TrailingSlashCommandToken(
    val query: String,
    val tokenRange: IntRange,
)

private val disallowedBareSwiftFileMentionQueries = setOf(
    "Binding",
    "Environment",
    "EnvironmentObject",
    "FocusState",
    "MainActor",
    "Namespace",
    "Observable",
    "ObservedObject",
    "Published",
    "SceneBuilder",
    "State",
    "StateObject",
    "UIApplicationDelegateAdaptor",
    "ViewBuilder",
    "testable",
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

internal fun trailingFileAutocompleteToken(text: String): TrailingFileAutocompleteToken? {
    val token = trailingFileToken(text) ?: return null
    return TrailingFileAutocompleteToken(
        query = token.query,
        tokenRange = token.tokenRange,
    )
}

internal fun trailingSkillAutocompleteToken(text: String): TrailingSkillAutocompleteToken? {
    val token = trailingToken(text, '$') ?: return null
    if (!token.query.any { it.isLetter() }) {
        return null
    }
    return TrailingSkillAutocompleteToken(
        query = token.query,
        tokenRange = token.tokenRange,
    )
}

internal fun replacingTrailingFileAutocompleteToken(text: String, selectedPath: String): String? {
    val trimmedPath = selectedPath.trim()
    val token = trailingFileAutocompleteToken(text) ?: return null
    if (trimmedPath.isEmpty()) {
        return null
    }
    return buildString {
        append(text.substring(0, token.tokenRange.first))
        append("@")
        append(trimmedPath)
        append(" ")
    }
}

internal fun replacingTrailingSkillAutocompleteToken(text: String, selectedSkill: String): String? {
    val trimmedSkill = selectedSkill.trim()
    val token = trailingSkillAutocompleteToken(text) ?: return null
    if (trimmedSkill.isEmpty()) {
        return null
    }
    return buildString {
        append(text.substring(0, token.tokenRange.first))
        append("$")
        append(trimmedSkill)
        append(" ")
    }
}

internal fun replacingFileMentionAliases(
    text: String,
    mention: ComposerMentionedFile,
    allowFileNameAliases: Boolean = true,
): String {
    val replacement = "@${mention.path}"
    val placeholder = "__androdex_file_mention__${mention.path.hashCode()}__"
    return fileMentionAliases(
        fileName = mention.fileName,
        path = mention.path,
        allowFileNameAliases = allowFileNameAliases,
    ).fold(text) { partialText, alias ->
        replaceBoundedToken(
            token = "@$alias",
            replacement = placeholder,
            text = partialText,
            caseInsensitive = true,
        )
    }.replace(placeholder, replacement)
}

internal fun removingFileMentionAliases(
    text: String,
    mention: ComposerMentionedFile,
    allowFileNameAliases: Boolean = true,
): String {
    return fileMentionAliases(
        fileName = mention.fileName,
        path = mention.path,
        allowFileNameAliases = allowFileNameAliases,
    ).fold(text) { partialText, alias ->
        removeBoundedToken(
            token = "@$alias",
            text = partialText,
            caseInsensitive = true,
        )
    }
}

internal fun fileMentionAliases(
    fileName: String,
    path: String,
    allowFileNameAliases: Boolean = true,
): List<String> {
    val aliases = linkedSetOf<String>()
    val seeds = buildList {
        if (allowFileNameAliases) {
            add(fileName)
            add(deletingPathExtension(fileName))
        }
        add(path)
        add(deletingPathExtension(path))
    }

    seeds.forEach { seed ->
        val trimmedSeed = seed.trim()
        if (trimmedSeed.isEmpty()) {
            return@forEach
        }
        aliases += trimmedSeed
        appendNormalizedFileMentionAliases(trimmedSeed, aliases)
    }

    return aliases
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .sortedWith(
            compareByDescending<String> { it.length }
                .thenBy { it.lowercase() }
        )
}

internal fun ambiguousFileNameAliasKeys(mentions: List<ComposerMentionedFile>): Set<String> {
    return mentions
        .mapNotNull { fileNameAliasCollisionKey(it.fileName) }
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
}

internal fun hasClosedConfirmedFileMentionPrefix(
    text: String,
    confirmedMentions: List<ComposerMentionedFile>,
): Boolean {
    if (confirmedMentions.isEmpty()) {
        return false
    }
    val triggerIndex = text.lastIndexOf('@')
    if (triggerIndex < 0) {
        return false
    }
    if (triggerIndex > 0 && !text[triggerIndex - 1].isWhitespace()) {
        return false
    }

    val tail = text.substring(triggerIndex + 1)
    if (tail.isEmpty()) {
        return false
    }

    val ambiguousKeys = ambiguousFileNameAliasKeys(confirmedMentions)
    confirmedMentions.forEach { mention ->
        val collisionKey = fileNameAliasCollisionKey(mention.fileName)
        val allowFileNameAliases = collisionKey?.let { it !in ambiguousKeys } ?: true
        fileMentionAliases(
            fileName = mention.fileName,
            path = mention.path,
            allowFileNameAliases = allowFileNameAliases,
        ).forEach { alias ->
            if (tail.startsWith(alias, ignoreCase = true)
                && tail.length > alias.length
                && tail[alias.length].isWhitespace()
            ) {
                return true
            }
        }
    }
    return false
}

internal fun buildComposerPayloadText(
    text: String,
    mentionedFiles: List<ComposerMentionedFile>,
    isSubagentsSelected: Boolean,
): String {
    var payload = text
    if (mentionedFiles.isNotEmpty()) {
        val ambiguousKeys = ambiguousFileNameAliasKeys(mentionedFiles)
        mentionedFiles.forEach { mention ->
            val collisionKey = fileNameAliasCollisionKey(mention.fileName)
            val allowFileNameAliases = collisionKey?.let { it !in ambiguousKeys } ?: true
            payload = replacingFileMentionAliases(
                text = payload,
                mention = mention,
                allowFileNameAliases = allowFileNameAliases,
            )
        }
    }
    return applyingSubagentsSelection(payload, isSelected = isSubagentsSelected)
}

internal fun buildTurnSkillMentions(mentionedSkills: List<ComposerMentionedSkill>): List<TurnSkillMention> {
    return mentionedSkills.mapNotNull { mention ->
        val normalizedName = mention.name.trim()
        if (normalizedName.isEmpty()) {
            null
        } else {
            TurnSkillMention(
                id = normalizedName,
                name = normalizedName,
                path = mention.path?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

internal fun removeBoundedToken(
    token: String,
    text: String,
    caseInsensitive: Boolean = false,
): String {
    if (token.isEmpty() || text.isEmpty()) {
        return text
    }
    val matchRange = findBoundedTokenRange(token, text, caseInsensitive) ?: return text
    val boundaryIndex = matchRange.last + 1
    val removalRange = if (boundaryIndex < text.length && isMentionBoundary(text[boundaryIndex])) {
        matchRange.first..boundaryIndex
    } else {
        matchRange
    }
    return text.removeRange(removalRange)
}

internal fun replaceBoundedToken(
    token: String,
    replacement: String,
    text: String,
    caseInsensitive: Boolean = false,
): String {
    if (token.isEmpty() || text.isEmpty()) {
        return text
    }
    var searchStart = 0
    var updated = text
    while (true) {
        val matchRange = findBoundedTokenRange(token, updated, caseInsensitive, startIndex = searchStart)
            ?: return updated
        updated = updated.replaceRange(matchRange, replacement)
        searchStart = matchRange.first + replacement.length
    }
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

internal fun reviewRequestText(
    target: ComposerReviewTarget,
    baseBranch: String? = null,
): String {
    return when (target) {
        ComposerReviewTarget.UNCOMMITTED_CHANGES -> "Review current changes"
        ComposerReviewTarget.BASE_BRANCH -> {
            val normalizedBaseBranch = baseBranch?.trim().orEmpty()
            if (normalizedBaseBranch.isNotEmpty()) {
                "Review against base branch $normalizedBaseBranch"
            } else {
                "Review against base branch"
            }
        }
    }
}

private data class TrailingToken(
    val query: String,
    val tokenRange: IntRange,
)

private fun trailingFileToken(text: String): TrailingToken? {
    if (text.isEmpty() || text.last().isWhitespace()) {
        return null
    }
    val triggerIndex = text.lastIndexOf('@')
    if (triggerIndex < 0) {
        return null
    }
    if (triggerIndex > 0 && !text[triggerIndex - 1].isWhitespace()) {
        return null
    }
    val rawQuery = text.substring(triggerIndex + 1)
    val query = rawQuery.trim()
    if (query.isEmpty() || query.any { it == '\n' || it == '\r' } || !isAllowedFileAutocompleteQuery(query)) {
        return null
    }
    if (query.any { it.isWhitespace() }) {
        val looksFileLike = query.contains('/') || query.contains('\\') || query.contains('.')
        if (!looksFileLike) {
            return null
        }
    }
    return TrailingToken(query = query, tokenRange = triggerIndex until text.length)
}

private fun isAllowedFileAutocompleteQuery(query: String): Boolean {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) {
        return false
    }
    if (trimmedQuery.contains('/') || trimmedQuery.contains('\\') || trimmedQuery.contains('.')) {
        return true
    }
    return trimmedQuery !in disallowedBareSwiftFileMentionQueries
}

private fun trailingToken(text: String, trigger: Char): TrailingToken? {
    if (text.isEmpty()) {
        return null
    }
    val lastWhitespaceIndex = text.indexOfLast { it.isWhitespace() }
    val tokenStart = lastWhitespaceIndex + 1
    if (tokenStart >= text.length || text[tokenStart] != trigger) {
        return null
    }
    val query = text.substring(tokenStart + 1)
    if (query.isEmpty() || query.any { it.isWhitespace() }) {
        return null
    }
    return TrailingToken(query = query, tokenRange = tokenStart until text.length)
}

private fun appendNormalizedFileMentionAliases(seed: String, aliases: MutableSet<String>) {
    val trimmedSeed = seed.trim()
    if (trimmedSeed.isEmpty()) {
        return
    }
    val extension = trimmedSeed.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    val stem = if (extension.isEmpty()) trimmedSeed else deletingPathExtension(trimmedSeed)
    val tokens = mentionSearchTokens(stem)
    if (tokens.isEmpty()) {
        return
    }
    val variants = linkedSetOf(
        tokens.joinToString(" "),
        tokens.joinToString("-"),
        tokens.joinToString("_"),
        tokens.joinToString(""),
        lowerCamelCase(tokens),
        upperCamelCase(tokens),
    ).filter { it.isNotEmpty() }

    variants.forEach { variant ->
        aliases += variant
        if (extension.isNotEmpty()) {
            aliases += "$variant.$extension"
        }
    }
}

private fun mentionSearchTokens(value: String): List<String> {
    val normalized = value
        .replace('\\', '/')
        .substringAfterLast('/')
        .replace(Regex("[^A-Za-z0-9]+"), " ")
        .trim()
    if (normalized.isEmpty()) {
        return emptyList()
    }
    val tokens = mutableListOf<String>()
    normalized.split(Regex("\\s+")).forEach { part ->
        Regex("[A-Z]+(?=$|[A-Z][a-z]|\\d)|[A-Z]?[a-z]+|\\d+")
            .findAll(part)
            .map { it.value.lowercase() }
            .forEach(tokens::add)
    }
    return tokens
}

private fun deletingPathExtension(value: String): String {
    val trimmed = value.trim()
    val lastSlashIndex = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    val lastDotIndex = trimmed.lastIndexOf('.')
    return if (lastDotIndex > lastSlashIndex) {
        trimmed.substring(0, lastDotIndex)
    } else {
        trimmed
    }
}

private fun fileNameAliasCollisionKey(fileName: String): String? {
    val normalized = deletingPathExtension(fileName)
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
    return normalized.takeIf { it.isNotEmpty() }
}

private fun lowerCamelCase(tokens: List<String>): String {
    if (tokens.isEmpty()) {
        return ""
    }
    return buildString {
        append(tokens.first())
        tokens.drop(1).forEach { token ->
            append(token.replaceFirstChar { it.titlecase() })
        }
    }
}

private fun upperCamelCase(tokens: List<String>): String {
    return tokens.joinToString("") { token ->
        token.replaceFirstChar { it.titlecase() }
    }
}

private fun findBoundedTokenRange(
    token: String,
    text: String,
    caseInsensitive: Boolean,
    startIndex: Int = 0,
): IntRange? {
    var searchIndex = startIndex.coerceAtLeast(0)
    while (searchIndex <= text.length - token.length) {
        val matchIndex = text.indexOf(token, searchIndex, ignoreCase = caseInsensitive)
        if (matchIndex < 0) {
            return null
        }
        val boundaryIndex = matchIndex + token.length
        if (boundaryIndex == text.length || isMentionBoundary(text[boundaryIndex])) {
            return matchIndex until boundaryIndex
        }
        searchIndex = matchIndex + 1
    }
    return null
}

private fun isMentionBoundary(char: Char): Boolean {
    return char.isWhitespace() || char in ",.;:!?)\\]}>"
}
