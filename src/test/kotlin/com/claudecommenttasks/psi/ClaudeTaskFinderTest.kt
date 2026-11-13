package com.claudecommenttasks.psi

import com.claudecommenttasks.highlight.ClaudeTaskColors
import com.claudecommenttasks.settings.ClaudeSettings
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Platform tests: PSI-based detection (Java), plain text fallback, highlighting and gutter icons. */
class ClaudeTaskFinderTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            ClaudeSettings.getInstance().highlightingEnabled = true
        } finally {
            super.tearDown()
        }
    }

    fun testJavaCommentsAreFoundButStringsAndCodeAreNot() {
        val file = myFixture.configureByText(
            "Demo.java",
            """
            // @claude add a method that returns the size of the set
            /* @claude block comment task */
            /**
             * Javadoc.
             * @claude document this class
             */
            public class Demo {
                String s = "@claude inside a string is not a task";
                String t = "// @claude also not a task";
                // plain comment without a task
                int x = 1; // trailing @claude trailing task
            }
            """.trimIndent(),
        )
        assertFalse(ClaudeTaskFinder.usesTextFallback(file))
        val tasks = ClaudeTaskFinder.findTasks(file)
        assertEquals(listOf(1, 2, 5, 11), tasks.map { it.line })
        assertEquals(
            listOf(
                "add a method that returns the size of the set",
                "block comment task",
                "document this class",
                "trailing task",
            ),
            tasks.map { it.text },
        )
        val text = file.text
        assertEquals("@claude block comment task", text.substring(tasks[1].startOffset, tasks[1].endOffset))
        assertEquals("@claude document this class", text.substring(tasks[2].startOffset, tasks[2].endOffset))
    }

    fun testJavaHighlightingAndGutterIcons() {
        myFixture.configureByText(
            "Demo.java",
            "// @claude first task\nclass Demo { String s = \"@claude no\"; }\n/**\n * @claude second task\n * @claude third task\n */\nclass Other {}\n",
        )
        val infos = myFixture.doHighlighting()
        val taskInfos = infos.filter { it.forcedTextAttributesKey == ClaudeTaskColors.TASK }
        assertEquals(3, taskInfos.size)
        assertEquals(TextRange(3, 21), TextRange(taskInfos[0].startOffset, taskInfos[0].endOffset))
        val tagInfos = infos.filter { it.forcedTextAttributesKey == ClaudeTaskColors.TAG }
        assertEquals(3, tagInfos.size)
        assertEquals(TextRange(3, 10), TextRange(tagInfos[0].startOffset, tagInfos[0].endOffset))

        val gutters = myFixture.findAllGutters().filter { it.tooltipText?.contains("@claude") == true }
        assertEquals(3, gutters.size)
    }

    fun testPlainTextFallback() {
        val file = myFixture.configureByText(
            "notes.txt",
            "# @claude first task\nsome text with @claude in the middle\n-- @claude second task\n",
        )
        assertTrue(ClaudeTaskFinder.usesTextFallback(file))
        val tasks = ClaudeTaskFinder.findTasks(file)
        assertEquals(listOf(1, 3), tasks.map { it.line })
        assertEquals(listOf("first task", "second task"), tasks.map { it.text })

        val infos = myFixture.doHighlighting()
        assertEquals(2, infos.count { it.forcedTextAttributesKey == ClaudeTaskColors.TASK })
        val gutters = myFixture.findAllGutters().filter { it.tooltipText?.contains("@claude") == true }
        assertEquals(2, gutters.size)
    }

    fun testHighlightingCanBeDisabledInSettings() {
        ClaudeSettings.getInstance().highlightingEnabled = false
        myFixture.configureByText("Demo.java", "// @claude a task\nclass Demo {}\n")
        val infos = myFixture.doHighlighting()
        assertEquals(0, infos.count { it.forcedTextAttributesKey == ClaudeTaskColors.TASK })
        // Gutter icons do not depend on the highlighting setting.
        assertEquals(1, myFixture.findAllGutters().count { it.tooltipText?.contains("@claude") == true })
    }
}
