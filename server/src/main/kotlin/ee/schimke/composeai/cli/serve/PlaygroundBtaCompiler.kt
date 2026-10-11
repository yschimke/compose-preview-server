@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.daemon.bta.BtaCompileSession
import ee.schimke.composeai.daemon.bta.DiagnosticCollector
import ee.schimke.composeai.daemon.protocol.CompileErrorDetail
import java.io.File
import java.nio.file.Path as NioPath
import okio.Path
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPluginOption

/**
 * In-process [PlaygroundCompileService.Compiler] backed by the Kotlin Build Tools API: compiles a
 * snippet against a catalog classpath without a daemon or Gradle (`docs/design/PLAYGROUND.md`
 * §2.1). Wraps [BtaCompileSession] and maps BTA's diagnostics to [PlaygroundDiagnostic].
 *
 * The BTA implementation jars and the Compose compiler plugin are staged into the install's
 * `lib-bta/` (see `cli/build.gradle.kts`) and located by [fromInstall]. Impl jars load into BTA's
 * isolated classloader; the Compose plugin rides the per-compile [CompilerPlugin] classpath, as in
 * the daemon's `DefaultBtaCompileService`.
 */
class PlaygroundBtaCompiler(
  /** `kotlin-build-tools-impl` + its transitive frontend — BTA's isolated-classloader classpath. */
  private val btaImplJars: List<NioPath>,
  /** `kotlin-compose-compiler-plugin-embeddable` — the Compose plugin's per-compile classpath. */
  private val compilerPluginJars: List<NioPath>,
  /** Per-process IC cache dir; reused across compiles in this host. */
  private val icWorkingDir: NioPath,
  private val moduleName: String = "playground",
) : PlaygroundCompileService.Compiler {

  // One session per compiler, retained across requests so the BTA-impl bootstrap is paid once;
  // BtaCompileSession serialises concurrent compiles internally. Lazy so probing availability
  // doesn't force the ~5 s toolchain load.
  private val session by lazy {
    BtaCompileSession(
      implClasspath = btaImplJars,
      icWorkingDir = icWorkingDir,
      moduleName = moduleName,
    )
  }
  private val compilerPlugins by lazy { composeCompilerPlugins(compilerPluginJars) }

  override fun compile(
    sources: List<Path>,
    classpath: List<Path>,
    outputDir: Path,
  ): List<PlaygroundDiagnostic> {
    return compileWithCollector { collector ->
      session.compile(
        sources = sources.map { it.toNioPath() },
        compileClasspath = classpath.map { it.toNioPath() },
        outputDir = outputDir.toNioPath(),
        compilerPlugins = compilerPlugins,
        diagnosticListener = collector,
      )
    }
  }

  override fun compileIncremental(
    sources: List<Path>,
    classpath: List<Path>,
    outputDir: Path,
    workingDir: Path,
    modified: List<Path>,
    removed: List<Path>,
    firstBuild: Boolean,
  ): PlaygroundCompileService.IncrementalCompileResult {
    val diagnostics = compileWithCollector { collector ->
      session.compileIncremental(
        sources = sources.map { it.toNioPath() },
        compileClasspath = classpath.map { it.toNioPath() },
        outputDir = outputDir.toNioPath(),
        compilerPlugins = compilerPlugins,
        sourcesChanges =
          if (firstBuild) SourcesChanges.Unknown
          else
            SourcesChanges.Known(
              modified.map { java.io.File(it.toString()) },
              removed.map { java.io.File(it.toString()) },
            ),
        diagnosticListener = collector,
        workingDir = workingDir.toNioPath(),
      )
    }
    // The first call seeds BTA's IC state but cannot reuse any previous compilation. Report only
    // subsequent calls as incremental so the UI and soak counters measure an actual warm edit.
    return PlaygroundCompileService.IncrementalCompileResult(
      diagnostics,
      incremental = !firstBuild,
    )
  }

  private inline fun compileWithCollector(
    block: (DiagnosticCollector) -> Unit
  ): List<PlaygroundDiagnostic> {
    // Use the raw `error(...)` callbacks, not `DiagnosticCollector.errors`: the collector silently
    // drops messages that don't match a single-line `file:line:col message` shape, and K2 renders
    // its common overload failure over several lines:
    //
    //     …/Snippet.kt:31:3 none of the following candidates is applicable:
    //
    //     fun Slider(state: SliderState, …): Unit:
    //       No parameter with name 'valueRange' found.
    //
    // Those came back as an unanchored "compilation failed" (and on the leased path looked like an
    // infrastructure failure, forcing a full recompile). The collector is kept only as a
    // pass-through to [ErrorLog].
    val log = ErrorLog()
    val collector = DiagnosticCollector(delegate = log)
    return try {
      // Non-incremental: a fresh snippet against a stable classpath gains nothing from retained IC
      // state. Leased editing uses the incremental entry point above.
      block(collector)
      // A clean compile: the log only captures errors, so success yields no diagnostics.
      emptyList()
    } catch (t: Throwable) {
      // BtaCompileSession throws on COMPILATION_ERROR. A diagnostic the compiler anchored to a
      // source position is a user error and is reported as itself.
      val diagnostics = diagnosticsFrom(log.messages)
      if (diagnostics.any { it.file != null }) {
        diagnostics
      } else {
        // Nothing had a position: a bootstrap or IC failure. Keep the single file-level
        // `compilation failed:` shape that
        // `PlaygroundCompileService.isInfrastructureCompileFailure` reads to reset a lease, but
        // carry what was actually logged.
        val logged = log.messages.joinToString("\n") { it.trim() }.trim()
        listOf(
          PlaygroundDiagnostic(
            severity = PlaygroundSeverity.ERROR,
            message =
              "compilation failed: " + logged.ifEmpty { t.message ?: t.javaClass.simpleName },
          )
        )
      }
    }
  }

  /**
   * Records every `error(...)` BTA emits, verbatim and in order. [DiagnosticCollector] forwards
   * each error here before parsing it, so this sees the full text. Non-error events are dropped.
   */
  internal class ErrorLog : KotlinLogger {
    private val collected = mutableListOf<String>()

    /** Every error message BTA logged, in emission order. */
    val messages: List<String>
      get() = collected.toList()

    override val isDebugEnabled: Boolean
      get() = false

    override fun error(msg: String, throwable: Throwable?) {
      collected.add(msg)
    }

    override fun warn(msg: String) = Unit

    override fun warn(msg: String, throwable: Throwable?) = Unit

    override fun info(msg: String) = Unit

    override fun debug(msg: String) = Unit

    override fun lifecycle(msg: String) = Unit
  }

  companion object {
    /** The Compose plugin jar's stable coordinate prefix, used to split it out of `lib-bta/`. */
    private const val COMPOSE_PLUGIN_PREFIX = "kotlin-compose-compiler-plugin-embeddable"

    /**
     * Build a compiler from the CLI install's staged `lib-bta/` dir, or null when it isn't present
     * (a non-installed run, or a build that didn't stage it) — the route then reports the mode
     * unavailable. Splits the Compose plugin jar(s) out of the impl classpath.
     */
    fun fromInstall(icWorkingDir: NioPath): PlaygroundBtaCompiler? {
      val (implJars, pluginJars) = installJars() ?: return null
      return PlaygroundBtaCompiler(
        btaImplJars = implJars.map(File::toPath),
        compilerPluginJars = pluginJars.map(File::toPath),
        icWorkingDir = icWorkingDir,
      )
    }

    /**
     * The staged `lib-bta/` split into (impl, Compose-plugin) jars, or null when absent or without
     * an impl jar. Shared with [PlaygroundJailedCompiler] so jailed and in-process compilers use
     * the same toolchain.
     */
    fun installJars(): Pair<List<File>, List<File>>? {
      val jars = locateBundleSidecarJars("lib-bta")
      if (jars.isEmpty()) return null
      val (pluginJars, implJars) = jars.partition { it.name.startsWith(COMPOSE_PLUGIN_PREFIX) }
      if (implJars.isEmpty()) return null
      return implJars to pluginJars
    }

    /**
     * Compose compiler plugin config, mirroring the internal
     * `DefaultBtaCompileService.composeCompilerPlugins`: plugin id, jars, and the load-bearing
     * `sourceInformation=true` KGP enables by default. Empty when no plugin jar was staged.
     */
    internal fun composeCompilerPlugins(pluginJars: List<NioPath>): List<CompilerPlugin> =
      if (pluginJars.isEmpty()) {
        emptyList()
      } else {
        listOf(
          CompilerPlugin(
            "androidx.compose.compiler.plugins.kotlin",
            pluginJars,
            listOf(CompilerPluginOption("sourceInformation", "true")),
            emptySet(),
          )
        )
      }

    /**
     * BTA's diagnostic-line head (`file:///path:line:col message`, optionally prefixed `e:`), with
     * `DiagnosticCollector`'s tolerances but matched against the first line only, so the rest of a
     * multi-line diagnostic is kept.
     */
    private val ERROR_HEAD_RE =
      Regex("""^(?:e:\s*)?(?:file://)?(.+?):(\d+):(\d+)(?::?\s*(?:error:\s*)?)\s*(.*)$""")

    /**
     * Turn BTA's raw `error(...)` messages into diagnostics, losing nothing. A message with a
     * `file:line:col` head is anchored there and keeps its remaining lines (K2's candidate lists
     * and caret excerpt). A message with no head is still reported, unanchored.
     */
    internal fun diagnosticsFrom(messages: List<String>): List<PlaygroundDiagnostic> =
      messages.mapNotNull { raw ->
        val text = raw.trim()
        if (text.isEmpty()) return@mapNotNull null
        val head = text.substringBefore('\n').trim()
        val rest = text.substringAfter('\n', "").trimEnd()
        val match = ERROR_HEAD_RE.matchEntire(head)
        val line = match?.groupValues?.get(2)?.toIntOrNull()
        val column = match?.groupValues?.get(3)?.toIntOrNull()
        if (match == null || line == null || column == null) {
          PlaygroundDiagnostic(severity = PlaygroundSeverity.ERROR, message = text)
        } else {
          val message =
            listOf(match.groupValues[4].trim(), rest).filter { it.isNotEmpty() }.joinToString("\n")
          mapDiagnostics(
              listOf(
                CompileErrorDetail(
                  file = match.groupValues[1],
                  line = line,
                  column = column,
                  // A head that is nothing but a position (the message wrapped onto line two, or
                  // an empty one) still deserves to be shown, so fall back to the raw text.
                  message = message.ifEmpty { text },
                )
              )
            )
            .single()
        }
      }

    /**
     * Map BTA's [CompileErrorDetail]s (1-based) to [PlaygroundDiagnostic] (CodeMirror 0-based). The
     * file is reduced to its basename, the snippet filename the editor knows. Only errors reach
     * here.
     */
    internal fun mapDiagnostics(details: List<CompileErrorDetail>): List<PlaygroundDiagnostic> =
      details.map { detail ->
        PlaygroundDiagnostic(
          severity = PlaygroundSeverity.ERROR,
          message = detail.message,
          file = File(detail.file).name,
          line = (detail.line - 1).coerceAtLeast(0),
          ch = (detail.column - 1).coerceAtLeast(0),
        )
      }
  }
}
