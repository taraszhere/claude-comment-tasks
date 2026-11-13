package com.claudecommenttasks.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

/** Settings | Tools | Claude Comment Tasks. */
class ClaudeSettingsConfigurable : BoundConfigurable("Claude Comment Tasks") {

    private val settings get() = ClaudeSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group("Claude Code CLI") {
            row("Path to claude:") {
                val descriptor = FileChooserDescriptorFactory.singleFile()
                    .withTitle("Select the claude Executable")
                textFieldWithBrowseButton(descriptor)
                    .bindText(settings::claudePath)
                    .align(AlignX.FILL)
                    .comment("Optional. When empty, <code>claude</code> is looked up on the PATH of your login shell " +
                        "(<code>\$SHELL -lc 'command -v claude'</code>).")
            }
            row("Allowed tools:") {
                textField()
                    .bindText(settings::allowedTools)
                    .align(AlignX.FILL)
                    .comment("Comma-separated list passed as <code>--allowedTools</code>. " +
                        "Default: ${ClaudeSettings.DEFAULT_ALLOWED_TOOLS}")
            }
            row("Timeout (seconds):") {
                intTextField(1..86_400)
                    .bindIntText(settings::timeoutSeconds)
                    .comment("The run is stopped when it takes longer. Default: ${ClaudeSettings.DEFAULT_TIMEOUT_SECONDS}")
            }
            row("Extra prompt text:") {
                textArea()
                    .bindText(settings::extraPrompt)
                    .rows(4)
                    .align(AlignX.FILL)
                    .comment("Optional. Appended to the prompt of every run, e.g. coding conventions.")
            }
        }
        group("Behavior") {
            row {
                checkBox("Run @claude tasks when a file is saved")
                    .bindSelected(settings::runOnSave)
                    .comment("Triggered by explicit saves (Save All / ⌘S / Ctrl+S), not by autosave. " +
                        "A file is skipped while a run on it is in progress.")
            }
            row {
                checkBox("Highlight @claude comments in the editor")
                    .bindSelected(settings::highlightingEnabled)
                    .comment("Colors are configured in Settings | Editor | Color Scheme | Claude Comment Tasks.")
            }
        }
    }

    override fun apply() {
        val highlightingBefore = settings.highlightingEnabled
        super.apply()
        if (highlightingBefore != settings.highlightingEnabled) {
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("Claude Comment Tasks highlighting toggled")
            }
        }
    }
}
