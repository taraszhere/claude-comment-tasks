package com.claudecommenttasks.highlight

import com.claudecommenttasks.ClaudeIcons
import com.claudecommenttasks.parser.ClaudeTask
import com.claudecommenttasks.parser.ClaudeTaskParser
import com.claudecommenttasks.psi.ClaudeTaskFinder
import com.claudecommenttasks.run.ClaudeTaskRunner
import com.claudecommenttasks.run.RunSource
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPlainText
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.awt.RelativePoint
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Puts a gutter icon on every `@claude` line. Clicking it runs the task through [ClaudeTaskRunner].
 * Registered with `language=""` so it applies to all languages; it can be switched off in
 * Settings | Editor | General | Gutter Icons.
 */
class ClaudeTaskLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = "@claude tasks"

    override fun getIcon(): Icon = ClaudeIcons.Task

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        for (element in elements) {
            // Markers must be attached to leaves; the tag text always contains '@'.
            if (element.firstChild != null || !element.textContains('@')) continue
            val leafRange = element.textRange ?: continue
            for (task in tasksForLeaf(element, leafRange)) {
                if (task.startOffset < leafRange.startOffset || task.startOffset >= leafRange.endOffset) continue
                val end = minOf(task.tagEndOffset, leafRange.endOffset)
                result.add(createMarker(element, TextRange(task.startOffset, end), task))
            }
        }
    }

    private fun tasksForLeaf(element: PsiElement, leafRange: TextRange): List<ClaudeTask> {
        if (element is PsiPlainText) {
            val base = leafRange.startOffset
            return ClaudeTaskParser.findTasksInPlainText(element.text).map {
                if (base == 0) it else it.copy(startOffset = it.startOffset + base, endOffset = it.endOffset + base)
            }
        }
        val comment = PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) ?: return emptyList()
        return ClaudeTaskFinder.tasksInComment(comment)
    }

    private fun createMarker(element: PsiElement, range: TextRange, task: ClaudeTask): LineMarkerInfo<PsiElement> {
        val tooltip = "Run @claude task (line ${task.line}): ${task.text}"
        return LineMarkerInfo(
            element,
            range,
            ClaudeIcons.Task,
            { tooltip },
            RunTaskNavigationHandler(task),
            GutterIconRenderer.Alignment.LEFT,
        ) { "Run @claude task" }
    }
}

/** Gutter click handler: runs the clicked task, or offers to run all tasks of the file. */
private class RunTaskNavigationHandler(private val task: ClaudeTask) : GutterIconNavigationHandler<PsiElement> {

    override fun navigate(e: MouseEvent, elt: PsiElement) {
        val psiFile = elt.containingFile ?: return
        val file = psiFile.virtualFile ?: return
        val project = psiFile.project
        val runner = ClaudeTaskRunner.getInstance()
        val allTasks = runReadActionBlocking { ClaudeTaskFinder.findTasks(psiFile) }
        if (allTasks.size <= 1) {
            runner.runTasksInFile(project, file, RunSource.GUTTER)
            return
        }
        val runThis = "Run this task only"
        val runAll = "Run all ${allTasks.size} @claude tasks in ${file.name}"
        val step = object : BaseListPopupStep<String>("Run @claude Task", listOf(runThis, runAll)) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? = doFinalStep {
                if (selectedValue == runThis) {
                    runner.runTasksInFile(project, file, RunSource.GUTTER, task)
                } else {
                    runner.runTasksInFile(project, file, RunSource.GUTTER)
                }
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).show(RelativePoint(e))
    }
}
