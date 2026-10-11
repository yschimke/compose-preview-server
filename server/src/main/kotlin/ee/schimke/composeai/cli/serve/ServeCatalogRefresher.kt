package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

enum class CatalogRefreshResult {
  UPDATED,
  CURRENT,
  UNAVAILABLE,
  FAILED,
  NOT_FOUND,
}

/**
 * Keeps a running `serve` fresh against catalog branches that change routinely. Catalogs are
 * otherwise fetched once at startup; a daemon thread periodically resolves each branch head and,
 * when it moved, re-runs the same [reload] path (`ServeCatalogStore.load`), which re-registers the
 * host in place. Unresolvable heads (offline, no `git`) are skipped.
 *
 * @param entries the branches to watch (`system`, `repo`, full `branch` ref), evaluated per pass so
 *   runtime-published catalogs are picked up and retired ones dropped.
 * @param reload re-fetch and re-register one system, returning the store's result or null.
 *   [checkOne] decides what it means for the recorded head: a load that registered but couldn't
 *   read everything is serving but not settled.
 * @param headResolver resolve a branch head sha, or null; defaults to [gitLsRemoteHead], injected
 *   for tests.
 * @param intervalMillis poll cadence; the first tick fires one interval after [start].
 */
public class ServeCatalogRefresher(
  private val entries: () -> List<Entry>,
  private val reload: (system: String, repo: String) -> ServeCatalogStore.Result?,
  private val intervalMillis: Long,
  private val headResolver: (repo: String, branch: String) -> String? = ::gitLsRemoteHead,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {

  /** One watched catalog branch. */
  data class Entry(val system: String, val repo: String, val branch: String)

  private val lastHead = ConcurrentHashMap<String, String>()

  /**
   * How many times each system was declared unsettled; monotonic. A count rather than a flag,
   * because a recorder must ask "did one arrive since I started?": post-publish lanes and
   * [seedInitialHeads] run concurrently with loads, so a flag could be consumed before the window
   * it covers. Recorders take an [invalidationMark] and [recordHead] writes only if it still
   * matches, inside [lastHead]'s per-key `compute`.
   */
  private val invalidations = ConcurrentHashMap<String, Long>()
  private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "serve-catalog-refresh").apply { isDaemon = true }
  }

  /**
   * Record current heads for catalogs that loaded completely at boot, so their first tick reloads
   * only after a branch move. Startup failures and incomplete loads are excluded so the first tick
   * retries them rather than settling a revision they couldn't fully read.
   */
  fun seedInitialHeads(systems: Set<String> = entries().mapTo(linkedSetOf()) { it.system }) {
    for (e in entries()) {
      if (e.system !in systems) continue
      val head = headResolver(e.repo, e.branch) ?: continue
      // Mark `0`: the boot load finished before this ran, so any invalidation since process start
      // postdates it, including those during this loop's `git ls-remote` calls.
      recordHead(e.system, head, mark = 0L)
    }
  }

  /**
   * Forget recorded heads for [systems] so the next [tick] re-fetches them though the branch hasn't
   * moved (e.g. after a trust revocation, which must re-verify now).
   */
  fun forgetHeads(systems: Collection<String>) {
    for (system in systems) {
      // Bump and removal in one `compute`, so they can't interleave with [recordHead]. Returning
      // null removes the entry.
      lastHead.compute(system) { _, _ ->
        invalidations.merge(system, 1L, Long::plus)
        null
      }
    }
  }

  /**
   * The invalidation count to hand back to [recordHead] once the work being vouched for is done.
   */
  private fun invalidationMark(system: String): Long = invalidations[system] ?: 0L

  /**
   * Record [head] for [system] unless [forgetHeads] ran since [mark] was taken. Check and write are
   * one `compute` on [lastHead] (as [forgetHeads] uses), leaving no window between them.
   */
  private fun recordHead(system: String, head: String, mark: Long) {
    lastHead.compute(system) { _, current ->
      if (invalidationMark(system) == mark) head else current
    }
  }

  /** Start the daemon poller. Idempotent-safe to call once after [seedInitialHeads]. */
  fun start() {
    exec.scheduleWithFixedDelay(
      {
        runCatching { tick() }
          .onFailure { onLog("serve: catalog refresh tick failed: ${it.message}") }
      },
      intervalMillis,
      intervalMillis,
      TimeUnit.MILLISECONDS,
    )
  }

  /** One poll pass over every watched branch; visible for deterministic tests. */
  @Synchronized
  fun tick() {
    for (e in entries()) checkOne(e)
  }

  /**
   * Check one catalog now via the poller's path. [force] drops the recorded head first (like
   * [forgetHeads]) so an unmoved branch is re-read, e.g. after discarding the blob cache.
   */
  @Synchronized
  fun refresh(system: String, force: Boolean = false): CatalogRefreshResult {
    val entry =
      entries().firstOrNull { it.system == system } ?: return CatalogRefreshResult.NOT_FOUND
    // [forgetHeads] rather than a bare removal, for the reason its own doc gives: the mark is what
    // stops a concurrent seed re-recording the head this operator just asked to be re-read.
    if (force) forgetHeads(listOf(system))
    return checkOne(entry)
  }

  private fun checkOne(e: Entry): CatalogRefreshResult {
    // Can't resolve the head (offline / git missing / private) → leave what we serve untouched.
    val head = headResolver(e.repo, e.branch) ?: return CatalogRefreshResult.UNAVAILABLE
    if (head == lastHead[e.system]) return CatalogRefreshResult.CURRENT
    val prev = lastHead[e.system]
    onLog(
      "serve: catalog ${e.system} (${e.branch}) moved ${prev?.take(7) ?: "?"}→${head.take(7)} — re-fetching"
    )
    // Taken before the reload, so an invalidation during it makes the head write conditional.
    val mark = invalidationMark(e.system)
    val loaded =
      runCatching { reload(e.system, e.repo) }.getOrNull() as? ServeCatalogStore.Result.Ok
    if (loaded == null) {
      onLog("serve: catalog ${e.system} refresh failed — keeping the current copy, will retry")
      return CatalogRefreshResult.FAILED
    }
    // Serving and settled are separate: the catalog is registered either way (both report
    // `UPDATED`), but an incomplete read, including a post-publish lane failing meanwhile,
    // withholds the recorded head so the next tick re-reads.
    if (loaded.incomplete) {
      // Recorded as an invalidation, not just left unrecorded: `refresh()` can run before
      // [seedInitialHeads], which would otherwise settle this incompletely read sha.
      forgetHeads(listOf(e.system))
      onLog(
        "serve: catalog ${e.system} refreshed to ${head.take(7)}, but some assets could not be " +
          "fetched — will re-read next tick"
      )
      return CatalogRefreshResult.UPDATED
    }
    // A post-publish lane that failed while this reload ran counts the same, and says so by having
    // bumped the mark — [recordHead] then leaves the head absent and the next tick re-reads.
    recordHead(e.system, head, mark)
    onLog(
      if (lastHead[e.system] == head) "serve: catalog ${e.system} refreshed to ${head.take(7)}"
      else
        "serve: catalog ${e.system} refreshed to ${head.take(7)}, but some assets could not be " +
          "fetched — will re-read next tick"
    )
    return CatalogRefreshResult.UPDATED
  }

  override fun close() {
    exec.shutdownNow()
  }
}

