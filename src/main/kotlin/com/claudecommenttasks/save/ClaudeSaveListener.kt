package com.claudecommenttasks.save

import com.claudecommenttasks.parser.ClaudeTaskParser
import com.claudecommenttasks.run.ClaudeTaskRunner
import com.claudecommenttasks.run.RunSource
import com.claudecommenttasks.settings.ClaudeSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.containers.ContainerUtil

/**
 * Optional "run on save" (off by default). The run is scheduled with `invokeLater` after the save has
 * completed, so saving is never blocked. Only explicit saves (Save All, Ctrl+S/⌘S, API calls) trigger
 * a run; autosave on focus loss or idle (`saveAllDocuments(false)`) does not.
 *
 * The explicit flag is only available per document through [FileDocumentSynchronizationVetoer], which
 * the platform asks right before each document is written; [ClaudeSaveVetoer] records it.
 */
class ClaudeSaveListener : FileDocumentManagerListener {

    override fun afterDocumentSaved(document: Document) {
        val explicit = ClaudeSaveVetoer.consumeExplicit(document)
        if (!explicit || !ClaudeSettings.getInstance().runOnSave) return
        if (!StringUtil.contains(document.immutableCharSequence, ClaudeTaskParser.TAG)) return

        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!file.isValid || file.isDirectory || !file.isInLocalFileSystem) return
        val runner = ClaudeTaskRunner.getInstance()
        if (runner.isRunning(file)) return
        val project = findProject(file) ?: return

        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed && file.isValid && !runner.isRunning(file)) {
                runner.runTasksInFile(project, file, RunSource.SAVE)
            }
        }, ModalityState.nonModal(), project.disposed)
    }

    private fun findProject(file: VirtualFile): Project? {
        ProjectLocator.getInstance().guessProjectForFile(file)?.takeIf { !it.isDisposed }?.let { return it }
        return ProjectManager.getInstanceIfCreated()?.openProjects?.firstOrNull { !it.isDisposed && !it.isDefault }
    }
}

/** Never vetoes anything; only remembers whether the save of a document was explicit. */
class ClaudeSaveVetoer : FileDocumentSynchronizationVetoer() {

    override fun maySaveDocument(document: Document, isSaveExplicit: Boolean): Boolean {
        synchronized(explicitSaves) {
            if (isSaveExplicit) explicitSaves.add(document) else explicitSaves.remove(document)
        }
        return true
    }

    companion object {
        private val explicitSaves: MutableSet<Document> = ContainerUtil.createWeakSet()

        fun consumeExplicit(document: Document): Boolean = synchronized(explicitSaves) { explicitSaves.remove(document) }
    }
}
