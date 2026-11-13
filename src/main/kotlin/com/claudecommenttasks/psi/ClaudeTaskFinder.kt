package com.claudecommenttasks.psi

import com.claudecommenttasks.parser.ClaudeTask
import com.claudecommenttasks.parser.ClaudeTaskParser
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.fileTypes.PlainTextLikeFileType
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPlainTextFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.util.concurrency.annotations.RequiresReadLock

/**
 * Finds `@claude` tasks in a file: PSI comments first, regex fallback for files without PSI support.
 */
object ClaudeTaskFinder {

    /** All tasks of [psiFile], sorted by offset. */
    @RequiresReadLock
    fun findTasks(psiFile: PsiFile): List<ClaudeTask> {
        val viewProvider = psiFile.viewProvider
        val text = viewProvider.contents
        if (usesTextFallback(psiFile)) {
            return ClaudeTaskParser.findTasksInPlainText(text)
        }
        val document = viewProvider.document
        val byOffset = LinkedHashMap<Int, ClaudeTask>()
        for (root in viewProvider.allFiles) {
            for (comment in SyntaxTraverser.psiTraverser(root).filter(PsiComment::class.java)) {
                for (task in tasksInComment(comment, document, text)) {
                    byOffset.putIfAbsent(task.startOffset, task)
                }
            }
        }
        return byOffset.values.sortedBy { it.startOffset }
    }

    /** Tasks of a single comment, with absolute offsets and 1-based line numbers. */
    @RequiresReadLock
    fun tasksInComment(comment: PsiComment): List<ClaudeTask> {
        val viewProvider = comment.containingFile?.viewProvider
        return tasksInComment(comment, viewProvider?.document, viewProvider?.contents ?: comment.text)
    }

    /** True when the file has no real PSI (plain text, unknown file types, TextMate-only highlighting). */
    fun usesTextFallback(psiFile: PsiFile): Boolean {
        if (psiFile is PsiPlainTextFile) return true
        val viewProvider = psiFile.viewProvider
        return viewProvider.baseLanguage == PlainTextLanguage.INSTANCE || viewProvider.fileType is PlainTextLikeFileType
    }

    private fun tasksInComment(comment: PsiComment, document: Document?, fileText: CharSequence): List<ClaudeTask> {
        val matches = ClaudeTaskParser.findTasksInComment(comment.text)
        if (matches.isEmpty()) return emptyList()
        val base = comment.textRange.startOffset
        return matches.map { match ->
            val start = base + match.tagStart
            ClaudeTask(
                line = lineNumberAt(document, fileText, start),
                text = match.text,
                startOffset = start,
                endOffset = base + match.textEnd,
            )
        }
    }

    private fun lineNumberAt(document: Document?, text: CharSequence, offset: Int): Int =
        if (document != null && offset <= document.textLength) document.getLineNumber(offset) + 1
        else ClaudeTaskParser.lineNumber(text, offset)
}
