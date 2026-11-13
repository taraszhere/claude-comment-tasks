package com.claudecommenttasks.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** Application-level settings, stored in `claudeCommentTasks.xml`. */
@Service(Service.Level.APP)
@State(name = "ClaudeCommentTasksSettings", storages = [Storage("claudeCommentTasks.xml")])
class ClaudeSettings : SimplePersistentStateComponent<ClaudeSettings.State>(State()) {

    class State : BaseState() {
        var claudePath by string()
        var allowedTools by string(DEFAULT_ALLOWED_TOOLS)
        var runOnSave by property(false)
        var timeoutSeconds by property(DEFAULT_TIMEOUT_SECONDS)
        var extraPrompt by string()
        var highlightingEnabled by property(true)
    }

    /** Absolute path to the `claude` executable; empty means "detect automatically". */
    var claudePath: String
        get() = state.claudePath.orEmpty()
        set(value) {
            state.claudePath = value.trim().ifEmpty { null }
        }

    /** Comma-separated value for `--allowedTools`. */
    var allowedTools: String
        get() = state.allowedTools.orEmpty()
        set(value) {
            state.allowedTools = value.trim()
        }

    val allowedToolsOrDefault: String
        get() = allowedTools.ifBlank { DEFAULT_ALLOWED_TOOLS }

    var runOnSave: Boolean
        get() = state.runOnSave
        set(value) {
            state.runOnSave = value
        }

    var timeoutSeconds: Int
        get() = state.timeoutSeconds
        set(value) {
            state.timeoutSeconds = value
        }

    var extraPrompt: String
        get() = state.extraPrompt.orEmpty()
        set(value) {
            state.extraPrompt = value.ifBlank { null }
        }

    var highlightingEnabled: Boolean
        get() = state.highlightingEnabled
        set(value) {
            state.highlightingEnabled = value
        }

    companion object {
        const val DEFAULT_ALLOWED_TOOLS = "Read,Edit,Grep,Glob"
        const val DEFAULT_TIMEOUT_SECONDS = 300

        @JvmStatic
        fun getInstance(): ClaudeSettings = service()
    }
}
