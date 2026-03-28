package io.androdex.android.ui.turn

internal data class CodeCommentDirectiveFinding(
    val id: String,
    val title: String,
    val body: String,
    val file: String,
    val startLine: Int?,
    val endLine: Int?,
    val priority: Int?,
    val confidence: Double?,
)

internal data class CodeCommentDirectiveContent(
    val findings: List<CodeCommentDirectiveFinding>,
    val fallbackText: String,
)

internal object CodeCommentDirectiveParser {
    private val directiveRegex = Regex(
        pattern = """::code-comment\{((?:[^"\\}]|\\.|"([^"\\]|\\.)*")*)\}""",
    )
    private val quotedAttributeRegex = Regex(
        pattern = "([A-Za-z][A-Za-z0-9_-]*)=\"((?:[^\"\\\\]|\\\\.)*)\"",
    )
    private val bareAttributeRegex = Regex(
        pattern = "([A-Za-z][A-Za-z0-9_-]*)=([^\\s}]+)",
    )
    private val titlePriorityRegex = Regex(
        pattern = """^\s*\[(P\d+)\]\s*""",
        option = RegexOption.IGNORE_CASE,
    )

    fun parse(rawText: String): CodeCommentDirectiveContent {
        val matches = directiveRegex.findAll(rawText).toList()
        if (matches.isEmpty()) {
            return CodeCommentDirectiveContent(
                findings = emptyList(),
                fallbackText = rawText,
            )
        }

        val findings = mutableListOf<CodeCommentDirectiveFinding>()
        val remaining = StringBuilder(rawText)
        matches.asReversed().forEach { match ->
            val finding = parseFinding(match.groupValues.getOrNull(1).orEmpty())
            if (finding != null) {
                findings.add(0, finding)
                remaining.replace(match.range.first, match.range.last + 1, "")
            }
        }

        return CodeCommentDirectiveContent(
            findings = findings,
            fallbackText = collapseDirectiveWhitespace(remaining.toString()),
        )
    }

    private fun parseFinding(payload: String): CodeCommentDirectiveFinding? {
        val attributes = parseAttributes(payload)
        val rawTitle = attributes["title"]?.trim().orEmpty()
        val body = attributes["body"]?.trim().orEmpty()
        val file = attributes["file"]?.trim().orEmpty()
        if (rawTitle.isEmpty() || body.isEmpty() || file.isEmpty()) {
            return null
        }

        val normalizedTitle = rawTitle.replace(titlePriorityRegex, "").trim()
        val inferredPriority = titlePriorityRegex.find(rawTitle)
            ?.groupValues
            ?.getOrNull(1)
            ?.drop(1)
            ?.toIntOrNull()

        return CodeCommentDirectiveFinding(
            id = "$file|${attributes["start"]}|${attributes["end"]}|${normalizedTitle.ifEmpty { rawTitle }}",
            title = normalizedTitle.ifEmpty { rawTitle },
            body = body,
            file = file,
            startLine = attributes["start"]?.toIntOrNull(),
            endLine = attributes["end"]?.toIntOrNull(),
            priority = attributes["priority"]?.toIntOrNull() ?: inferredPriority,
            confidence = attributes["confidence"]?.toDoubleOrNull(),
        )
    }

    private fun parseAttributes(payload: String): Map<String, String> {
        val attributes = linkedMapOf<String, String>()
        val occupiedRanges = mutableListOf<IntRange>()

        quotedAttributeRegex.findAll(payload).forEach { match ->
            val key = match.groupValues.getOrNull(1).orEmpty()
            val value = match.groupValues.getOrNull(2).orEmpty()
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            if (key.isNotEmpty()) {
                attributes[key] = value
                occupiedRanges += match.range
            }
        }

        bareAttributeRegex.findAll(payload).forEach { match ->
            if (occupiedRanges.any { it.first <= match.range.last && match.range.first <= it.last }) {
                return@forEach
            }
            val key = match.groupValues.getOrNull(1).orEmpty()
            val value = match.groupValues.getOrNull(2).orEmpty()
            if (key.isNotEmpty()) {
                attributes[key] = value
            }
        }

        return attributes
    }

    private fun collapseDirectiveWhitespace(text: String): String {
        return text
            .replace(Regex("""\n{3,}"""), "\n\n")
            .lineSequence()
            .joinToString("\n") { it.trim() }
            .trim()
    }
}
