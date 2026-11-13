package com.claudecommenttasks.parser

/**
 * Pure Kotlin logic for finding `@claude` tasks. It has no IntelliJ dependencies so it can be unit tested.
 *
 * Two entry points:
 * - [findTasksInComment] works on the text of a single comment (obtained from a `PsiComment` of any language).
 * - [findTasksInPlainText] is the regex fallback for files without PSI support (plain text).
 */
object ClaudeTaskParser {
    const val TAG = "@claude"

    /**
     * Fallback regex for files without PSI support. Group 2 is the task text, group 3 the optional `*&#47;`.
     */
    val FALLBACK_REGEX: Regex =
        Regex("""(//|#|--|;|/\*+|\*)\s*@claude\b\s*(.+?)\s*(\*/)?$""", RegexOption.MULTILINE)

    /**
     * Finds the tag anywhere in a comment. The tag must not be glued to a preceding word character or to
     * a following `.`, `-` or `@` plus word (so `mail@claude.ai` or `@claude-code` are not tasks).
     * Group 1 is the rest of the line after the tag.
     */
    private val TAG_IN_COMMENT_REGEX: Regex =
        Regex("""(?<![\w@.])@claude\b(?![.\-@]\w)[ \t]*([^\r\n]*)""")

    /** Block comment delimiters whose closing part must not become part of the task text. Order matters. */
    private val BLOCK_DELIMITERS: List<Pair<String, String>> = listOf(
        "/*" to "*/",
        "<!--" to "-->",
        "(*" to "*)",
        "{-" to "-}",
        "--[[" to "]]",
        "#|" to "|#",
        "<#" to "#>",
        "%{" to "%}",
        "=begin" to "=end",
        "{" to "}",
    )

    /** `@claude: do X` and `@claude - do X` are accepted; the punctuation is not part of the task. */
    private val LEADING_PUNCTUATION: Regex = Regex("""^(?::|-(?=\s))\s*""")

    /**
     * Finds all tasks in the text of one comment (including its delimiters, exactly as the PSI reports it).
     */
    fun findTasksInComment(commentText: CharSequence): List<CommentTaskMatch> {
        if (!commentText.contains(TAG)) return emptyList()
        val closer = closingDelimiterOf(commentText)
        val contentEnd = commentText.trimEnd().length
        val result = ArrayList<CommentTaskMatch>(2)
        for (match in TAG_IN_COMMENT_REGEX.findAll(commentText)) {
            val group = match.groups[1] ?: continue
            val textStart = group.range.first
            var raw = group.value.trimEnd()
            val reachesCommentEnd = textStart + group.value.length >= contentEnd
            if (closer != null && reachesCommentEnd && raw.endsWith(closer)) {
                raw = raw.dropLast(closer.length).trimEnd()
                if (closer == "*/") raw = raw.trimEnd('*').trimEnd()
            }
            val text = cleanTaskText(raw)
            if (text.isEmpty()) continue
            result += CommentTaskMatch(tagStart = match.range.first, textEnd = textStart + raw.length, text = text)
        }
        return result
    }

    /**
     * Regex fallback for text without PSI. Returns tasks with absolute offsets and 1-based line numbers.
     */
    fun findTasksInPlainText(text: CharSequence): List<ClaudeTask> {
        if (!text.contains(TAG)) return emptyList()
        val result = ArrayList<ClaudeTask>(2)
        var line = 1
        var scanned = 0
        for (match in FALLBACK_REGEX.findAll(text)) {
            val group = match.groups[2] ?: continue
            // `\s*` in the regex may cross line breaks: the marker, the tag and the task text must share one line
            // (whitespace swallowed after the task text does not matter).
            val head = text.subSequence(match.range.first, group.range.first)
            if (head.indexOf('\n') >= 0 || head.indexOf('\r') >= 0) continue
            val tagStart = text.indexOf(TAG, match.range.first)
            if (tagStart < 0 || tagStart > group.range.first) continue
            var raw = group.value.trimEnd()
            if (match.groups[3] != null) raw = raw.trimEnd('*').trimEnd()
            val taskText = cleanTaskText(raw)
            if (taskText.isEmpty()) continue
            line += countNewlines(text, scanned, tagStart)
            scanned = tagStart
            result += ClaudeTask(
                line = line,
                text = taskText,
                startOffset = tagStart,
                endOffset = group.range.first + raw.length,
            )
        }
        return result
    }

    /** 1-based line number of [offset] in [text]. */
    fun lineNumber(text: CharSequence, offset: Int): Int =
        1 + countNewlines(text, 0, offset.coerceIn(0, text.length))

    private fun countNewlines(text: CharSequence, from: Int, to: Int): Int {
        var count = 0
        for (i in from until to) if (text[i] == '\n') count++
        return count
    }

    private fun cleanTaskText(raw: String): String =
        LEADING_PUNCTUATION.replaceFirst(raw.trim(), "").trim()

    private fun closingDelimiterOf(commentText: CharSequence): String? {
        val trimmed = commentText.trim()
        for ((open, close) in BLOCK_DELIMITERS) {
            if (trimmed.length >= open.length + close.length && trimmed.startsWith(open) && trimmed.endsWith(close)) {
                return close
            }
        }
        return null
    }
}
