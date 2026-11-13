package com.claudecommenttasks.run

import com.claudecommenttasks.parser.ClaudeTask
import com.claudecommenttasks.parser.ClaudeTaskParser
import com.claudecommenttasks.psi.ClaudeTaskFinder
import com.claudecommenttasks.settings.ClaudeSettings
import com.intellij.lang.Language
import com.intellij.notification.Notification
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.SystemProperties
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.util.concurrent.ConcurrentHashMap

/**
 * Starts Claude runs and keeps the set of files with a run in progress (loop prevention).
 */
@Service(Service.Level.APP)
class ClaudeTaskRunner {

    private val inProgress: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isRunning(file: VirtualFile): Boolean = file.path in inProgress

    /**
     * Finds the tasks of [file], saves its document and runs Claude in the background.
     * With [onlyTask] only that task is sent, otherwise all tasks of the file.
     */
    @RequiresEdt
    fun runTasksInFile(project: Project, file: VirtualFile, source: RunSource, onlyTask: ClaudeTask? = null) {
        ThreadingAssertions.assertEventDispatchThread()
        val interactive = source != RunSource.SAVE
        if (project.isDisposed) return
        if (!file.isValid || file.isDirectory || !file.isInLocalFileSystem) {
            if (interactive) ClaudeNotifications.warning(project, "Claude Comment Tasks", "Only local files can be processed.")
            return
        }
        if (isRunning(file)) {
            if (interactive) {
                ClaudeNotifications.info(
                    project,
                    "Claude is still working on ${file.name}",
                    "Wait for the current run to finish before starting another one.",
                )
            }
            return
        }

        val documentManager = FileDocumentManager.getInstance()
        val document = documentManager.getDocument(file)
        val psiFile = PsiManager.getInstance(project).findFile(file)
        // EDT with the write-intent lock: a plain, non-cancellable read is what we want here.
        val allTasks: List<ClaudeTask> = runReadActionBlocking {
            when {
                psiFile != null -> ClaudeTaskFinder.findTasks(psiFile)
                document != null -> ClaudeTaskParser.findTasksInPlainText(document.immutableCharSequence)
                else -> emptyList()
            }
        }
        val tasks = selectTasks(allTasks, onlyTask)
        if (tasks.isEmpty()) {
            if (interactive) {
                ClaudeNotifications.info(project, "No @claude tasks", "${file.name} contains no @claude comments.")
            }
            return
        }

        if (!inProgress.add(file.path)) return
        var started = false
        try {
            // Save first: Claude works on the file on disk. (Our save listener ignores this save
            // because the file is already marked as in progress.)
            if (document != null) documentManager.saveDocument(document)

            val settings = ClaudeSettings.getInstance()
            val language = runReadActionBlocking { psiFile?.language?.takeIf { it != Language.ANY }?.displayName }
                ?: file.fileType.displayName
            val workingDirectory = project.basePath ?: file.parent?.path ?: SystemProperties.getUserHome()
            val prompt = ClaudePromptBuilder.build(file.path, language, tasks, settings.extraPrompt)
            val request = RunRequest(
                project = project,
                file = file,
                tasks = tasks,
                prompt = prompt,
                workingDirectory = workingDirectory,
                allowedTools = settings.allowedToolsOrDefault,
                timeoutSeconds = settings.timeoutSeconds.coerceAtLeast(1),
            )
            val progressNotification = ClaudeNotifications.showInProgress(project, file.name)
            ProgressManager.getInstance().run(ClaudeRunTask(request, progressNotification))
            started = true
        } finally {
            if (!started) inProgress.remove(file.path)
        }
    }

    private fun selectTasks(all: List<ClaudeTask>, only: ClaudeTask?): List<ClaudeTask> {
        if (only == null) return all
        // The task object may come from an older highlighting pass; match by position, then text, then line.
        all.firstOrNull { it.startOffset == only.startOffset && it.text == only.text }?.let { return listOf(it) }
        all.firstOrNull { it.text == only.text }?.let { return listOf(it) }
        all.firstOrNull { it.line == only.line }?.let { return listOf(it) }
        return emptyList()
    }

    private inner class ClaudeRunTask(
        private val request: RunRequest,
        private val progressNotification: Notification,
    ) : Task.Backgroundable(request.project, "Claude is working on ${request.file.name}", true) {

        @Volatile
        private var outcome: RunOutcome? = null

        override fun run(indicator: ProgressIndicator) {
            outcome = ClaudeCliRunner.execute(request, indicator)
        }

        override fun onCancel() {
            if (outcome == null) outcome = RunOutcome.Cancelled
        }

        override fun onThrowable(error: Throwable) {
            LOG.warn("Claude run failed for ${request.file.path}", error)
            outcome = RunOutcome.Failure("Unexpected error: ${error.message ?: error.javaClass.simpleName}")
        }

        /** Runs on the EDT after the task finished, regardless of the outcome. */
        override fun onFinished() {
            inProgress.remove(request.file.path)
            progressNotification.expire()
            if (request.project.isDisposed) return
            FileRefresher.refreshAndReload(request.file)
            RunReporter.report(request, outcome ?: RunOutcome.Failure("The run produced no result."))
        }
    }

    companion object {
        private val LOG = logger<ClaudeTaskRunner>()

        fun getInstance(): ClaudeTaskRunner = service()
    }
}
