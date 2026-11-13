package com.claudecommenttasks.run

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
 * Runs `claude -p "<prompt>" --allowedTools <tools> --output-format json` for one request.
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
            .withParameters("-p", request.prompt, "--allowedTools", request.allowedTools, "--output-format", "json")
            .withWorkDirectory(request.workingDirectory)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(Charsets.UTF_8)
        if (!FileUtil.isAncestor(request.workingDirectory, request.file.path, false)) {
            // Claude Code may only edit files below its working directory unless told otherwise.
            request.file.parent?.let { commandLine.addParameters("--add-dir", it.path) }
        }

        val startedAt = System.currentTimeMillis()
        console.printLine(
            "=== [${now()}] Claude run: ${request.file.path} (${request.tasks.size} task(s)) ===",
            ConsoleViewContentType.SYSTEM_OUTPUT,
        )
        console.printLine("Working directory: ${request.workingDirectory}", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.printLine("Command: ${describe(binary, request)}", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.printLine("Prompt:\n${request.prompt}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
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
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                when (outputType) {
                    ProcessOutputTypes.STDOUT -> {
                        synchronized(stdout) { stdout.append(event.text) }
                        console.print(event.text, ConsoleViewContentType.NORMAL_OUTPUT)
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
        val out = synchronized(stdout) { stdout.toString() }
        val err = synchronized(stderr) { stderr.toString() }
        val result = ClaudeCliResultParser.parse(out)

        val summary = buildString {
            append("exit code ").append(exitCode).append(", ").append(elapsedMs / 1000).append(" s")
            result?.numTurns?.let { append(", ").append(it).append(" turn(s)") }
            result?.totalCostUsd?.let { append(", cost $").append(String.format(Locale.ROOT, "%.4f", it)) }
            result?.subtype?.let { append(", ").append(it) }
        }
        console.printLine("\n=== [${now()}] Finished: $summary ===\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        LOG.info("claude finished for ${request.file.path}: $summary\n--- stdout ---\n$out\n--- stderr ---\n$err")

        val failed = exitCode != 0 || result?.isError == true
        if (!failed) return RunOutcome.Success(result, elapsedMs)

        val message = sequenceOf(
            result?.resultText,
            result?.errors?.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            err,
            out,
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

    private fun describe(binary: String, request: RunRequest): String =
        "$binary -p \"<prompt>\" --allowedTools \"${request.allowedTools}\" --output-format json"

    private fun now(): String = LocalTime.now().format(TIME)
}
