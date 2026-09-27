package ee.schimke.composeai.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What one recompile did (issue #1174): the Gradle task it asked for, how long it took, whether the
 * compose-preview CLI's init script was injected, and every task Gradle scheduled, in Gradle's own
 * plain-console spelling (`:app:compileKotlin`, `:app:processResources NO-SOURCE`).
 *
 * The task list is what makes wasted work visible structurally rather than as a slow timing: a
 * recompile that schedules `assemble*`, `lint*` or `test*` is doing far more than the save loop
 * needs, however fast the machine that ran it was.
 */
data class CompileWork(
  val task: String,
  val ms: Long,
  val initScript: Boolean,
  val tasks: List<String>,
  /**
   * What asked for the recompile: `notify` (`notify_file_changed`) or `detected` (a render found a
   * changed source itself). Null when the compiler ran outside the server's edit loop.
   */
  val trigger: String? = null,
) {

  /** Task paths without Gradle's outcome suffix. */
  val taskPaths: List<String>
    get() = tasks.map { it.substringBefore(' ') }

  /** Tasks that did work: neither `UP-TO-DATE`, `NO-SOURCE` nor `SKIPPED`. */
  val executed: List<String>
    get() =
      tasks
        .filter { line -> NO_WORK.none { line.endsWith(" $it") } }
        .map { it.substringBefore(' ') }

  /**
   * The scheduled tasks a save loop has no business running: any task named like packaging, linting
   * or testing, anywhere; and, when [allowedModules] is given, any task outside those modules. A
   * recompile of `:app` may compile `:app`'s sources and their generated inputs, nothing else.
   */
  fun disallowedTasks(allowedModules: Set<String>? = null): List<String> =
    taskPaths.filter { path ->
      val module = path.substringBeforeLast(':').ifEmpty { ":" }
      val name = path.substringAfterLast(':')
      FORBIDDEN_TASK.containsMatchIn(name) || (allowedModules != null && module !in allowedModules)
    }

  fun toJson(): JsonObject = buildJsonObject {
    put("task", task)
    put("ms", ms)
    put("initScript", initScript)
    trigger?.let { put("trigger", it) }
    putJsonArray("tasks") { tasks.forEach { add(JsonPrimitive(it)) } }
  }

  companion object {
    private val TASK_LINE = Regex("""^> Task (\S+)(?: ([A-Z-]+))?\s*$""")
    private val NO_WORK = listOf("UP-TO-DATE", "NO-SOURCE", "SKIPPED")

    /**
     * Name patterns that never belong in an edit→render recompile: packaging, installing,
     * publishing, linting and testing. Matched against the task name, not the path.
     */
    val FORBIDDEN_TASK =
      Regex(
        "(?i)^(assemble|bundle|package|install|uninstall|connected|publish|sign|minify|check$|build$)" +
          "|lint|test"
      )

    /**
     * The `> Task :x[ OUTCOME]` lines of a `--console=plain` Gradle run, in order, deduplicated.
     */
    fun parseTaskLines(output: String): List<String> =
      output
        .lineSequence()
        .mapNotNull { TASK_LINE.find(it.trimEnd()) }
        .map { m ->
          m.groupValues[1] + m.groupValues[2].takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()
        }
        .distinctBy { it.substringBefore(' ') }
        .toList()
  }
}

/**
 * The work record for one edit→render cycle, attached to the render's result as `_meta.work` and
 * logged: the recompile the edit caused (null when nothing was compiled since the last render of
 * the module, which is what an unchanged render must show), how long the daemon took to render, and
 * the daemon's own per-render trace when it sends one.
 */
data class EditCycleWork(
  val compile: CompileWork?,
  val renderMs: Long,
  val daemonTrace: JsonElement? = null,
  /** The change-detection pass this render ran before compiling; see [SourceTree]. */
  val scan: SourceTree.Refresh? = null,
) {
  fun toJson(): JsonObject = buildJsonObject {
    compile?.let { put("compile", it.toJson()) }
    put("renderMs", renderMs)
    scan?.let { scan ->
      put(
        "scan",
        buildJsonObject {
          put("changed", scan.changed.size)
          put("statted", scan.statted)
          put("listed", scan.listed)
          put("ms", scan.ms)
          put("complete", scan.complete)
          put("initial", scan.initial)
        },
      )
    }
    // TODO(#1181): the daemon does not send a per-render trace of the post-capture processors and
    //  data kinds it ran (e.g. `compose/figma-svg`) yet. `renderFinished.params.workTrace` is the
    //  provisional name; until a daemon release sends it this stays absent.
    daemonTrace?.let { put("daemonTrace", it) }
  }
}
