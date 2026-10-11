package ee.schimke.composeai.mcp

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Recompiles a module's sources so the classes a daemon loads match disk. A daemon's
 * `fileChanged({kind:"source"})` only swaps its classloader and compiles nothing; editors compile
 * first, but an agent calling `notify_file_changed` would otherwise get the old image. The MCP
 * server runs this before forwarding `fileChanged`.
 */
fun interface SourceCompiler {
  fun compile(projectRoot: File, modulePath: String, sources: List<File>): SourceCompileOutcome
}

sealed interface SourceCompileOutcome {
  /** What the compile did, when it ran one: see [CompileWork]. */
  val work: CompileWork?
    get() = null

  data class Ok(val durationMs: Long, override val work: CompileWork? = null) : SourceCompileOutcome

  /** The compile ran and failed, usually a compile error; [reason] is one line for the agent. */
  data class Failed(val reason: String, override val work: CompileWork? = null) :
    SourceCompileOutcome

  /** No compile could run at all (no Gradle wrapper, disabled, …). */
  data class Unavailable(val reason: String) : SourceCompileOutcome
}

/**
 * Stage-0 compile: `./gradlew <module>:composePreviewCompile`, the plugin's lifecycle task for the
 * save loop (Kotlin compile without discovery). For a project that doesn't apply the plugin, the
 * task only exists with the CLI's init script, which [InitScripts] locates; it is passed as the CLI
 * does (`--init-script <path>`, Isolated Projects off). Output is captured, never inherited: stdout
 * is the MCP transport.
 */
class GradleSourceCompiler(
  private val timeoutMs: Long = TimeUnit.MINUTES.toMillis(5),
  private val initScripts: InitScripts = InitScripts(),
  private val androidSdks: AndroidSdks = AndroidSdks(),
) : SourceCompiler {

  /** Per project root: whether its compile needs the CLI's init script. */
  private val needsInitScript = ConcurrentHashMap<String, Boolean>()

  override fun compile(
    projectRoot: File,
    modulePath: String,
    sources: List<File>,
  ): SourceCompileOutcome {
    val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    val wrapper = File(projectRoot, if (windows) "gradlew.bat" else "gradlew")
    if (!wrapper.isFile) {
      return SourceCompileOutcome.Unavailable("no Gradle wrapper at ${wrapper.path}")
    }
    val task = if (modulePath == ":" || modulePath.isBlank()) ":$TASK" else "$modulePath:$TASK"
    val command =
      if (windows) listOf("cmd", "/c", wrapper.absolutePath) else listOf(wrapper.absolutePath)
    val key = projectRoot.absolutePath
    val initScript = initScripts.forProject(projectRoot)
    val inject =
      initScript != null &&
        needsInitScript.computeIfAbsent(key) { !initScripts.projectAppliesPlugin(projectRoot) }
    val first = run(projectRoot, command, task, if (inject) initScript else null)
    if (
      first is GradleRun.Finished &&
        first.exitCode != 0 &&
        !inject &&
        initScript != null &&
        TASK_NOT_FOUND.containsMatchIn(first.output)
    ) {
      // The scan missed a build that does not apply the plugin; remember that and retry.
      needsInitScript[key] = true
      return outcome(task, run(projectRoot, command, task, initScript))
    }
    return outcome(task, first).let { result ->
      if (
        result is SourceCompileOutcome.Failed &&
          initScript == null &&
          first is GradleRun.Finished &&
          TASK_NOT_FOUND.containsMatchIn(first.output)
      ) {
        result.copy(
          reason =
            "${result.reason} (the project does not apply the Compose Preview plugin and no " +
              "compose-preview init script was found; run `compose-preview mcp install` once)"
        )
      } else result
    }
  }

  private sealed interface GradleRun {
    data class Finished(
      val exitCode: Int,
      val output: String,
      val durationMs: Long,
      val initScript: Boolean,
    ) : GradleRun

    data class TimedOut(val timeoutMs: Long) : GradleRun

    data class NotStarted(val reason: String) : GradleRun
  }

  private fun outcome(task: String, run: GradleRun): SourceCompileOutcome =
    when (run) {
      is GradleRun.NotStarted -> SourceCompileOutcome.Unavailable(run.reason)
      is GradleRun.TimedOut ->
        SourceCompileOutcome.Failed("$task timed out after ${run.timeoutMs}ms")
      is GradleRun.Finished -> {
        val work =
          CompileWork(
            task = task,
            ms = run.durationMs,
            initScript = run.initScript,
            tasks = CompileWork.parseTaskLines(run.output),
          )
        if (run.exitCode == 0) SourceCompileOutcome.Ok(run.durationMs, work)
        else
          SourceCompileOutcome.Failed("$task failed: ${summarizeGradleFailure(run.output)}", work)
      }
    }

  private fun run(
    projectRoot: File,
    command: List<String>,
    task: String,
    initScript: File?,
  ): GradleRun {
    val injection =
      initScript?.let { listOf("--init-script", it.absolutePath) + ISOLATED_PROJECTS_OFF }.orEmpty()
    val startedAt = System.nanoTime()
    val process = runCatching {
      // Not `--quiet`: [CompileWork] reads the plain console's `> Task :x` lines.
      ProcessBuilder(command + injection + listOf("--console=plain", task))
        .directory(projectRoot)
        .redirectErrorStream(true)
        .apply {
          // A worktree has no untracked local.properties, and a server an app launched often has
          // no ANDROID_HOME: without one of them AGP cannot configure the build at all.
          androidSdks.forBuild(projectRoot)?.let { environment()["ANDROID_HOME"] = it.absolutePath }
        }
        .start()
    }
      .getOrElse {
        return GradleRun.NotStarted("could not start ${command.last()}: ${it.message}")
      }
    process.outputStream.close()
    val output = StringBuilder()
    val reader =
      Thread(
          {
            runCatching {
              process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                  synchronized(output) {
                    if (output.length < MAX_CAPTURED_CHARS) output.appendLine(line)
                  }
                }
              }
            }
          },
          "compose-preview-mcp-gradle-compile",
        )
        .apply {
          isDaemon = true
          start()
        }
    if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
      process.destroyForcibly()
      return GradleRun.TimedOut(timeoutMs)
    }
    reader.join(2_000)
    return GradleRun.Finished(
      exitCode = process.exitValue(),
      output = synchronized(output) { output.toString() },
      durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
      initScript = initScript != null,
    )
  }

  companion object {
    const val TASK = "composePreviewCompile"
    private const val MAX_CAPTURED_CHARS = 64 * 1024
    private val TASK_NOT_FOUND = Regex("""task '$TASK' not found|Task '[^']*$TASK' not found""")

    /**
     * Mirrors the CLI's `ISOLATED_PROJECTS_OFF_ARGS`: the injected script cannot run under Isolated
     * Projects, and both spellings go out because Gradle 9.7 renamed the property.
     */
    private val ISOLATED_PROJECTS_OFF =
      listOf("-Dorg.gradle.unsafe.isolated-projects=false", "-Dorg.gradle.isolated-projects=false")

    /**
     * The first Kotlin `e:` diagnostic, else Gradle's "What went wrong" line plus its root cause
     * (the deepest `> …` line under it: "SDK location not found" hides behind "Could not determine
     * the dependencies of task"), else the tail.
     */
    internal fun summarizeGradleFailure(output: String): String {
      val lines = output.lines().map(String::trim).filter(String::isNotEmpty)
      lines
        .firstOrNull { it.startsWith("e: ") }
        ?.let {
          return it.removePrefix("e: ").take(300)
        }
      val wrong = lines.indexOfFirst { it.startsWith("* What went wrong") }
      if (wrong >= 0 && wrong + 1 < lines.size) {
        val headline = lines[wrong + 1]
        val cause =
          lines
            .drop(wrong + 2)
            .takeWhile { !it.startsWith("* ") && !it.startsWith("BUILD ") }
            .lastOrNull { it.startsWith(">") }
            ?.trimStart('>', ' ')
            ?.takeIf { it.isNotEmpty() && it != headline }
        return if (cause == null) headline.take(300)
        else "${headline.take(200)} Cause: ${cause.take(200)}"
      }
      return lines.lastOrNull()?.take(300) ?: "exit code non-zero"
    }
  }
}

