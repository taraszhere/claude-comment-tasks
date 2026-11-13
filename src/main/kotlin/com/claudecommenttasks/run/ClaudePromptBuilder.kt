package com.claudecommenttasks.run

import com.claudecommenttasks.parser.ClaudeTask

/** Builds the prompt passed to `claude -p`. Pure Kotlin, unit tested. */
object ClaudePromptBuilder {

    const val INSTRUCTION =
        "Do each task. Change only what the task needs. Remove each @claude comment when its task is done."

    fun build(filePath: String, language: String, tasks: List<ClaudeTask>, extraPrompt: String = ""): String {
        val sb = StringBuilder()
        sb.appendLine("File: $filePath")
        sb.appendLine("Language: $language")
        sb.appendLine()
        sb.appendLine("The file contains ${tasks.size} task comment(s) marked with @claude. Line numbers are 1-based.")
        for (task in tasks) {
            sb.appendLine("- Line ${task.line}: ${task.text}")
        }
        sb.appendLine()
        sb.appendLine(INSTRUCTION)
        sb.appendLine("Only the @claude comments listed above belong to this request; leave other comments in place.")
        if (extraPrompt.isNotBlank()) {
            sb.appendLine()
            sb.appendLine(extraPrompt.trim())
        }
        return sb.toString().trimEnd()
    }
}
