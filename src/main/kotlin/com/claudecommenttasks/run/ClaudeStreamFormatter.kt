package com.claudecommenttasks.run

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/** How a piece of console output is styled; mapped to console content types by the runner. */
enum class OutputStyle { META, TOOL, TEXT, ERROR, WARNING }

/** A piece of console output. [filePath] (absolute) turns the text into a link that opens the file. */
data class OutputSegment(val text: String, val style: OutputStyle, val filePath: String? = null)

/**
 * Turns the line-delimited events of `claude -p --output-format stream-json --verbose` into readable
 * console output. Pure Kotlin, unit tested. One instance per run: it remembers the last text Claude wrote,
 * so the final answer is not printed twice.
 */
class ClaudeStreamFormatter(private val baseDir: String) {

    private var lastText: String? = null

    /** Lines of stdout that were not JSON (warnings and the like), for error messages. */
    val plainLines = mutableListOf<String>()

    fun format(line: String): List<OutputSegment> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val event = parseObject(trimmed)
        if (event == null) {
            plainLines += trimmed
            return listOf(OutputSegment(trimmed + "\n", OutputStyle.TEXT))
        }
        val nested = event.str("parent_tool_use_id") != null
        return when (event.str("type")) {
            "system" -> if (event.str("subtype") == "init") init(event) else emptyList()
            "assistant" -> assistant(event, nested)
            "user" -> toolResults(event, nested)
            "result" -> result(event)
            else -> emptyList()
        }
    }

    private fun init(event: JsonObject): List<OutputSegment> {
        val parts = listOfNotNull(
            event.str("model")?.let { "Model: $it" },
            event.str("permissionMode")?.let { "permission mode: $it" },
        )
        return if (parts.isEmpty()) emptyList() else listOf(OutputSegment(parts.joinToString(" · ") + "\n", OutputStyle.META))
    }

    private fun assistant(event: JsonObject, nested: Boolean): List<OutputSegment> {
        val indent = if (nested) "    " else "  "
        val out = mutableListOf<OutputSegment>()
        for (block in event.obj("message")?.array("content").orEmpty()) {
            when (block.str("type")) {
                "text" -> {
                    val text = block.str("text")?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    if (!nested) lastText = text
                    val body = if (nested) text.prependIndent("    ") else text
                    out += OutputSegment("\n$body\n\n", OutputStyle.TEXT)
                }
                "tool_use" -> out += toolUse(block, indent)
            }
        }
        return out
    }

    private fun toolUse(block: JsonObject, indent: String): List<OutputSegment> {
        val name = block.str("name") ?: "tool"
        val input = block.obj("input") ?: JsonObject()
        val head = OutputSegment("$indent▸ ${name.padEnd(6)} ", OutputStyle.TOOL)
        val path = filePathOf(input)
        if (path != null) {
            return listOf(head, OutputSegment(relativize(path), OutputStyle.TOOL, path), OutputSegment("\n", OutputStyle.TOOL))
        }
        return listOf(head, OutputSegment(oneLine(describeInput(name, input), 160) + "\n", OutputStyle.TOOL))
    }

    private fun toolResults(event: JsonObject, nested: Boolean): List<OutputSegment> {
        val indent = if (nested) "      " else "    "
        val out = mutableListOf<OutputSegment>()
        for (block in event.obj("message")?.array("content").orEmpty()) {
            if (block.str("type") != "tool_result" || block.bool("is_error") != true) continue
            val text = contentText(block.get("content"))
            out += OutputSegment("$indent✗ ${oneLine(text, 200)}\n", OutputStyle.ERROR)
        }
        return out
    }

    private fun result(event: JsonObject): List<OutputSegment> {
        val out = mutableListOf<OutputSegment>()
        val text = event.str("result")?.trim().orEmpty()
        if (text.isNotEmpty() && text != lastText) out += OutputSegment("\n$text\n\n", OutputStyle.TEXT)
        if (event.bool("is_error") == true) {
            event.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { error ->
                val message = when {
                    error.isJsonPrimitive -> error.asString
                    error.isJsonObject -> error.asJsonObject.str("message") ?: error.toString()
                    else -> error.toString()
                }
                out += OutputSegment("✗ $message\n", OutputStyle.ERROR)
            }
        }
        for (denial in event.array("permission_denials").orEmpty()) {
            val name = denial.str("tool_name") ?: "tool"
            val input = denial.obj("tool_input") ?: JsonObject()
            val detail = filePathOf(input)?.let { relativize(it) } ?: oneLine(describeInput(name, input), 160)
            out += OutputSegment(
                "⚠ Permission denied: $name${if (detail.isEmpty()) "" else " ($detail)"}\n",
                OutputStyle.WARNING,
            )
        }
        return out
    }

    private fun describeInput(name: String, input: JsonObject): String = when (name) {
        "Bash" -> input.str("command")
        "Grep" -> listOfNotNull(input.str("pattern"), input.str("path")?.let { "in ${relativize(it)}" }).joinToString(" ")
        "Glob" -> listOfNotNull(input.str("pattern"), input.str("path")?.let { "in ${relativize(it)}" }).joinToString(" ")
        "LS" -> input.str("path")?.let { relativize(it) }
        "WebFetch" -> input.str("url")
        "WebSearch" -> input.str("query")
        "Task", "Agent" -> input.str("description")
        "TodoWrite" -> input.array("todos")?.let { "${it.size} item(s)" }
        else -> null
    } ?: input.entrySet().firstOrNull { it.value.isJsonPrimitive }?.value?.asString.orEmpty()

    private fun filePathOf(input: JsonObject): String? = input.str("file_path") ?: input.str("notebook_path")

    private fun relativize(path: String): String {
        val base = baseDir.trimEnd('/', '\\')
        return if (base.isNotEmpty() && (path.startsWith("$base/") || path.startsWith("$base\\"))) path.substring(base.length + 1) else path
    }

    private fun contentText(content: JsonElement?): String = when {
        content == null || content.isJsonNull -> ""
        content.isJsonPrimitive -> content.asString
        content.isJsonArray -> content.asJsonArray.mapNotNull {
            if (it.isJsonObject) it.asJsonObject.str("text") else null
        }.joinToString("\n")
        else -> content.toString()
    }

    private fun oneLine(text: String, max: Int): String {
        val first = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val more = text.trim().lines().count { it.isNotBlank() } > 1
        val clipped = if (first.length > max) first.take(max - 1) + "…" else first
        return if (more && !clipped.endsWith("…")) "$clipped …" else clipped
    }

    private fun parseObject(text: String): JsonObject? {
        if (!text.startsWith("{")) return null
        return try {
            JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun JsonObject.str(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.bool(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(name: String): List<JsonObject>? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonObject }?.map { it.asJsonObject }
}
