package com.claudecommenttasks.run

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/** The parts of the `--output-format json` result object that the plugin uses. */
data class ClaudeCliResult(
    val isError: Boolean?,
    val subtype: String?,
    val resultText: String?,
    val sessionId: String?,
    val totalCostUsd: Double?,
    val durationMs: Long?,
    val numTurns: Int?,
    val errors: List<String>,
)

/** Pure Kotlin parser for the CLI's JSON output. Tolerates warnings printed around the JSON object. */
object ClaudeCliResultParser {

    fun parse(stdout: String): ClaudeCliResult? {
        val json = extractJsonObject(stdout) ?: return null
        return ClaudeCliResult(
            isError = json.boolOrNull("is_error"),
            subtype = json.stringOrNull("subtype"),
            resultText = json.stringOrNull("result"),
            sessionId = json.stringOrNull("session_id"),
            totalCostUsd = json.doubleOrNull("total_cost_usd"),
            durationMs = json.longOrNull("duration_ms"),
            numTurns = json.intOrNull("num_turns"),
            errors = json.stringList("errors"),
        )
    }

    private fun extractJsonObject(stdout: String): JsonObject? {
        val candidates = sequence {
            yield(stdout.trim())
            // A JSON object on its own line, searching from the end (the result is printed last).
            stdout.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.toList().asReversed().forEach { yield(it) }
            val first = stdout.indexOf('{')
            val last = stdout.lastIndexOf('}')
            if (first in 0 until last) yield(stdout.substring(first, last + 1))
        }
        for (candidate in candidates) {
            if (candidate.isEmpty()) continue
            val element = parseOrNull(candidate) ?: continue
            if (element.isJsonObject && element.asJsonObject.has("type")) return element.asJsonObject
        }
        return null
    }

    private fun parseOrNull(text: String): JsonElement? = try {
        JsonParser.parseString(text)
    } catch (_: JsonSyntaxException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun JsonObject.member(name: String): JsonElement? = get(name)?.takeUnless { it.isJsonNull }

    private fun JsonObject.stringOrNull(name: String): String? =
        member(name)?.let { if (it.isJsonPrimitive) it.asString else it.toString() }

    private fun JsonObject.boolOrNull(name: String): Boolean? =
        member(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.doubleOrNull(name: String): Double? =
        member(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private fun JsonObject.longOrNull(name: String): Long? =
        member(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.intOrNull(name: String): Int? =
        member(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.stringList(name: String): List<String> {
        val element = member(name) ?: return emptyList()
        if (!element.isJsonArray) return listOf(element.toString())
        return element.asJsonArray.map { if (it.isJsonPrimitive) it.asString else it.toString() }
    }
}
