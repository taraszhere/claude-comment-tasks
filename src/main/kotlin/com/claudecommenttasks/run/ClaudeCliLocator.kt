package com.claudecommenttasks.run

import com.claudecommenttasks.settings.ClaudeSettings
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import com.intellij.util.SystemProperties
import java.io.File

/**
 * Finds the `claude` executable: the configured path first, then the PATH known to the IDE, then the
 * PATH of a login shell (`$SHELL -lc 'command -v claude'`), then a few well-known install locations.
 * Starts a shell, so call it from a background thread.
 */
object ClaudeCliLocator {

    private val LOG = logger<ClaudeCliLocator>()

    @Volatile
    private var cached: String? = null

    fun locate(settings: ClaudeSettings = ClaudeSettings.getInstance()): String? {
        val configured = settings.claudePath
        if (configured.isNotBlank()) {
            val file = File(expandUserHome(configured))
            return if (file.isFile && file.canExecute()) file.absolutePath else null
        }
        cached?.let { if (File(it).canExecute()) return it }

        val found = findOnIdePath() ?: findViaLoginShell() ?: findInWellKnownLocations()
        cached = found
        return found
    }

    private fun executableNames(): List<String> =
        if (SystemInfo.isWindows) listOf("claude.exe", "claude.cmd", "claude.bat", "claude") else listOf("claude")

    /** The PATH the IDE itself knows (on macOS this already comes from the user's login shell). */
    private fun findOnIdePath(): String? {
        val path = EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH") ?: return null
        val names = executableNames()
        for (dir in path.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            for (name in names) {
                val candidate = File(dir, name)
                if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
            }
        }
        return null
    }

    private fun findViaLoginShell(): String? {
        if (SystemInfo.isWindows) return null
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() && File(it).canExecute() } ?: "/bin/sh"
        return runShell(shell, "-lc", "command -v claude")
            ?: runShell(shell, "-lic", "command -v claude")
    }

    private fun runShell(shell: String, vararg args: String): String? {
        val commandLine = GeneralCommandLine(shell, *args)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        return try {
            val output = ExecUtil.execAndGetOutput(commandLine, 15_000)
            if (output.isTimeout) {
                LOG.info("Timed out while running ${commandLine.commandLineString}")
                return null
            }
            output.stdoutLines.asReversed().map { it.trim() }
                .firstOrNull { it.startsWith("/") && File(it).canExecute() }
        } catch (e: ExecutionException) {
            LOG.info("Could not run ${commandLine.commandLineString}: ${e.message}")
            null
        }
    }

    private fun expandUserHome(path: String): String =
        if (path == "~" || path.startsWith("~/") || path.startsWith("~\\")) SystemProperties.getUserHome() + path.substring(1) else path

    private fun findInWellKnownLocations(): String? {
        val home = SystemProperties.getUserHome()
        val candidates = listOf(
            "$home/.local/bin/claude",
            "$home/.claude/local/claude",
            "/opt/homebrew/bin/claude",
            "/usr/local/bin/claude",
            "$home/.npm-global/bin/claude",
            "/usr/bin/claude",
        )
        return candidates.firstOrNull { File(it).canExecute() }
    }
}
