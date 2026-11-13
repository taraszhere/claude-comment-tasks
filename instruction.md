Build an IntelliJ IDEA plugin in Kotlin. Name: "Claude Comment Tasks".

## Goal
I write a comment in a source file, for example:
  Scala:  // @claude add a method that calculates the sum of the numbers in a Set
  Python: # @claude add a function that removes duplicates from a list
When I press a shortcut (and, if I turn it on, when I save the file),
the plugin runs the Claude Code CLI (`claude`) on that file. Claude does
the task and removes the @claude comment. IntelliJ then shows the
changed file.

## Tech stack
- Kotlin, Gradle (Kotlin DSL), IntelliJ Platform Gradle Plugin 2.x
  (`org.jetbrains.intellij.platform`).
- Target the latest stable IntelliJ IDEA. Look up the current versions
  first. Do not use versions from memory.
- Make the plugin work in all languages and all comment styles:
  `//`, `#`, `--`, `;`, `/* ... */`, `/** ... */`, etc.

## Features
1. Comment parser
   - Find @claude tasks inside comments only. Do not find "@claude"
     in strings or in code.
   - Use the PSI first: find all PsiComment elements that contain
     `@claude`. This works for each language that IntelliJ supports.
   - If the file has no PSI support (plain text), use a text fallback
     with this regex:
     `(//|#|--|;|/\*+|\*)\s*@claude\b\s*(.+?)\s*(\*/)?$`
   - Keep the line number and the task text of each match.
   - Put the text logic in a pure Kotlin class with unit tests.
     Include tests for Scala, Python, Java block comments, and SQL.

2. Highlight @claude comments
   - In the editor, show each @claude comment in its own color, in
     all languages.
   - Recommended method: use a language-independent Annotator or
     HighlightVisitor on PsiComment elements. Highlight the full
     comment line from "@claude" to the end of the comment. Make
     the "@claude" word bold.
   - Default color: a clear orange or purple. It must be easy to see
     in the light theme and in the dark theme.
   - Add a ColorSettingsPage (Settings > Editor > Color Scheme >
     Claude Comment Tasks) so that I can change the color. Use
     TextAttributesKey with a default fallback.
   - Also add a gutter icon on each @claude line. When I click the
     icon, it runs the task (same as the action in section 3).
   - Do not use the global TODO patterns for this. Do not change my
     TODO settings.

3. Action with a shortcut
   - Action name: "Run @claude Tasks in File".
   - Default shortcut: Cmd+. (macOS), Ctrl+. (others).
   - Also add it to the editor right-click menu.
   - If the file has no @claude comments, show a short notification
     and do nothing.

4. Optional run on save
   - Use a FileDocumentManagerListener.
   - Off by default. A setting turns it on.
   - Do not block the save. Start the run after the save completes.

5. Run the CLI
   - Save the document first.
   - Command:
     claude -p "<prompt>" --allowedTools "Read,Edit,Grep,Glob" --output-format json
   - Working directory: the project base path.
   - The prompt must include: the absolute file path, the language,
     each task with its line number, and this instruction: "Do each
     task. Change only what the task needs. Remove each @claude
     comment when its task is done."
   - Use GeneralCommandLine / OSProcessHandler.
   - Run it in a Task.Backgroundable with a progress bar and a working
     Cancel button. Cancel must stop the process.
   - Add a timeout (setting, default 5 minutes).

6. Find the `claude` binary
   - IntelliJ on macOS does not get the PATH from the user's shell.
     Use the path from the settings first. If it is empty, get the
     PATH from a login shell (`$SHELL -lc 'command -v claude'`).
     If you cannot find it, show an error notification with a link
     to the settings page.

7. Safety and loop prevention
   - Keep a set of files that have a run in progress. Do not start a
     second run on the same file.
   - Ignore saves of a file while its run is in progress.
   - While Claude runs, show a notification that tells me not to edit
     the file.

8. After the run
   - Refresh the file from disk (VFS refresh + reload the document) so
     the editor shows the changes.
   - Show a notification with the result: success, or the error text
     from the CLI.
   - Write the full CLI output to a "Claude Tasks" tool window or to
     idea.log, so that I can debug problems.

9. Settings page (Settings > Tools > Claude Comment Tasks)
   - Path to `claude` (optional)
   - Allowed tools (default: Read,Edit,Grep,Glob)
   - Run on save (on/off)
   - Timeout in seconds
   - Extra prompt text added to each run (optional)
   - Highlighting on/off

## How to work
- First, show me a short plan and the file structure. Then build it.
- Use the current, non-deprecated IntelliJ Platform APIs. Do all file
  and PSI access on the correct threads (read/write actions, EDT).
- Run the unit tests and `./gradlew buildPlugin`. Fix all errors.
- Write a README with:
  - how to build the plugin
  - how to test it with `./gradlew runIde`
  - how to install the zip with "Install Plugin from Disk"