/**
 * Finds the compose-preview CLI's plugin init script (`apply-compose-ai-preview.init.gradle.kts`)
 * where the CLI materialises it: `$XDG_CACHE_HOME/composeai/init/<version>/`, else
 * `~/.cache/composeai/init/<version>/`, newest version first. `COMPOSE_PREVIEW_INIT_SCRIPT` names
 * one explicitly. The CLI's opt-outs apply here too: `COMPOSE_PREVIEW_NO_AUTO_INJECT=1`, and a
 * build whose settings `includeBuild("gradle-plugin")`.
 */
class InitScripts(
  private val environment: Map<String, String> = System.getenv(),
  private val userHome: File = File(System.getProperty("user.home") ?: "."),
  private val cli: CliInitScript? = CliInitScript.subprocess(environment, userHome),
) {

  /** Builds the CLI was already asked to write a script for; asked at most once per build. */
  private val cliAsked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  fun forProject(projectRoot: File): File? {
    if (environment["COMPOSE_PREVIEW_NO_AUTO_INJECT"] == "1") return null
    if (settingsText(projectRoot).contains(INCLUDED_PLUGIN_BUILD)) return null
    environment["COMPOSE_PREVIEW_INIT_SCRIPT"]
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return File(it).takeIf(File::isFile)
      }
    return cached() ?: fromCli(projectRoot)
  }

  private fun cached(): File? {
    val cacheRoot =
      environment["XDG_CACHE_HOME"]?.takeIf { it.isNotBlank() }?.let { File(it, "composeai/init") }
        ?: File(userHome, ".cache/composeai/init")
    return cacheRoot
      .listFiles(File::isDirectory)
      .orEmpty()
      .sortedWith(compareByDescending<File, List<Int>>(VERSION_ORDER) { versionKey(it.name) })
      .map { File(it, FILE_NAME) }
      .firstOrNull(File::isFile)
  }

  /**
   * Nothing cached yet (e.g. `compose-preview mcp install` never ran): ask the CLI to write the
   * script (`compose-preview init-script --path`, side-effect free) rather than failing the first
   * render.
   */
  private fun fromCli(projectRoot: File): File? {
    val cli = cli ?: return null
    if (projectAppliesPlugin(projectRoot)) return null
    if (!cliAsked.add(projectRoot.absolutePath)) return null
    return runCatching { cli.materialize(projectRoot) }.getOrNull()?.takeIf(File::isFile)
  }

  /**
   * Whether the build applies `ee.schimke.composeai.preview` itself: in its settings, root or
   * module build scripts, version catalog, or `build-logic` / `buildSrc` convention plugins. A
   * cheap text scan; a miss only costs one retry, see [GradleSourceCompiler].
   */
  fun projectAppliesPlugin(projectRoot: File): Boolean {
    val candidates = buildList {
      addAll(listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle"))
      add("gradle/libs.versions.toml")
      projectRoot.listFiles(File::isDirectory).orEmpty().forEach { module ->
        add("${module.name}/build.gradle.kts")
        add("${module.name}/build.gradle")
      }
    }
    val files =
      candidates.map { File(projectRoot, it) }.filter(File::isFile) +
        listOf("build-logic", "buildSrc").flatMap { dir ->
          File(projectRoot, dir)
            .takeIf(File::isDirectory)
            ?.walkTopDown()
            ?.maxDepth(8)
            ?.filter { it.isFile && (it.extension == "kts" || it.extension == "kt") }
            ?.take(200)
            ?.toList()
            .orEmpty()
        }
    return files.any { runCatching { it.readText() }.getOrDefault("").contains(PLUGIN_ID) }
  }

  private fun settingsText(projectRoot: File): String =
    listOf("settings.gradle.kts", "settings.gradle")
      .map { File(projectRoot, it) }
      .firstOrNull(File::isFile)
      ?.let { runCatching { it.readText() }.getOrNull() }
      .orEmpty()

  companion object {
    const val FILE_NAME = "apply-compose-ai-preview.init.gradle.kts"
    private const val PLUGIN_ID = "ee.schimke.composeai.preview"
    private val INCLUDED_PLUGIN_BUILD = Regex("""includeBuild\s*\(\s*["']gradle-plugin["']\s*\)""")

    private fun versionKey(name: String): List<Int> =
      name.split('.', '-').map { it.toIntOrNull() ?: -1 }

    private val VERSION_ORDER =
      Comparator<List<Int>> { a, b ->
        (0 until maxOf(a.size, b.size))
          .map { (a.getOrElse(it) { 0 }).compareTo(b.getOrElse(it) { 0 }) }
          .firstOrNull { it != 0 } ?: 0
      }
  }
}

