package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

/** What one [ServeLocalRebuild.rebuild] did, as `POST /{session}/rebuild` reports it. */
sealed interface LocalRebuildResult {
  /** The edit compiled and the running daemon loaded the new classes; reload the page. */
  data class Reloaded(val durationMs: Long) : LocalRebuildResult

  /**
   * The edit compiled, but the daemon must start again: its launch descriptor (classpath) or the
   * preview set changed, which a classloader swap cannot pick up. [reason] says which.
   */
  data class Restarted(val durationMs: Long, val reason: String) : LocalRebuildResult

  /** The build failed; the page keeps showing the last good classes. */
  data class Failed(val durationMs: Long, val reason: String) : LocalRebuildResult

  /** A rebuild of this session is already running. */
  data object Busy : LocalRebuildResult

  /** Not a local project session (a catalog, a bundle, an unknown id). */
  data object NotFound : LocalRebuildResult
}

/**
 * Rebuilds a local project session (`browse`, `serve --discover`) after an edit, without restarting
 * the server: `:<module>:composePreviewCompile` through the build host, then the cheapest way to
 * show the result.
 * - The descriptor and preview set are unchanged: [ServeHost.sourcesChanged] has the running daemon
 *   swap in the new classes and the host drop its cached renders.
 * - Either changed (a dependency or BUILD edit, a preview added or removed): the session is
 *   re-registered with the new previews, which closes the old daemon; the next request starts one
 *   from the new descriptor.
 *
 * A failed compile changes nothing, so the last good classes keep rendering. The same handling
 * serves Gradle and Bazel: the CLI's Bazel build host answers `composePreviewCompile` with a
 * rebuild into the same output paths.
 */
class ServeLocalRebuild(
  private val registry: ServeSessionRegistry,
  /** Each local session id and the module it serves. */
  private val modules: Map<String, PreviewModule>,
  /** Runs `:<module>:composePreviewCompile`; true when the build succeeded. */
  private val compile: (PreviewModule) -> Boolean,
  /** The servable previews of a re-read manifest, as the session was opened with. */
  private val previewsOf: (PreviewModule, PreviewManifest) -> List<ServePreview>,
  private val nanoTime: () -> Long = System::nanoTime,
) {
  private val inFlight = ConcurrentHashMap.newKeySet<String>()

  /** Whether [sessionId] is a local session this can rebuild. */
  fun canRebuild(sessionId: String): Boolean = sessionId in modules

  fun rebuild(sessionId: String): LocalRebuildResult {
    val module = modules[sessionId] ?: return LocalRebuildResult.NotFound
    if (!inFlight.add(sessionId)) return LocalRebuildResult.Busy
    try {
      val output = File(module.projectDir, OUTPUT_DIR)
      val descriptor = File(output, "daemon-launch.json")
      val before = descriptor.readTextOrNull()
      val started = nanoTime()
      val compiled = runCatching { compile(module) }.getOrDefault(false)
      val durationMs = TimeUnit.NANOSECONDS.toMillis(nanoTime() - started)
      if (!compiled) {
        return LocalRebuildResult.Failed(
          durationMs,
          ":${module.gradlePath}:composePreviewCompile failed (see the server log); the last " +
            "good build is still shown",
        )
      }
      val state = registry.peekState(sessionId) ?: return LocalRebuildResult.NotFound
      val previews =
        File(output, "previews.json")
          .readTextOrNull()
          ?.let { text ->
            runCatching { json.decodeFromString(PreviewManifest.serializer(), text) }.getOrNull()
          }
          ?.let { previewsOf(module, it) }
          ?.takeIf { it.isNotEmpty() } ?: state.previews
      val reason =
        when {
          descriptor.readTextOrNull() != before -> "the daemon's classpath changed"
          previews.map { it.id } != state.previews.map { it.id } -> "the preview set changed"
          else -> null
        }
      if (reason != null) {
        registry.register(sessionId, state.copy(previews = previews))
        return LocalRebuildResult.Restarted(durationMs, reason)
      }
      // A suspended session has no daemon; its next resume reads the new classes from disk.
      val host = registry.peekHost(sessionId)
      if (host != null && !host.sourcesChanged(emptyList())) {
        registry.register(sessionId, state)
        return LocalRebuildResult.Restarted(durationMs, "the host cannot reload classes in place")
      }
      return LocalRebuildResult.Reloaded(durationMs)
    } finally {
      inFlight.remove(sessionId)
    }
  }

  private fun File.readTextOrNull(): String? = takeIf {
    it.isFile
  }?.let { runCatching { it.readText() }.getOrNull() }

  private companion object {
    const val OUTPUT_DIR = "build/compose-previews"
    val json = Json { ignoreUnknownKeys = true }
  }
}
