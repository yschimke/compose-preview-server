package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** What one [ServeLocalRebuild.rebuild] did, as `POST /{session}/rebuild` reports it. */
sealed interface LocalRebuildResult {
  /** The edit compiled and the running daemon loaded the new classes; reload the page. */
  data class Reloaded(val durationMs: Long) : LocalRebuildResult

  /**
   * The edit compiled, but the daemon must start again: its launch descriptor (classpath) or the
   * previews changed (one added or removed, or its label, theme or background), which a classloader
   * swap cannot pick up. [reason] says which.
   */
  data class Restarted(val durationMs: Long, val reason: String) : LocalRebuildResult

  /** The build failed; the page keeps showing the last good classes. */
  data class Failed(val durationMs: Long, val reason: String) : LocalRebuildResult

  /** A rebuild is already running on this server (any session; the build host is shared). */
  data object Busy : LocalRebuildResult

  /** Not a local project session (a catalog, a bundle, an unknown id). */
  data object NotFound : LocalRebuildResult
}

/**
 * What a local session's page knows about its rebuilds, as `GET /{session}/reload` reports it.
 *
 * [version] moves on every change, so a page waiting on it hears a build start as well as finish.
 * [generation] moves only when new classes are on screen (a reload or a restart), and is what a
 * page appends to its image URLs to fetch the new renders. [status] and [reason] describe the last
 * finished rebuild: `reloaded`, `restarted` or `failed`, or null before the first.
 */
data class LocalReloadState(
  val version: Long = 0,
  val generation: Long = 0,
  val building: Boolean = false,
  val status: String? = null,
  val reason: String? = null,
)

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
 * A failed compile changes nothing, so the last good classes keep rendering. One rebuild runs at a
 * time across the server, whichever session asked: every session shares one build host, whose
 * connection is not thread-safe. The same handling serves Gradle and Bazel: the CLI's Bazel build
 * host answers `composePreviewCompile` with a rebuild into the same output paths.
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
  private val building = AtomicBoolean(false)
  private val states = ConcurrentHashMap<String, MutableStateFlow<LocalReloadState>>()

  /** Whether [sessionId] is a local session this can rebuild. */
  fun canRebuild(sessionId: String): Boolean = sessionId in modules

  /** The local modules, by session id, for a source watcher to derive its roots from. */
  fun modules(): Map<String, PreviewModule> = modules

  /** [sessionId]'s reload state now, or null when it is not a local session. */
  fun reloadState(sessionId: String): LocalReloadState? = stateOf(sessionId)?.value

  /**
   * [sessionId]'s reload state once its version passes [afterVersion], or the current state after
   * [timeoutMillis] with nothing new: the long poll behind `GET /{session}/reload`. Null when it is
   * not a local session.
   */
  suspend fun awaitReloadState(
    sessionId: String,
    afterVersion: Long,
    timeoutMillis: Long,
  ): LocalReloadState? {
    val state = stateOf(sessionId) ?: return null
    return withTimeoutOrNull(timeoutMillis) { state.first { it.version > afterVersion } }
      ?: state.value
  }

  /**
   * The reload states of every local session among [sessionIds], once the sum of their versions
   * passes [afterVersion], or as they stand after [timeoutMillis] with nothing new: the long poll
   * behind `GET /modules/reload`. A version only grows, so the sum moves whenever any session's
   * does, and one poll serves a page showing every module. Null when none of them is local.
   */
  suspend fun awaitReloadStates(
    sessionIds: Collection<String>,
    afterVersion: Long,
    timeoutMillis: Long,
  ): Map<String, LocalReloadState>? {
    val flows = sessionIds.mapNotNull { id -> stateOf(id)?.let { id to it } }
    if (flows.isEmpty()) return null
    val ids = flows.map { it.first }
    val states =
      withTimeoutOrNull(timeoutMillis) {
        combine(flows.map { it.second }) { it.toList() }
          .first { all -> all.sumOf { it.version } > afterVersion }
      } ?: flows.map { it.second.value }
    return ids.zip(states).toMap()
  }

  private fun stateOf(sessionId: String): MutableStateFlow<LocalReloadState>? =
    if (sessionId in modules)
      states.computeIfAbsent(sessionId) { MutableStateFlow(LocalReloadState()) }
    else null

  fun rebuild(sessionId: String): LocalRebuildResult {
    val module = modules[sessionId] ?: return LocalRebuildResult.NotFound
    if (!building.compareAndSet(false, true)) return LocalRebuildResult.Busy
    val state = stateOf(sessionId)!!
    state.update { it.copy(version = it.version + 1, building = true) }
    var result: LocalRebuildResult = LocalRebuildResult.NotFound
    try {
      result = rebuildLocked(sessionId, module)
      return result
    } finally {
      state.update {
        val shown = result is LocalRebuildResult.Reloaded || result is LocalRebuildResult.Restarted
        it.copy(
          version = it.version + 1,
          generation = if (shown) it.generation + 1 else it.generation,
          building = false,
          status =
            when (result) {
              is LocalRebuildResult.Reloaded -> "reloaded"
              is LocalRebuildResult.Restarted -> "restarted"
              is LocalRebuildResult.Failed -> "failed"
              else -> it.status
            },
          reason =
            when (val r = result) {
              is LocalRebuildResult.Restarted -> r.reason
              is LocalRebuildResult.Failed -> r.reason
              is LocalRebuildResult.Reloaded -> null
              else -> it.reason
            },
        )
      }
      building.set(false)
    }
  }

  private fun rebuildLocked(sessionId: String, module: PreviewModule): LocalRebuildResult {
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
    // An unreadable manifest keeps the previews the session has; one that parses is the truth,
    // even with no previews left in it.
    val previews =
      File(output, "previews.json")
        .readTextOrNull()
        ?.let { text ->
          runCatching { json.decodeFromString(PreviewManifest.serializer(), text) }.getOrNull()
        }
        ?.let { previewsOf(module, it) } ?: state.previews
    val reason =
      when {
        descriptor.readTextOrNull() != before -> "the daemon's classpath changed"
        previews.map { it.id } != state.previews.map { it.id } -> "the preview set changed"
        previews != state.previews -> "a preview's metadata changed"
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
  }

  private fun File.readTextOrNull(): String? = takeIf {
    it.isFile
  }
    ?.let { runCatching { it.readText() }.getOrNull() }

  private companion object {
    const val OUTPUT_DIR = "build/compose-previews"
    val json = Json { ignoreUnknownKeys = true }
  }
}
