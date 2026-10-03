package ee.schimke.composeai.mcp

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Prepares a registered build that `compose-preview mcp install` never ran in: none of its modules
 * has a daemon launch descriptor (`build/compose-previews/daemon-launch.json`), so no daemon can
 * start and every preview lookup came back empty ("no preview matches").
 *
 * On first use it runs `./gradlew composePreviewDiscover composePreviewDaemonStart`, with the
 * compose-preview CLI's init script when the build does not apply the plugin itself: the same
 * [InitScripts] lookup (`COMPOSE_PREVIEW_INIT_SCRIPT`, else `~/.cache/composeai/init/<v>/…`) the
 * recompile uses. Until that succeeds the project is reported as "project not prepared: <reason>".
 */
class ProjectBootstrap(
  private val initScripts: InitScripts = InitScripts(),
  private val runner: GradleTaskRunner = GradleTaskRunner.subprocess(),
) {

  sealed interface Outcome {
    /** Descriptors exist (now or already); [ran] says whether this call ran Gradle. */
    data class Ready(val ran: Boolean, val durationMs: Long = 0) : Outcome

    data class NotPrepared(val reason: String) : Outcome
  }

  private val locks = ConcurrentHashMap<String, Any>()

  /** Whether [projectRoot] has at least one module with a daemon launch descriptor. */
  fun isPrepared(projectRoot: File): Boolean = runCatching {
    DescriptorProvider.indexDescriptorsByModulePath(projectRoot).isNotEmpty()
  }
    .getOrDefault(false)

  /**
   * Makes [projectRoot] renderable, running the bootstrap when it is not. Concurrent calls for one
   * build share one Gradle run. [progress] gets one line per step and per Gradle task.
   */
  fun ensurePrepared(projectRoot: File, progress: (String) -> Unit = {}): Outcome {
    if (isPrepared(projectRoot)) return Outcome.Ready(ran = false)
    synchronized(locks.computeIfAbsent(projectRoot.absolutePath) { Any() }) {
      if (isPrepared(projectRoot)) return Outcome.Ready(ran = false)
      val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
      val wrapper = File(projectRoot, if (windows) "gradlew.bat" else "gradlew")
      if (!wrapper.isFile) {
        return Outcome.NotPrepared(
          "no Gradle wrapper at ${wrapper.path}, so compose-preview cannot run its discovery " +
            "task there; run `compose-preview mcp install` in ${projectRoot.path}"
        )
      }
      val initScript =
        initScripts.forProject(projectRoot)?.takeIf {
          !initScripts.projectAppliesPlugin(projectRoot)
        }
      val arguments = buildList {
        if (initScript != null) {
          add("--init-script")
          add(initScript.absolutePath)
          addAll(ISOLATED_PROJECTS_OFF)
        }
        add("--console=plain")
        add("--continue")
        addAll(TASKS)
      }
      progress(
        "preparing ${projectRoot.name}: ./gradlew ${TASKS.joinToString(" ")}" +
          if (initScript != null) " (with the compose-preview init script)" else ""
      )
      val started = System.nanoTime()
      val result =
        runner.run(projectRoot, wrapper, arguments) { line ->
          if (line.startsWith("> Task ")) progress(line)
        }
      val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
      if (isPrepared(projectRoot)) {
        progress("prepared ${projectRoot.name} in ${durationMs}ms")
        return Outcome.Ready(ran = true, durationMs = durationMs)
      }
      val why =
        when {
          result.timedOut -> "the bootstrap timed out after ${durationMs}ms"
          result.exitCode != 0 ->
            "./gradlew ${TASKS.joinToString(" ")} failed: " +
              GradleSourceCompiler.summarizeGradleFailure(result.output)
          else -> "./gradlew ${TASKS.joinToString(" ")} wrote no daemon launch descriptor"
        }
      val hint =
        if (initScript == null && !initScripts.projectAppliesPlugin(projectRoot)) {
          " (the build does not apply the Compose Preview plugin and no compose-preview init " +
            "script was found; run `compose-preview mcp install` once)"
        } else ""
      return Outcome.NotPrepared(why + hint)
    }
  }

  /**
   * Reruns `composePreviewDiscover` in a build that is already prepared, so previews declared since
   * its daemons started reach `previews.json`. The daemon's own incremental discovery missed a
   * newly added file (yschimke/compose-ag-plugin#64), while this Gradle task, the one
   * `compose-preview show` runs, finds it. Returns null when the build has no Gradle wrapper.
   */
  fun rediscover(projectRoot: File, progress: (String) -> Unit = {}): GradleTaskRunner.Result? {
    val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    val wrapper = File(projectRoot, if (windows) "gradlew.bat" else "gradlew")
    if (!wrapper.isFile) return null
    synchronized(locks.computeIfAbsent(projectRoot.absolutePath) { Any() }) {
      val initScript =
        initScripts.forProject(projectRoot)?.takeIf {
          !initScripts.projectAppliesPlugin(projectRoot)
        }
      val arguments = buildList {
        if (initScript != null) {
          add("--init-script")
          add(initScript.absolutePath)
          addAll(ISOLATED_PROJECTS_OFF)
        }
        add("--console=plain")
        add("--continue")
        add(DISCOVER_TASK)
      }
      progress("rediscovering previews in ${projectRoot.name}: ./gradlew $DISCOVER_TASK")
      return runner.run(projectRoot, wrapper, arguments) { line ->
        if (line.startsWith("> Task ")) progress(line)
      }
    }
  }

  companion object {
    val TASKS = listOf("composePreviewDiscover", "composePreviewDaemonStart")

    const val DISCOVER_TASK = "composePreviewDiscover"

    /** Mirrors [GradleSourceCompiler]: the injected script cannot run under Isolated Projects. */
    private val ISOLATED_PROJECTS_OFF =
      listOf("-Dorg.gradle.unsafe.isolated-projects=false", "-Dorg.gradle.isolated-projects=false")
  }
}

