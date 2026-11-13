package com.claudecommenttasks.highlight

import com.claudecommenttasks.parser.ClaudeTaskParser
import com.claudecommenttasks.parser.CommentTaskMatch
import com.claudecommenttasks.settings.ClaudeSettings
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPlainText

/**
 * Language-independent annotator (registered with `language=""`) that colors `@claude` tasks inside
 * every [PsiComment], and inside plain text files via the regex fallback.
 */
class ClaudeTaskAnnotator : Annotator, DumbAware {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (!element.textContains('@')) return
        val matches: List<CommentTaskMatch> = when (element) {
            is PsiComment -> ClaudeTaskParser.findTasksInComment(element.text)
            is PsiPlainText -> ClaudeTaskParser.findTasksInPlainText(element.text)
                .map { CommentTaskMatch(it.startOffset, it.endOffset, it.text) }
            else -> return
        }
        if (matches.isEmpty() || !ClaudeSettings.getInstance().highlightingEnabled) return

        val base = element.textRange.startOffset
        for (match in matches) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(base + match.tagStart, base + match.textEnd))
                .textAttributes(ClaudeTaskColors.TASK)
                .create()
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(base + match.tagStart, base + match.tagEnd))
                .textAttributes(ClaudeTaskColors.TAG)
                .create()
        }
    }
}
