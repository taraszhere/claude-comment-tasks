package com.claudecommenttasks.highlight

import com.claudecommenttasks.ClaudeIcons
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/** Settings | Editor | Color Scheme | Claude Comment Tasks. */
class ClaudeTaskColorSettingsPage : ColorSettingsPage {

    override fun getDisplayName(): String = "Claude Comment Tasks"

    override fun getIcon(): Icon = ClaudeIcons.Task

    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getDemoText(): String = """
        |// A regular comment stays as it is.
        |// <task><tag>@claude</tag> add a method that calculates the sum of the numbers in a Set</task>
        |def remove_duplicates(items):
        |    # <task><tag>@claude</tag> remove duplicates but keep the original order</task>
        |    return items
        |
        |/**
        | * Javadoc of the class.
        | * <task><tag>@claude</tag> document the thread-safety guarantees</task>
        | */
        |SELECT * FROM users; -- <task><tag>@claude</tag> add a WHERE clause for active users</task>
    """.trimMargin()

    private companion object {
        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Task text (from @claude to end of comment)", ClaudeTaskColors.TASK),
            AttributesDescriptor("@claude tag", ClaudeTaskColors.TAG),
        )
        val TAGS = mapOf(
            "task" to ClaudeTaskColors.TASK,
            "tag" to ClaudeTaskColors.TAG,
        )
    }
}