/**
 * Resolve a branch head via `git ls-remote`, which is unauthenticated and unrated (unlike the
 * GitHub API's 60/hr). Null on failure, which the refresher skips.
 */
public fun gitLsRemoteHead(repo: String, branch: String): String? {
  val (_, output) =
    gitLsRemote(repo, patterns = listOf("refs/heads/$branch"), waitSeconds = 20) ?: return null
  return Regex("\\b([0-9a-f]{40})\\b").find(output)?.groupValues?.get(1)
}

/**
 * `git ls-remote … https://github.com/<repo>.git <patterns>` as `(exitCode, output)`, or null when
 * it couldn't run or exceeded [waitSeconds]. Output drains on a daemon thread so a stalled remote
 * can't block past the bounded wait.
 */
internal fun gitLsRemote(
  repo: String,
  options: List<String> = emptyList(),
  patterns: List<String> = emptyList(),
  waitSeconds: Long,
): Pair<Int, String>? = runCatching {
  val proc =
    ProcessBuilder(listOf("git", "ls-remote") + options + "https://github.com/$repo.git" + patterns)
      .redirectErrorStream(true)
      .start()
  proc.outputStream.close()
  val captured = StringBuffer()
  val reader = Thread {
    runCatching { proc.inputStream.bufferedReader().use { r -> captured.append(r.readText()) } }
  }
    .apply {
      isDaemon = true
      start()
    }
  if (!proc.waitFor(waitSeconds, TimeUnit.SECONDS)) {
    proc.destroyForcibly()
    return null
  }
  reader.join(2_000)
  proc.exitValue() to captured.toString()
}
  .getOrNull()