/** Runs a Gradle wrapper; a seam so tests need no Gradle. */
fun interface GradleTaskRunner {

  data class Result(val exitCode: Int, val output: String, val timedOut: Boolean = false)

  fun run(
    projectRoot: File,
    wrapper: File,
    arguments: List<String>,
    onLine: (String) -> Unit,
  ): Result

  companion object {
    private const val MAX_CAPTURED_CHARS = 64 * 1024

    /**
     * A child process whose output is captured, never inherited: this process's stdout is the MCP
     * transport. Like [GradleSourceCompiler], it passes [androidSdks]' SDK as `ANDROID_HOME` to a
     * build that cannot see one, such as a worktree with no copy of the untracked
     * `local.properties` (yschimke/compose-ag-plugin#64).
     */
    fun subprocess(
      timeoutMs: Long = TimeUnit.MINUTES.toMillis(15),
      androidSdks: AndroidSdks = AndroidSdks(),
    ) = GradleTaskRunner { projectRoot, wrapper, arguments, onLine ->
      val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
      val command =
        (if (windows) listOf("cmd", "/c", wrapper.absolutePath) else listOf(wrapper.absolutePath)) +
          arguments
      val process = runCatching {
        ProcessBuilder(command)
          .directory(projectRoot)
          .redirectErrorStream(true)
          .apply {
            androidSdks.forBuild(projectRoot)?.let {
              environment()["ANDROID_HOME"] = it.absolutePath
            }
          }
          .start()
      }
        .getOrElse {
          return@GradleTaskRunner Result(-1, "could not start ${wrapper.path}: ${it.message}")
        }
      process.outputStream.close()
      val output = StringBuilder()
      val reader =
        Thread(
            {
              runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                  lines.forEach { line ->
                    runCatching { onLine(line) }
                    synchronized(output) {
                      if (output.length < MAX_CAPTURED_CHARS) output.appendLine(line)
                    }
                  }
                }
              }
            },
            "compose-preview-mcp-bootstrap",
          )
          .apply {
            isDaemon = true
            start()
          }
      if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        return@GradleTaskRunner Result(-1, synchronized(output) { output.toString() }, true)
      }
      reader.join(2_000)
      Result(process.exitValue(), synchronized(output) { output.toString() })
    }
  }
}
