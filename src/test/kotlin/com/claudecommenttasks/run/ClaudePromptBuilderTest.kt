package com.claudecommenttasks.run

import com.claudecommenttasks.parser.ClaudeTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudePromptBuilderTest {

    private val tasks = listOf(
        ClaudeTask(line = 3, text = "add a function that removes duplicates from a list", startOffset = 10, endOffset = 70),
        ClaudeTask(line = 12, text = "implement main", startOffset = 200, endOffset = 221),
    )

    @Test
    fun `prompt contains path, language, numbered tasks and the instruction`() {
        val prompt = ClaudePromptBuilder.build("/work/app/util.py", "Python", tasks)
        assertTrue(prompt.contains("/work/app/util.py"))
        assertTrue(prompt.contains("Language: Python"))
        assertTrue(prompt.contains("- Line 3: add a function that removes duplicates from a list"))
        assertTrue(prompt.contains("- Line 12: implement main"))
        assertTrue(prompt.contains(ClaudePromptBuilder.INSTRUCTION))
        assertEquals(
            "Do each task. Change only what the task needs. Remove each @claude comment when its task is done.",
            ClaudePromptBuilder.INSTRUCTION,
        )
    }

    @Test
    fun `extra prompt text is appended only when present`() {
        val without = ClaudePromptBuilder.build("/f.scala", "Scala", tasks, "")
        assertFalse(without.contains("Use tabs"))
        val with = ClaudePromptBuilder.build("/f.scala", "Scala", tasks, "  Use tabs for indentation.  ")
        assertTrue(with.endsWith("Use tabs for indentation."))
    }
}
