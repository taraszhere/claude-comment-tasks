package com.claudecommenttasks.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeTaskParserTest {

    // ---- Scala / Java-style line comments -------------------------------------------------------

    @Test
    fun `scala line comment`() {
        val source = """
            |object Numbers {
            |  // @claude add a method that calculates the sum of the numbers in a Set
            |  val numbers: Set[Int] = Set(1, 2, 3)
            |}
        """.trimMargin()
        val tasks = ClaudeTaskParser.findTasksInPlainText(source)
        assertEquals(1, tasks.size)
        assertEquals(2, tasks[0].line)
        assertEquals("add a method that calculates the sum of the numbers in a Set", tasks[0].text)
        assertEquals("@claude add a method that calculates the sum of the numbers in a Set",
            source.substring(tasks[0].startOffset, tasks[0].endOffset))
    }

    @Test
    fun `scala comment text via comment parser`() {
        val matches = ClaudeTaskParser.findTasksInComment("// @claude add a method that calculates the sum")
        assertEquals(1, matches.size)
        assertEquals(3, matches[0].tagStart)
        assertEquals("add a method that calculates the sum", matches[0].text)
    }

    // ---- Python ---------------------------------------------------------------------------------

    @Test
    fun `python hash comment with line numbers`() {
        val source = "import os\n\n# @claude add a function that removes duplicates from a list\ndef main():\n    pass  # @claude implement main\n"
        val tasks = ClaudeTaskParser.findTasksInPlainText(source)
        assertEquals(listOf(3, 5), tasks.map { it.line })
        assertEquals(
            listOf("add a function that removes duplicates from a list", "implement main"),
            tasks.map { it.text },
        )
    }

    @Test
    fun `python comment via comment parser`() {
        val matches = ClaudeTaskParser.findTasksInComment("# @claude add a function that removes duplicates from a list")
        assertEquals(listOf("add a function that removes duplicates from a list"), matches.map { it.text })
    }

    // ---- Java block and doc comments ------------------------------------------------------------

    @Test
    fun `java single line block comment strips closing delimiter`() {
        val matches = ClaudeTaskParser.findTasksInComment("/* @claude extract this into a helper */")
        assertEquals(1, matches.size)
        assertEquals("extract this into a helper", matches[0].text)
        assertEquals("@claude extract this into a helper", "/* @claude extract this into a helper */".substring(matches[0].tagStart, matches[0].textEnd))
    }

    @Test
    fun `java doc comment with two tasks`() {
        val comment = "/**\n * Computes things.\n * @claude document the return value\n * @claude add a null check for the parameter\n */"
        val matches = ClaudeTaskParser.findTasksInComment(comment)
        assertEquals(listOf("document the return value", "add a null check for the parameter"), matches.map { it.text })
        assertEquals(3, ClaudeTaskParser.lineNumber(comment, matches[0].tagStart))
        assertEquals(4, ClaudeTaskParser.lineNumber(comment, matches[1].tagStart))
    }

    @Test
    fun `java doc comment task on the closing line`() {
        val matches = ClaudeTaskParser.findTasksInComment("/** @claude document this class **/")
        assertEquals(listOf("document this class"), matches.map { it.text })
    }

    @Test
    fun `java block comments in plain text fallback`() {
        val source = "/* @claude first */\nint x;\n/**\n * @claude second\n */\n"
        val tasks = ClaudeTaskParser.findTasksInPlainText(source)
        assertEquals(listOf(1, 4), tasks.map { it.line })
        assertEquals(listOf("first", "second"), tasks.map { it.text })
    }

    // ---- SQL ------------------------------------------------------------------------------------

    @Test
    fun `sql double dash comment`() {
        val source = "SELECT *\nFROM users -- @claude add a WHERE clause that filters inactive users\nORDER BY id;\n"
        val tasks = ClaudeTaskParser.findTasksInPlainText(source)
        assertEquals(1, tasks.size)
        assertEquals(2, tasks[0].line)
        assertEquals("add a WHERE clause that filters inactive users", tasks[0].text)
    }

    @Test
    fun `sql comment via comment parser`() {
        val matches = ClaudeTaskParser.findTasksInComment("-- @claude add an index hint")
        assertEquals(listOf("add an index hint"), matches.map { it.text })
    }

    // ---- Other comment styles -------------------------------------------------------------------

    @Test
    fun `semicolon and xml comments`() {
        assertEquals(listOf("rename this label"), ClaudeTaskParser.findTasksInPlainText("; @claude rename this label").map { it.text })
        assertEquals(listOf("add a closing tag"), ClaudeTaskParser.findTasksInComment("<!-- @claude add a closing tag -->").map { it.text })
        assertEquals(listOf("handle the error"), ClaudeTaskParser.findTasksInComment("(* @claude handle the error *)").map { it.text })
    }

    @Test
    fun `windows line endings`() {
        val source = "// @claude first\r\nint a;\r\n# @claude second\r\n"
        val tasks = ClaudeTaskParser.findTasksInPlainText(source)
        assertEquals(listOf(1, 3), tasks.map { it.line })
        assertEquals(listOf("first", "second"), tasks.map { it.text })
    }

    // ---- Things that are not tasks --------------------------------------------------------------

    @Test
    fun `tag without text or glued to other words is ignored`() {
        assertTrue(ClaudeTaskParser.findTasksInComment("// @claude").isEmpty())
        assertTrue(ClaudeTaskParser.findTasksInComment("// @claude   ").isEmpty())
        assertTrue(ClaudeTaskParser.findTasksInComment("// @claudette is a name").isEmpty())
        assertTrue(ClaudeTaskParser.findTasksInComment("// write to support@claude.ai about it").isEmpty())
        assertTrue(ClaudeTaskParser.findTasksInComment("// see @claude-code docs").isEmpty())
        assertTrue(ClaudeTaskParser.findTasksInPlainText("// @claude").isEmpty())
        assertTrue(ClaudeTaskParser.findTasksInPlainText("// @claudeX do it").isEmpty())
    }

    @Test
    fun `fallback requires a comment marker before the tag`() {
        assertTrue(ClaudeTaskParser.findTasksInPlainText("val s = \"@claude not a task\"").isEmpty())
        assertTrue(ClaudeTaskParser.findTasksInPlainText("@claude at line start without marker").isEmpty())
        assertTrue(ClaudeTaskParser.findTasksInPlainText("//\n@claude text on the next line").isEmpty())
    }

    @Test
    fun `leading punctuation after the tag is dropped`() {
        assertEquals(listOf("do it"), ClaudeTaskParser.findTasksInComment("// @claude: do it").map { it.text })
        assertEquals(listOf("do it"), ClaudeTaskParser.findTasksInComment("// @claude - do it").map { it.text })
        assertEquals(listOf("-1 is the sentinel, document it"), ClaudeTaskParser.findTasksInComment("// @claude -1 is the sentinel, document it").map { it.text })
    }

    @Test
    fun `only one task per line`() {
        val matches = ClaudeTaskParser.findTasksInComment("// @claude do A and then @claude do B")
        assertEquals(1, matches.size)
        assertEquals("do A and then @claude do B", matches[0].text)
    }

    @Test
    fun `line number helper`() {
        assertEquals(1, ClaudeTaskParser.lineNumber("abc", 0))
        assertEquals(1, ClaudeTaskParser.lineNumber("abc\ndef", 3))
        assertEquals(2, ClaudeTaskParser.lineNumber("abc\ndef", 4))
        assertEquals(3, ClaudeTaskParser.lineNumber("a\n\nb", 3))
    }
}
