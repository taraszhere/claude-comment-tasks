package com.claudecommenttasks.run

import com.claudecommenttasks.settings.ClaudeSettings
import com.claudecommenttasks.toolwindow.ClaudeTasksConsole
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Runs `claude -p "<prompt>" --allowedTools <tools> --output-format stream-json --verbose` for one request
 * and shows the events in the "Claude Tasks" console as readable text (see [ClaudeStreamFormatter]).
 * Runs on a background thread under a cancellable progress indicator; never touches PSI or documents.
 */
object ClaudeCliRunner {

    private val LOG = logger<ClaudeCliRunner>()
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun execute(request: RunRequest, indicator: ProgressIndicator): RunOutcome {
        val console = ClaudeTasksConsole.getInstance(request.project)
        indicator.isIndeterminate = true
        indicator.text = "Locating the claude executable…"

        val binary = ClaudeCliLocator.locate()
        if (binary == null) {
            console.printLine(
                "[${now()}] The claude executable was not found. Set its path in Settings | Tools | Claude Comment Tasks.",
                ConsoleViewContentType.ERROR_OUTPUT,
            )
            LOG.warn("claude executable not found")
            return RunOutcome.BinaryNotFound
        }

        val commandLine = GeneralCommandLine(binary)
            .withParameters(
                "-p", request.prompt,
                "--allowedTools", request.allowedTools,
                // stream-json gives one event per line while Claude works; in print mode it requires --verbose.
                "--output-format", "stream-json", "--verbose",
            )
            .withWorkDirectory(request.workingDirectory)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(Charsets.UTF_8)
        if (!FileUtil.isAncestor(request.workingDirectory, request.file.path, false)) {
            // Claude Code may only edit files below its working directory unless told otherwise.
            request.file.parent?.let { commandLine.addParameters("--add-dir", it.path) }
        }

        val startedAt = System.currentTimeMillis()
        printHeader(console, binary, request)
        LOG.info("Running ${describe(binary, request)} in ${request.workingDirectory}\nPrompt:\n${request.prompt}")

        val handler = try {
            KillableProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            console.printLine("Could not start claude: ${e.message}", ConsoleViewContentType.ERROR_OUTPUT)
            LOG.warn("Could not start claude", e)
            return RunOutcome.Failure("Could not start claude: ${e.message}")
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val formatter = ClaudeStreamFormatter(request.workingDirectory)
        val pendingLine = StringBuilder()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                when (outputType) {
                    ProcessOutputTypes.STDOUT -> {
                        synchronized(stdout) {
                            stdout.append(event.text)
                            pendingLine.append(event.text)
                            printCompleteLines(console, formatter, pendingLine)
                        }
                    }
                    ProcessOutputTypes.STDERR -> {
                        synchronized(stderr) { stderr.append(event.text) }
                        console.print(event.text, ConsoleViewContentType.ERROR_OUTPUT)
                    }
                }
            }
        })
        handler.startNotify()
        // In print mode claude reads stdin until EOF when stdin is not a terminal; close it right away.
        try {
            handler.processInput.close()
        } catch (e: IOException) {
            LOG.debug("Could not close stdin of claude", e)
        }

        indicator.text = "Claude is working on ${request.file.name}…"
        val timeoutMs = request.timeoutSeconds * 1000L
        var timedOut = false
        try {
            while (!handler.isProcessTerminated) {
                indicator.checkCanceled()
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed >= timeoutMs) {
                    timedOut = true
                    break
                }
                indicator.text2 = "${elapsed / 1000} s elapsed (timeout ${request.timeoutSeconds} s)"
                handler.waitFor(250)
            }
        } catch (e: ProcessCanceledException) {
            console.printLine("\n--- [${now()}] Cancelled, stopping claude ---", ConsoleViewContentType.SYSTEM_OUTPUT)
            LOG.info("claude run cancelled for ${request.file.path}")
            stop(handler)
            throw e
        }

        if (timedOut) {
            console.printLine(
                "\n--- [${now()}] Timeout after ${request.timeoutSeconds} s, stopping claude ---",
                ConsoleViewContentType.ERROR_OUTPUT,
            )
            LOG.warn("claude timed out after ${request.timeoutSeconds} s for ${request.file.path}")
            stop(handler)
            return RunOutcome.TimedOut(request.timeoutSeconds)
        }

        val exitCode = handler.exitCode ?: -1
        val elapsedMs = System.currentTimeMillis() - startedAt
        val out = synchronized(stdout) {
            // The last line may lack a trailing newline.
            if (pendingLine.isNotEmpty()) pendingLine.append('\n')
            printCompleteLines(console, formatter, pendingLine)
            stdout.toString()
        }
        val err = synchronized(stderr) { stderr.toString() }
        val result = ClaudeCliResultParser.parse(out)

        val summary = buildString {
            append("exit code ").append(exitCode).append(", ").append(elapsedMs / 1000).append(" s")
            result?.numTurns?.let { append(", ").append(it).append(" turn(s)") }
            result?.totalCostUsd?.let { append(", cost $").append(String.format(Locale.ROOT, "%.4f", it)) }
            result?.subtype?.let { append(", ").append(it) }
        }
        LOG.info("claude finished for ${request.file.path}: $summary\n--- stdout ---\n$out\n--- stderr ---\n$err")

        val failed = exitCode != 0 || result?.isError == true
        printFooter(console, failed, exitCode, elapsedMs, result)
        if (!failed) return RunOutcome.Success(result, elapsedMs)

        val message = sequenceOf(
            result?.resultText,
            result?.errors?.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            err,
            formatter.plainLines.joinToString("\n"),
        ).map { it?.trim().orEmpty() }.firstOrNull { it.isNotEmpty() } ?: "claude exited with code $exitCode"
        return RunOutcome.Failure(message, summary)
    }

    /** SIGINT lets claude finish its turn cleanly; fall back to a hard kill of the process tree. */
    private fun stop(handler: KillableProcessHandler) {
        if (handler.isProcessTerminated) return
        handler.destroyProcess()
        if (!handler.waitFor(5_000)) {
            handler.killProcess()
            handler.waitFor(2_000)
        }
    }

    private fun printHeader(console: ClaudeTasksConsole, binary: String, request: RunRequest) {
        val header = ConsoleViewContentType.SYSTEM_OUTPUT
        console.print("━━ ${now()}  ", header)
        console.printFileLink(request.file.name, request.file.path, header)
        console.printLine("  ·  ${request.tasks.size} task(s) ━━━━━━━━━━━━━━━━━━━━━━━━", header)
        for (task in request.tasks) {
            console.printLine("  ● Line ${task.line}: ${task.text}", ConsoleViewContentType.USER_INPUT)
        }
        if (ClaudeSettings.getInstance().showRunDetails) {
            val meta = ConsoleViewContentType.LOG_DEBUG_OUTPUT
            console.printLine("Working directory: ${request.workingDirectory}", meta)
            console.printLine("Command: ${describe(binary, request)}", meta)
            console.printLine("Prompt:\n${request.prompt.prependIndent("  ")}", meta)
        }
        console.printLine("")
    }

    private fun printFooter(
        console: ClaudeTasksConsole,
        failed: Boolean,
        exitCode: Int,
        elapsedMs: Long,
        result: ClaudeCliResult?,
    ) {
        val stats = buildList {
            add("${elapsedMs / 1000} s")
            result?.numTurns?.let { add("$it turn(s)") }
            result?.totalCostUsd?.let { add("$" + String.format(Locale.ROOT, "%.4f", it)) }
        }.joinToString(" · ")
        if (failed) {
            val reason = result?.subtype?.takeIf { it != "success" } ?: "exit code $exitCode"
            console.printLine("✗ Failed ($reason) after $stats\n", ConsoleViewContentType.ERROR_OUTPUT)
        } else {
            console.printLine("✓ Done in $stats\n", ConsoleViewContentType.USER_INPUT)
        }
    }

    /** Formats and prints every complete line in [pending], leaving a trailing partial line in place. */
    private fun printCompleteLines(console: ClaudeTasksConsole, formatter: ClaudeStreamFormatter, pending: StringBuilder) {
        while (true) {
            val end = pending.indexOf("\n")
            if (end < 0) return
            val line = pending.substring(0, end)
            pending.delete(0, end + 1)
            for (segment in formatter.format(line)) {
                val type = styleOf(segment.style)
                if (segment.filePath != null) console.printFileLink(segment.text, segment.filePath, type)
                else console.print(segment.text, type)
            }
        }
    }

    private fun styleOf(style: OutputStyle): ConsoleViewContentType = when (style) {
        OutputStyle.META -> ConsoleViewContentType.LOG_DEBUG_OUTPUT
        OutputStyle.TOOL -> ConsoleViewContentType.SYSTEM_OUTPUT
        OutputStyle.TEXT -> ConsoleViewContentType.NORMAL_OUTPUT
        OutputStyle.ERROR -> ConsoleViewContentType.ERROR_OUTPUT
        OutputStyle.WARNING -> ConsoleViewContentType.LOG_WARNING_OUTPUT
    }

    private fun describe(binary: String, request: RunRequest): String =
        "$binary -p \"<prompt>\" --allowedTools \"${request.allowedTools}\" --output-format stream-json --verbose"

    private fun now(): String = LocalTime.now().format(TIME)
}
