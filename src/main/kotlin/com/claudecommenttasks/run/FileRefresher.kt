package com.claudecommenttasks.run

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresEdt

/** Makes the editor show what Claude wrote to disk: VFS refresh plus document reload. */
object FileRefresher {

    @RequiresEdt
    fun refreshAndReload(file: VirtualFile) {
        if (!file.isValid) return
        // Synchronous refresh of this one file; fires contentsChanged, which reloads unmodified documents.
        VfsUtil.markDirtyAndRefresh(false, false, false, file)
        if (!file.isValid) return

        val manager = FileDocumentManager.getInstance()
        val document = manager.getCachedDocument(file) ?: return
        // The user edited the file during the run: keep their changes, the platform shows its conflict dialog.
        if (manager.isDocumentUnsaved(document)) return
        val onDisk = LoadTextUtil.loadText(file)
        if (!StringUtil.equals(document.immutableCharSequence, onDisk)) {
            manager.reloadFiles(file)
        }
    }
}
