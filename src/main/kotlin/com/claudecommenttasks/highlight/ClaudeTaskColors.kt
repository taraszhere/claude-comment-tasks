package com.claudecommenttasks.highlight

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * Text attribute keys for `@claude` comments. The default colors live in the `colorSchemes` resource files
 * (registered with `additionalTextAttributes`), the fallback keeps unknown schemes readable.
 */
object ClaudeTaskColors {
    /** From the `@claude` tag to the end of the comment line. */
    @JvmField
    val TASK: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("CLAUDE_TASK_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)

    /** The `@claude` word itself; bold by default, color inherited from [TASK]. */
    @JvmField
    val TAG: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("CLAUDE_TASK_TAG", TASK)
}
