package com.claudecommenttasks.toolwindow

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.annotations.RequiresEdt

/**
 * Owns the console shown in the "Claude Tasks" tool window. Output can be appended from any thread,
 * even before the tool window has been opened for the first time.
 */
@Service(Service.Level.PROJECT)
class ClaudeTasksConsole(private val project: Project) : Disposable {

    private var consoleView: ConsoleView? = null

    @RequiresEdt
    fun getOrCreateConsole(): ConsoleView {
        consoleView?.let { return it }
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        Disposer.register(this, console)
        consoleView = console
        return console
    }

    /** Appends [text] (thread-safe; output order is preserved). */
    fun print(text: String, type: ConsoleViewContentType = ConsoleViewContentType.NORMAL_OUTPUT) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) getOrCreateConsole().print(text, type)
        }, ModalityState.any())
    }

    fun printLine(text: String, type: ConsoleViewContentType = ConsoleViewContentType.NORMAL_OUTPUT) =
        print(text + "\n", type)

    @RequiresEdt
    fun show() {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
    }

    override fun dispose() {
        consoleView = null
    }

    companion object {
        const val TOOL_WINDOW_ID = "Claude Tasks"

        fun getInstance(project: Project): ClaudeTasksConsole = project.service()
    }
}
