package ee.schimke.composeai.mcp

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Recompiles a module's Kotlin/Java sources so the classes a daemon loads match the source on disk.
 *
 * A daemon's `fileChanged({kind:"source"})` handler only swaps its user classloader: it re-reads
 * the class directories Gradle wrote and does not compile anything. The editor integrations run a
 * compile first (VS Code runs `composePreviewCompile`, or the daemon's `compileSources` when BTA is
 * wired), but an agent that edits a file and calls `notify_file_changed` has nobody doing that, so
 * the swapped classloader loads the old bytecode and `render_preview` returns the old image
 * (issue #1169). The MCP server now runs this before it forwards `fileChanged`.
 */
fun interface SourceCompiler {
  fun compile(projectRoot: File, modulePath: String, sources: List<File>): SourceCompileOutcome
}

sealed interface SourceCompileOutcome {
  data class Ok(val durationMs: Long) : SourceCompileOutcome

  /** The compile ran and failed, usually a compile error; [reason] is one line for the agent. */
  data class Failed(val reason: String) : SourceCompileOutcome

  /** No compile could run at all (no Gradle wrapper, disabled, …). */
  data class Unavailable(val reason: String) : SourceCompileOutcome
}

/**
 * Stage-0 compile: `./gradlew <module>:composePreviewCompile`, the lifecycle task the Compose
 * Preview Gradle plugin registers for exactly this save loop. It runs the same Kotlin compile task
 * the daemon's class directories come from, without the discovery scan.
 *
 * The child's output is captured, never inherited: this process's stdout is the MCP transport.
 */
class GradleSourceCompiler(private val timeoutMs: Long = TimeUnit.MINUTES.toMillis(5)) :
  SourceCompiler {

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
    val startedAt = System.nanoTime()
    val process = runCatching {
      ProcessBuilder(command + listOf("--quiet", "--console=plain", task))
        .directory(projectRoot)
        .redirectErrorStream(true)
        .start()
    }
      .getOrElse {
        return SourceCompileOutcome.Unavailable("could not start ${wrapper.name}: ${it.message}")
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
      return SourceCompileOutcome.Failed("$task timed out after ${timeoutMs}ms")
    }
    reader.join(2_000)
    val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    if (process.exitValue() == 0) return SourceCompileOutcome.Ok(durationMs)
    val text = synchronized(output) { output.toString() }
    return SourceCompileOutcome.Failed("$task failed: ${summarizeGradleFailure(text)}")
  }

  companion object {
    const val TASK = "composePreviewCompile"
    private const val MAX_CAPTURED_CHARS = 64 * 1024

    /** The first Kotlin `e:` diagnostic, else Gradle's "What went wrong" line, else the tail. */
    internal fun summarizeGradleFailure(output: String): String {
      val lines = output.lines().map(String::trim).filter(String::isNotEmpty)
      lines
        .firstOrNull { it.startsWith("e: ") }
        ?.let {
          return it.removePrefix("e: ").take(300)
        }
      val wrong = lines.indexOfFirst { it.startsWith("* What went wrong") }
      if (wrong >= 0 && wrong + 1 < lines.size) return lines[wrong + 1].take(300)
      return lines.lastOrNull()?.take(300) ?: "exit code non-zero"
    }
  }
}
