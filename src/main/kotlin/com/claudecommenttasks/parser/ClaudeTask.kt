package com.claudecommenttasks.parser

/**
 * A single `@claude` task found inside a comment.
 *
 * @property line 1-based line number of the `@claude` tag.
 * @property text the task text that follows the tag, without comment delimiters.
 * @property startOffset absolute offset of the `@claude` tag in the file.
 * @property endOffset absolute end offset (exclusive) of the task text.
 */
data class ClaudeTask(
    val line: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
) {
    /** End offset (exclusive) of the `@claude` word itself. */
    val tagEndOffset: Int get() = startOffset + ClaudeTaskParser.TAG.length
}

/**
 * A task found inside the text of one comment. Offsets are relative to the start of that comment text.
 */
data class CommentTaskMatch(
    val tagStart: Int,
    val textEnd: Int,
    val text: String,
) {
    val tagEnd: Int get() = tagStart + ClaudeTaskParser.TAG.length
}
