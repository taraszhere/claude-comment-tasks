package com.claudecommenttasks.actions

import com.claudecommenttasks.run.ClaudeTaskRunner
import com.claudecommenttasks.run.RunSource
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware

/** "Run @claude Tasks in File": Cmd+. / Ctrl+. and the editor context menu. */
class RunClaudeTasksAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            e.project != null && file != null && !file.isDirectory && file.isInLocalFileSystem
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        ClaudeTaskRunner.getInstance().runTasksInFile(project, file, RunSource.ACTION)
    }
}
