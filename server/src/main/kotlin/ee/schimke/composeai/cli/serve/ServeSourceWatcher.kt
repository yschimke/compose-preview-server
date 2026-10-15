package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A directory to watch for one local session, with its subdirectories when [recursive]. */
data class WatchRoot(val dir: File, val recursive: Boolean)

/**
 * Rebuilds a local session when its sources change (`browse`, `serve --discover --watch`), so an
 * edit reaches the page without anyone pressing rebuild.
 *
 * One thread drains a [WatchService] and runs the rebuilds. A burst of saves is coalesced: a
 * session rebuilds once it has been quiet for [debounceMillis], and an edit landing mid-build
 * schedules exactly one more. Rebuilds run one at a time on this thread, which is also what the
 * shared build host needs; a rebuild that finds the button's build running is retried after
 * [retryMillis] rather than dropped.
 *
 * Build outputs and tool state are never watched (`build/`, `.gradle/`, `bazel-*`, …), or every
 * compile would schedule the next. A JDK without a native watcher (macOS) polls, so an edit there
 * can take a few seconds to notice.
 */
class ServeSourceWatcher(
  roots: Map<String, List<WatchRoot>>,
  private val rebuild: (String) -> LocalRebuildResult,
  private val debounceMillis: Long = 300,
  private val retryMillis: Long = 500,
  private val onLog: (String) -> Unit = { System.err.println(it) },
  private val nowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

  private val service: WatchService = FileSystems.getDefault().newWatchService()

  /** Each registered directory's key, and the sessions that directory belongs to. */
  private val keys = HashMap<WatchKey, Watched>()

  private class Watched(val dir: Path, val recursive: Boolean, val sessions: MutableSet<String>)

  /** Session id → when it is next due to rebuild; present while a change is pending. */
  private val due = LinkedHashMap<String, Long>()

  private val thread: Thread

  init {
    for ((session, sessionRoots) in roots) {
      for (root in sessionRoots) {
        if (root.dir.isDirectory) register(root.dir.toPath(), root.recursive, session)
      }
    }
    thread =
      Thread(::loop, "compose-preview-source-watcher").apply {
        isDaemon = true
        start()
      }
  }

  /** How many directories are watched, for the startup line. */
  val watchedDirectories: Int
    get() = synchronized(keys) { keys.size }

  private fun register(dir: Path, recursive: Boolean, session: String) {
    if (recursive) {
      // A directory can vanish mid-walk (a package renamed, a branch switched); what is left of it
      // is watched, and the watcher thread never dies of it.
      runCatching {
        Files.walk(dir).use { paths ->
          paths
            .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            .filter { it == dir || !isExcludedPath(dir, it) }
            .forEach { registerOne(it, recursive = true, session) }
        }
      }
        .onFailure { onLog("watch: cannot walk $dir: ${it.message}") }
    } else {
      registerOne(dir, recursive = false, session)
    }
  }

  private fun registerOne(dir: Path, recursive: Boolean, session: String) {
    val key =
      runCatching { dir.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE) }
        .onFailure { onLog("watch: cannot watch $dir: ${it.message}") }
        .getOrNull() ?: return
    synchronized(keys) {
      keys.getOrPut(key) { Watched(dir, recursive, mutableSetOf()) }.sessions += session
    }
  }

  private fun loop() {
    try {
      while (!Thread.currentThread().isInterrupted) {
        val key = service.poll(pollMillis(), TimeUnit.MILLISECONDS)
        if (key != null) drain(key)
        rebuildDue()
      }
    } catch (_: ClosedWatchServiceException) {
      // close()
    } catch (_: InterruptedException) {
      // close()
    }
  }

  /** How long to wait for an event: until the next pending session is due, or a while. */
  private fun pollMillis(): Long {
    val next = due.values.minOrNull() ?: return IDLE_POLL_MILLIS
    return (next - nowMillis()).coerceIn(1, IDLE_POLL_MILLIS)
  }

  private fun drain(first: WatchKey) {
    var key: WatchKey? = first
    while (key != null) {
      val watched = synchronized(keys) { keys[key] }
      if (watched != null) {
        for (event in key.pollEvents()) {
          if (event.kind() == OVERFLOW) {
            touch(watched.sessions)
            continue
          }
          val name = event.context() as? Path ?: continue
          val path = watched.dir.resolve(name)
          if (isExcludedName(name.toString())) continue
          if (
            event.kind() == ENTRY_CREATE &&
              watched.recursive &&
              Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
          ) {
            // A new package directory: watch it, and treat whatever it already holds as changed.
            watched.sessions.toList().forEach { register(path, recursive = true, it) }
          }
          if (isIgnoredFile(name.toString())) continue
          touch(watched.sessions)
        }
      }
      if (!key.reset()) synchronized(keys) { keys.remove(key) }
      key = service.poll()
    }
  }

  private fun touch(sessions: Collection<String>) {
    val at = nowMillis() + debounceMillis
    sessions.forEach { due[it] = at }
  }

  private fun rebuildDue() {
    val now = nowMillis()
    val ready = due.filterValues { it <= now }.keys.toList()
    for (session in ready) {
      due.remove(session)
      val result = runCatching {
        rebuild(session)
      }
        .onFailure { onLog("watch: rebuilding $session failed: ${it.message}") }
        .getOrNull()
      when (result) {
        null -> Unit
        // Someone pressed rebuild; it may have started before this edit was saved.
        LocalRebuildResult.Busy -> due.putIfAbsent(session, nowMillis() + retryMillis)
        is LocalRebuildResult.Reloaded ->
          onLog("watch: $session reloaded in ${result.durationMs} ms")
        is LocalRebuildResult.Restarted ->
          onLog("watch: $session restarted (${result.reason}) after ${result.durationMs} ms")
        is LocalRebuildResult.Failed -> onLog("watch: $session build failed: ${result.reason}")
        LocalRebuildResult.NotFound -> Unit
      }
    }
  }

  override fun close() {
    thread.interrupt()
    runCatching { service.close() }
    thread.join(TimeUnit.SECONDS.toMillis(2))
  }

  companion object {
    private const val IDLE_POLL_MILLIS = 1_000L
    private const val OUTPUT_DIR = "build/compose-previews"

    /** Directories holding build outputs or tool state, never sources. */
    private val EXCLUDED_DIRS =
      setOf(
        "build",
        "out",
        ".gradle",
        ".kotlin",
        ".git",
        ".idea",
        ".compose-preview",
        ".cxx",
        "node_modules",
      )

    private val json = Json { ignoreUnknownKeys = true }

    internal fun isExcludedName(name: String): Boolean =
      name in EXCLUDED_DIRS || name.startsWith("bazel-")

    private fun isExcludedPath(root: Path, dir: Path): Boolean =
      root.relativize(dir).any { isExcludedName(it.toString()) }

    /** Editor swap, backup and lock files, which come and go around every save. */
    internal fun isIgnoredFile(name: String): Boolean =
      name.startsWith(".#") ||
        name.endsWith("~") ||
        name.endsWith(".swp") ||
        name.endsWith(".swx") ||
        name.endsWith(".tmp") ||
        name == "4913"

    /**
     * What to watch for a local [module].
     *
     * A Gradle module is its project directory, recursively. A Bazel module's project directory is
     * the CLI's output base, so its sources are read from the `build-artifacts.json` the CLI writes
     * beside `previews.json`: each source's directory under the workspace root the launch
     * descriptor names, watched without recursion so a monorepo is never walked. A `BUILD` edit in
     * one of those directories is seen too; one elsewhere is not.
     */
    fun rootsFor(module: PreviewModule): List<WatchRoot> {
      val output = File(module.projectDir, OUTPUT_DIR)
      val workspace =
        ServeBundleDaemon.readLaunchDescriptor(File(output, "daemon-launch.json"))
          ?.systemProperties
          ?.get("composeai.daemon.workspaceRoot")
          ?.let(::File)
      val artifacts = File(output, "build-artifacts.json")
      if (workspace == null || !artifacts.isFile || workspace == module.projectDir) {
        return listOf(WatchRoot(module.projectDir, recursive = true))
      }
      val sources = runCatching {
        (json.parseToJsonElement(artifacts.readText()).jsonObject["sourceFiles"] as? JsonArray)
          .orEmpty()
          .mapNotNull { it.jsonPrimitive.contentOrNull }
      }
        .getOrDefault(emptyList())
      return sources
        .filterNot { it.startsWith("bazel-out/") || it.startsWith("external/") }
        .map { File(it).let { file -> if (file.isAbsolute) file else workspace.resolve(it) } }
        .mapNotNull { it.parentFile }
        .distinct()
        .filter { it.isDirectory }
        .map { WatchRoot(it, recursive = false) }
    }
  }
}
