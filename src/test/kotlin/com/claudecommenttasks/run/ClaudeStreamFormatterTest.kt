package com.claudecommenttasks.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeStreamFormatterTest {

    private val base = "/work/app"

    private fun text(segments: List<OutputSegment>) = segments.joinToString("") { it.text }

    @Test
    fun `init event shows model and permission mode`() {
        val f = ClaudeStreamFormatter(base)
        val out = f.format("""{"type":"system","subtype":"init","model":"claude-haiku-5-5","permissionMode":"default","tools":["Read"]}""")
        assertEquals("Model: claude-haiku-5-5 · permission mode: default\n", text(out))
        assertEquals(OutputStyle.META, out.single().style)
    }

    @Test
    fun `other system events and unknown types are hidden`() {
        val f = ClaudeStreamFormatter(base)
        assertTrue(f.format("""{"type":"system","subtype":"hook_started","hook_id":"x"}""").isEmpty())
        assertTrue(f.format("""{"type":"rate_limit_event","rate_limit_info":{}}""").isEmpty())
        assertTrue(f.format("   ").isEmpty())
    }

    @Test
    fun `file tool calls link to the file with a project relative path`() {
        val f = ClaudeStreamFormatter(base)
        val out = f.format(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Read",""" +
                """"input":{"file_path":"/work/app/src/Util.kt"}}]},"parent_tool_use_id":null}""",
        )
        assertEquals("  ▸ Read   src/Util.kt\n", text(out))
        assertEquals("/work/app/src/Util.kt", out.single { it.filePath != null }.filePath)
    }

    @Test
    fun `bash and search tool calls show their key argument on one line`() {
        val f = ClaudeStreamFormatter(base)
        val bash = f.format(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash",""" +
                """"input":{"command":"./gradlew test\nexit","description":"Run tests"}}]}}""",
        )
        assertEquals("  ▸ Bash   ./gradlew test …\n", text(bash))
        val grep = f.format(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Grep",""" +
                """"input":{"pattern":"TODO","path":"/work/app/src"}}]}}""",
        )
        assertEquals("  ▸ Grep   TODO in src\n", text(grep))
    }

    @Test
    fun `successful tool results are hidden and errors show their first line`() {
        val f = ClaudeStreamFormatter(base)
        assertTrue(
            f.format("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"1\thello"}]}}""")
                .isEmpty(),
        )
        val out = f.format(
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2",""" +
                """"content":"Exit code 1\ncat: nope.txt: No such file or directory","is_error":true}]}}""",
        )
        assertEquals("    ✗ Exit code 1 …\n", text(out))
        assertEquals(OutputStyle.ERROR, out.single().style)
    }

    @Test
    fun `thinking blocks are hidden and the final answer is not printed twice`() {
        val f = ClaudeStreamFormatter(base)
        assertTrue(f.format("""{"type":"assistant","message":{"content":[{"type":"thinking","thinking":""}]}}""").isEmpty())
        val answer = f.format("""{"type":"assistant","message":{"content":[{"type":"text","text":"Added the comment."}]}}""")
        assertEquals("\nAdded the comment.\n\n", text(answer))
        val result = f.format(
            """{"type":"result","subtype":"success","is_error":false,"result":"Added the comment.","permission_denials":[]}""",
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `result shows its text when it differs and warns about denied permissions`() {
        val f = ClaudeStreamFormatter(base)
        val out = f.format(
            """{"type":"result","subtype":"success","is_error":false,"result":"Not done.","permission_denials":[""" +
                """{"tool_name":"Bash","tool_use_id":"t1","tool_input":{"command":"touch x.txt"}},""" +
                """{"tool_name":"Write","tool_use_id":"t2","tool_input":{"file_path":"/work/app/x.txt","content":""}}]}""",
        )
        assertEquals(
            "\nNot done.\n\n⚠ Permission denied: Bash (touch x.txt)\n⚠ Permission denied: Write (x.txt)\n",
            text(out),
        )
    }

    @Test
    fun `error results list their errors`() {
        val f = ClaudeStreamFormatter(base)
        val out = f.format("""{"type":"result","subtype":"error_during_execution","is_error":true,"errors":["Not logged in"],"result":""}""")
        assertEquals("✗ Not logged in\n", text(out))
    }

    @Test
    fun `non json lines are kept as plain text`() {
        val f = ClaudeStreamFormatter(base)
        val out = f.format("Warning: something odd")
        assertEquals("Warning: something odd\n", text(out))
        assertEquals(listOf("Warning: something odd"), f.plainLines)
    }

    @Test
    fun `sub-agent output is indented further`() {
        val f = ClaudeStreamFormatter(base)
        val out = f.format(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Glob","input":{"pattern":"**/*.kt"}}]},""" +
                """"parent_tool_use_id":"toolu_parent"}""",
        )
        assertEquals("    ▸ Glob   **/*.kt\n", text(out))
    }
}
