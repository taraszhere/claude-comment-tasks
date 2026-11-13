# Claude Comment Tasks (IntelliJ plugin)

Write a task for [Claude Code](https://code.claude.com/docs) as a comment in any source file:

```scala
// @claude add a method that calculates the sum of the numbers in a Set
```

```python
# @claude add a function that removes duplicates from a list
```

Press **⌘.** (macOS) / **Ctrl+.** (Windows, Linux), click the orange gutter icon, use the editor
context menu, or (optionally) just save the file. The plugin runs the Claude Code CLI (`claude`) on
the file, Claude does each task and removes its `@claude` comment, and the editor shows the changed file.

Works in every language IntelliJ supports and in every comment style (`//`, `#`, `--`, `;`,
`/* */`, `/** */`, `<!-- -->`, …), because it looks at the PSI comments. Plain text files use a regex
fallback.

## Requirements

| What | Version |
|------|---------|
| IntelliJ IDEA (or another JetBrains IDE) | 2026.2 or newer (build 262+). Compiled against 2026.3. |
| Claude Code CLI | `claude` installed and logged in (`claude --version` works in your terminal). |
| For building | JDK 25 (downloaded automatically by Gradle through the Foojay toolchain resolver), internet access. |

## Build the plugin

```bash
./gradlew buildPlugin
```

The distributable zip is written to `build/distributions/claude-comment-tasks-<version>.zip`.

Other useful tasks:

```bash
./gradlew test            # unit tests (parser, prompt, JSON) and platform tests (PSI, highlighting, gutter)
./gradlew verifyPlugin    # IntelliJ Plugin Verifier against IDEA 2026.2 and 2026.3
./gradlew check           # tests + plugin configuration checks
```

The first build downloads IntelliJ IDEA 2026.3 (about 1.5 GB) and JDK 25; later builds are fast.

## Test it in a sandbox IDE

```bash
./gradlew runIde
```

This starts a separate IntelliJ IDEA 2026.3 with the plugin installed (settings and plugins live in
`build/idea-sandbox`, your real IDE is untouched). Then:

1. Open or create any project and a source file.
2. Add a comment such as `// @claude add a toString method` — it is highlighted in orange and a
   gutter icon appears.
3. Press **⌘.** / **Ctrl+.**, or click the gutter icon, or right-click → *Run @claude Tasks in File*.
4. A background task with a Cancel button runs while Claude works. When it finishes, the file is
   reloaded from disk and a notification shows the result. The full CLI output is in the
   **Claude Tasks** tool window (bottom) and in `idea.log`.

## Install the zip in your IDE ("Install Plugin from Disk")

1. Run `./gradlew buildPlugin`.
2. In IntelliJ IDEA open **Settings | Plugins**.
3. Click the gear icon (⚙) next to the tabs and choose **Install Plugin from Disk…**.
4. Select `build/distributions/claude-comment-tasks-<version>.zip` and confirm.
5. Restart the IDE when asked.

## Settings

**Settings | Tools | Claude Comment Tasks**

| Setting | Default | Meaning |
|---------|---------|---------|
| Path to claude | empty | Absolute path of the `claude` executable. When empty the plugin looks on the IDE's PATH, then asks your login shell (`$SHELL -lc 'command -v claude'`), then checks common install locations such as `~/.local/bin/claude`. |
| Allowed tools | `Read,Edit,Grep,Glob` | Passed as `--allowedTools`. |
| Timeout (seconds) | `300` | The process is stopped (SIGINT, then SIGKILL) when a run takes longer. |
| Extra prompt text | empty | Appended to every prompt, e.g. coding conventions. |
| Run @claude tasks when a file is saved | off | Runs after an explicit save (Save All, ⌘S / Ctrl+S) completes. Autosave on focus loss or idle does not trigger it. |
| Highlight @claude comments | on | Toggles the editor coloring. |

**Settings | Editor | Color Scheme | Claude Comment Tasks** lets you change the color of the task
text and the bold `@claude` tag. Defaults: orange `#C2410C` in light schemes, amber `#F59E0B` in
dark schemes.

**Settings | Editor | General | Gutter Icons** → *@claude tasks* toggles the gutter icon.

### The shortcut

**⌘.** / **Ctrl+.** is also the default shortcut of *Fold Selection* (code folding). IntelliJ
lets both actions share the key. If the wrong one runs for you, give one of them
a different key, use **Settings | Keymap** and search for "Run @claude Tasks in File".

## How it works

1. **Find tasks**: all `PsiComment` elements containing `@claude` are collected (strings and code are
   never matched). Files without PSI support (plain text) use the regex
   `(//|#|--|;|/\*+|\*)\s*@claude\b\s*(.+?)\s*(\*/)?$` line by line.
2. **Save** the document, then start a cancellable `Task.Backgroundable`.
3. **Run** in the project base directory:
   ```
   claude -p "<prompt>" --allowedTools "Read,Edit,Grep,Glob" --output-format json
   ```
   The prompt contains the absolute file path, the language, every task with its line number, and
   the instruction *"Do each task. Change only what the task needs. Remove each @claude comment when
   its task is done."* plus your extra prompt text.
4. **Afterwards** the file is refreshed in the VFS and the document reloaded, a notification shows the
   result (or the CLI error), and the complete output is written to the *Claude Tasks* tool window
   and `idea.log` (Help | Show Log in Finder/Explorer).

Loop protection: a file with a run in progress is never started a second time, and saves of that file
are ignored while Claude is working on it. A sticky notification reminds you not to edit the file.

## Troubleshooting

- **"Claude Code CLI not found"**: IntelliJ started from the Dock does not always see the PATH of your
  terminal. Set the full path in the settings (`which claude` in a terminal prints it).
- **Nothing happens on save**: enable *Run @claude tasks when a file is saved* in the settings and use
  an explicit save (⌘S / Ctrl+S). Autosave does not trigger runs.
- **The run fails immediately**: open the *Claude Tasks* tool window; the CLI output usually names the
  cause (not logged in, no network, permission denied for a tool). Run `claude -p "hi"` in a terminal
  to check the CLI itself.
- **Claude did not remove the comment**: run again or remove it by hand; nothing re-runs automatically
  unless you save with run-on-save enabled.

## Project layout

```
build.gradle.kts, settings.gradle.kts, gradle.properties   Gradle (IntelliJ Platform Gradle Plugin 2.x, Kotlin)
src/main/kotlin/com/claudecommenttasks/
  parser/      ClaudeTaskParser (pure Kotlin: comment text + regex fallback), ClaudeTask
  psi/         ClaudeTaskFinder (PSI comments first, text fallback)
  highlight/   Annotator, LineMarkerProvider (gutter icon), TextAttributesKeys, ColorSettingsPage
  actions/     RunClaudeTasksAction (⌘. / Ctrl+., editor popup, Tools menu)
  run/         ClaudeTaskRunner (in-progress set, background task), ClaudeCliRunner (process),
               ClaudeCliLocator, ClaudePromptBuilder, ClaudeCliResult (JSON), notifications, refresh
  save/        ClaudeSaveListener (FileDocumentManagerListener, run on save)
  settings/    ClaudeSettings (PersistentStateComponent), ClaudeSettingsConfigurable
  toolwindow/  "Claude Tasks" console tool window
src/main/resources/META-INF/plugin.xml, colorSchemes/, icons/, messages/
src/test/kotlin/   parser, prompt and JSON unit tests; platform tests for PSI, highlighting, gutter
```
