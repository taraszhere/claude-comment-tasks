package com.claudecommenttasks.run

import com.claudecommenttasks.parser.ClaudeTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Where a run was started from. Save-triggered runs stay silent when there is nothing to do. */
enum class RunSource { ACTION, GUTTER, SAVE }

/** Everything the background task needs; computed on the EDT before the task starts. */
class RunRequest(
    val project: Project,
    val file: VirtualFile,
    val tasks: List<ClaudeTask>,
    val prompt: String,
    val workingDirectory: String,
    val allowedTools: String,
    val timeoutSeconds: Int,
)

sealed class RunOutcome {
    class Success(val result: ClaudeCliResult?, val elapsedMs: Long) : RunOutcome()
    class Failure(val message: String, val details: String? = null) : RunOutcome()
    class TimedOut(val seconds: Int) : RunOutcome()
    object Cancelled : RunOutcome()
    object BinaryNotFound : RunOutcome()
}