/**
 * Asks the CLI to write its init script and returns the path (`compose-preview init-script
 * --path`); a seam so tests need no CLI.
 */
fun interface CliInitScript {

  fun materialize(projectRoot: File): File?

  companion object {
    private const val CLI_NAME = "compose-preview"

    /**
     * The launcher the CLI names in `COMPOSE_PREVIEW_CLI`, else `compose-preview` on
     * [environment]'s `PATH`, else the installer's `~/.local/bin/compose-preview`. Null when none
     * exists.
     */
    fun locate(environment: Map<String, String>, userHome: File): File? {
      environment["COMPOSE_PREVIEW_CLI"]
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile && it.canExecute() }
        ?.let {
          return it
        }
      val names =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows"))
          listOf("$CLI_NAME.bat", "$CLI_NAME.cmd", CLI_NAME)
        else listOf(CLI_NAME)
      val onPath =
        environment["PATH"]
          .orEmpty()
          .split(File.pathSeparatorChar)
          .filter { it.isNotBlank() }
          .flatMap { dir -> names.map { File(dir, it) } }
      return (onPath + File(userHome, ".local/bin/$CLI_NAME")).firstOrNull {
        it.isFile && it.canExecute()
      }
    }

    /** Runs the located CLI with captured output: this process's stdout is the MCP transport. */
    fun subprocess(
      environment: Map<String, String>,
      userHome: File,
      timeoutMs: Long = TimeUnit.MINUTES.toMillis(2),
    ): CliInitScript = CliInitScript { projectRoot ->
      val launcher = locate(environment, userHome) ?: return@CliInitScript null
      val process =
        ProcessBuilder(launcher.absolutePath, "init-script", "--path")
          .directory(projectRoot)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start()
      process.outputStream.close()
      val output = StringBuilder()
      val reader =
        Thread(
            { runCatching { output.append(process.inputStream.bufferedReader().readText()) } },
            "compose-preview-mcp-init-script",
          )
          .apply {
            isDaemon = true
            start()
          }
      if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        return@CliInitScript null
      }
      reader.join(2_000)
      if (process.exitValue() != 0) return@CliInitScript null
      output
        .lines()
        .map { it.trim() }
        .lastOrNull { it.endsWith(InitScripts.FILE_NAME) }
        ?.let { File(it) }
    }
  }
}
