package com.claudecommenttasks

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object ClaudeIcons {
    /** 12x12 gutter and action icon. */
    @JvmField
    val Task: Icon = IconLoader.getIcon("/icons/claudeTask.svg", ClaudeIcons::class.java)

    /** 13x13 tool window icon. */
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/toolWindowClaude.svg", ClaudeIcons::class.java)
}
