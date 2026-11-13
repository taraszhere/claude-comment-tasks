package com.claudecommenttasks.run

import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.util.Locale

/** Turns a [RunOutcome] into a notification. */
object RunReporter {

    private const val MAX_NOTIFICATION_TEXT = 600

    @RequiresEdt
    fun report(request: RunRequest, outcome: RunOutcome) {
        val project = request.project
        val name = request.file.name
        val openLog = ClaudeNotifications.openToolWindowAction(project)
        when (outcome) {
            is RunOutcome.Success -> ClaudeNotifications.info(
                project,
                "Claude finished ${request.tasks.size} task(s) in $name",
                successText(outcome),
                openLog,
            )
            is RunOutcome.Failure -> ClaudeNotifications.error(
                project,
                "Claude failed on $name",
                shorten(outcome.message) + (outcome.details?.let { "<br><i>${ClaudeNotifications.escape(it)}</i>" } ?: ""),
                openLog,
            )
            is RunOutcome.TimedOut -> ClaudeNotifications.warning(
                project,
                "Claude timed out on $name",
                "No result after ${outcome.seconds} s; the process was stopped and the file may contain partial changes. " +
                    "The timeout can be changed in Settings | Tools | Claude Comment Tasks.",
                openLog,
                ClaudeNotifications.openSettingsAction(project),
            )
            RunOutcome.Cancelled -> ClaudeNotifications.info(
                project,
                "Claude run cancelled",
                "The run on $name was cancelled. The file may contain partial changes.",
                openLog,
            )
            RunOutcome.BinaryNotFound -> ClaudeNotifications.error(
                project,
                "Claude Code CLI not found",
                "The <code>claude</code> executable was not found on the PATH of your login shell. " +
                    "Set its location in Settings | Tools | Claude Comment Tasks.",
                ClaudeNotifications.openSettingsAction(project),
                openLog,
            )
        }
    }

    private fun successText(outcome: RunOutcome.Success): String {
        val result = outcome.result
        val stats = buildString {
            append(outcome.elapsedMs / 1000).append(" s")
            result?.numTurns?.let { append(", ").append(it).append(" turn(s)") }
            result?.totalCostUsd?.let { append(", $").append(String.format(Locale.ROOT, "%.2f", it)) }
        }
        val text = result?.resultText?.trim().orEmpty()
        return if (text.isEmpty()) stats else "${shorten(text)}<br><i>$stats</i>"
    }

    private fun shorten(text: String): String =
        ClaudeNotifications.escape(StringUtil.shortenTextWithEllipsis(text.trim(), MAX_NOTIFICATION_TEXT, 0))
            .replace("\n", "<br>")
}
