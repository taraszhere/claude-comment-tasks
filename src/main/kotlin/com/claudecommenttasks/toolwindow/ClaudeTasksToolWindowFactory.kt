package com.claudecommenttasks.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Creates the "Claude Tasks" tool window: a console with the full CLI output of every run. */
class ClaudeTasksToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = ClaudeTasksConsole.getInstance(project).getOrCreateConsole()
        val panel = SimpleToolWindowPanel(false, true)
        panel.setContent(console.component)
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ClaudeTasksToolWindow", DefaultActionGroup(*console.createConsoleActions()), false)
        toolbar.targetComponent = panel
        panel.toolbar = toolbar.component
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
