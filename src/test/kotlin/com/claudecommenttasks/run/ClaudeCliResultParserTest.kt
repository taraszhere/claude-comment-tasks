package com.claudecommenttasks.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeCliResultParserTest {

    @Test
    fun `parses a successful result object`() {
        val json = """{"type":"result","subtype":"success","is_error":false,"duration_ms":1862,"num_turns":1,""" +
            """"result":"Done.","session_id":"abc","total_cost_usd":0.47,"permission_denials":[]}"""
        val result = ClaudeCliResultParser.parse(json)
        assertNotNull(result)
        assertEquals(false, result!!.isError)
        assertEquals("success", result.subtype)
        assertEquals("Done.", result.resultText)
        assertEquals("abc", result.sessionId)
        assertEquals(0.47, result.totalCostUsd!!, 1e-9)
        assertEquals(1862L, result.durationMs)
        assertEquals(1, result.numTurns)
    }

    @Test
    fun `parses an error result with errors array and surrounding warnings`() {
        val stdout = "Warning: no stdin data received in 3s, proceeding without it.\n" +
            """{"type":"result","subtype":"error_during_execution","is_error":true,"errors":["Not logged in"],"result":""}""" + "\n"
        val result = ClaudeCliResultParser.parse(stdout)
        assertNotNull(result)
        assertEquals(true, result!!.isError)
        assertEquals("error_during_execution", result.subtype)
        assertEquals(listOf("Not logged in"), result.errors)
    }

    @Test
    fun `returns null for non-json output`() {
        assertNull(ClaudeCliResultParser.parse(""))
        assertNull(ClaudeCliResultParser.parse("plain text output"))
        assertNull(ClaudeCliResultParser.parse("{\"foo\": 1}"))
    }
}
