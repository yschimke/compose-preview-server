package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.client.WorkspaceId
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session subscription bookkeeping for the `subscribe` resources capability:
 * - **Per-URI subscriptions** ([subscribe]/[unsubscribe]) from `resources/subscribe`, forwarded as
 *   `notifications/resources/updated` when that URI's bytes change.
 * - **Watch sets** ([watch]/[unwatch]) from the `watch` tool: workspace + optional module +
 *   optional FQN glob, expanded to URIs, prioritised via `setVisible`/`setFocus`, and re-expanded
 *   on `discoveryUpdated`.
 *
 * Sessions are identified by reference equality, so the transport decides what a session is.
 */
class Subscriptions {

  /** URI → set of session refs subscribed to it. */
  private val byUri = ConcurrentHashMap<String, MutableSet<Session>>()

  /** Session ref → its watch sets. */
  private val watches = ConcurrentHashMap<Session, MutableList<WatchEntry>>()

  /**
   * `(uri, kind)` → sessions subscribed via `subscribe_preview_data`, refcounted so the daemon gets
   * `data/subscribe` on first reference and `data/unsubscribe` on last release, and disconnects
   * don't leak daemon-side subscriptions.
   */
  private val byDataKey = ConcurrentHashMap<DataSubKey, MutableSet<Session>>()

  fun subscribe(uri: String, session: Session) {
    val set = byUri.computeIfAbsent(uri) { ConcurrentHashMap.newKeySet() }
    set.add(session)
  }

  fun unsubscribe(uri: String, session: Session) {
    byUri[uri]?.remove(session)
  }

  /**
   * Adds a data-product subscription; `true` iff [session] is the first for the pair (forward
   * `data/subscribe`).
   */
  fun subscribeData(uri: String, kind: String, session: Session): Boolean {
    val key = DataSubKey(uri, kind)
    var firstRef = false
    byDataKey.compute(key) { _, existing ->
      val set = existing ?: ConcurrentHashMap.newKeySet<Session>().also { firstRef = true }
      // Re-subscribing the same session is idempotent (set semantics) — but we still surface
      // `firstRef = true` only when the set was empty before this call.
      set.add(session)
      set
    }
    return firstRef
  }

  /**
   * Removes [session] from the subscription; `true` iff it was the last (forward
   * `data/unsubscribe`).
   */
  fun unsubscribeData(uri: String, kind: String, session: Session): Boolean {
    val key = DataSubKey(uri, kind)
    var lastRef = false
    byDataKey.computeIfPresent(key) { _, set ->
      set.remove(session)
      if (set.isEmpty()) {
        lastRef = true
        null
      } else set
    }
    return lastRef
  }

  /**
   * On disconnect: removes and returns the pairs [session] held the last reference to, so the
   * supervisor can unsubscribe each from its daemon.
   */
  fun forgetDataSubscriptions(session: Session): List<DataSubKey> {
    val released = mutableListOf<DataSubKey>()
    val iter = byDataKey.entries.iterator()
    while (iter.hasNext()) {
      val (key, set) = iter.next()
      if (set.remove(session) && set.isEmpty()) {
        released.add(key)
        iter.remove()
      }
    }
    return released
  }

  /** Registers a watch entry for [session]. Returns the entry so the tool can echo it back. */
  fun watch(session: Session, entry: WatchEntry): WatchEntry {
    val list = watches.computeIfAbsent(session) { mutableListOf() }
    synchronized(list) { list.add(entry) }
    return entry
  }

  /** Removes every watch for [session] matching [predicate]. Returns the number removed. */
  fun unwatch(session: Session, predicate: (WatchEntry) -> Boolean): Int {
    val list = watches[session] ?: return 0
    return synchronized(list) {
      val before = list.size
      list.removeAll(predicate)
      before - list.size
    }
  }

  /**
   * Drops [session]'s resource subscriptions and watches. Data subscriptions are released
   * separately by [forgetDataSubscriptions], which reports which keys need a wire call.
   */
  fun forget(session: Session) {
    byUri.values.forEach { it.remove(session) }
    watches.remove(session)
  }

  /** Sessions currently subscribed to [uri]. */
  fun sessionsSubscribedTo(uri: String): Set<Session> = byUri[uri]?.toSet() ?: emptySet()

  /**
   * Subscribed URIs for the same preview as [uri], including override-bearing variants. Keys are
   * exactly what each client subscribed to, so notifications name the resource the client knows.
   */
  fun subscribedUrisMatching(uri: PreviewUri): Map<String, Set<Session>> {
    val base = uri.copy(overridesJson = null)
    return byUri.entries
      .mapNotNull { (subscribedUri, sessions) ->
        val parsed = PreviewUri.parseOrNull(subscribedUri) ?: return@mapNotNull null
        if (parsed.copy(overridesJson = null) != base) return@mapNotNull null
        subscribedUri to sessions.toSet()
      }
      .toMap()
  }

  /** Watch entries registered by [session], snapshot. */
  fun watchesFor(session: Session): List<WatchEntry> =
    watches[session]?.let { synchronized(it) { it.toList() } } ?: emptyList()

  /** Every session whose watch set matches [uri] (each once). */
  fun sessionsWatching(uri: PreviewUri): Set<Session> {
    val out = mutableSetOf<Session>()
    for ((session, entries) in watches) {
      val list = synchronized(entries) { entries.toList() }
      if (list.any { it.matches(uri) }) out.add(session)
    }
    return out
  }

  /**
   * Union of all sessions' watched URIs for [workspaceId] + [modulePath]; [WatchPropagator]
   * forwards it as `setVisible`.
   */
  fun urisWatchedFor(
    workspaceId: WorkspaceId,
    modulePath: String,
    candidatePreviews: Sequence<PreviewUri>,
  ): Set<PreviewUri> {
    val allEntries = watches.values.flatMap { synchronized(it) { it.toList() } }
    if (allEntries.isEmpty()) return emptySet()
    val out = mutableSetOf<PreviewUri>()
    candidatePreviews.forEach { uri ->
      if (uri.workspaceId != workspaceId || uri.modulePath != modulePath) return@forEach
      if (allEntries.any { it.matches(uri) }) out.add(uri)
    }
    return out
  }

  /** Snapshot of every (session, watch) pair — for diagnostics / `list_watches` tool. */
  fun snapshotAllWatches(): List<Pair<Session, WatchEntry>> {
    val out = mutableListOf<Pair<Session, WatchEntry>>()
    for ((session, entries) in watches) {
      val list = synchronized(entries) { entries.toList() }
      list.forEach { out.add(session to it) }
    }
    return out
  }
}

/** Refcount key for `subscribe_preview_data`, returned to callers on disconnect. */
data class DataSubKey(val uri: String, val kind: String)

/**
 * One area-of-interest registration: [workspaceId] is required (registered via `register_project`);
 * a null [modulePath] means any module, and a null [fqnGlob] every preview ([FqnGlob] syntax).
 */
data class WatchEntry(
  val workspaceId: WorkspaceId,
  val modulePath: String? = null,
  private val fqnGlobPattern: String? = null,
) {
  private val compiledGlob: FqnGlob? = fqnGlobPattern?.let { FqnGlob(it) }

  val fqnGlob: String?
    get() = fqnGlobPattern

  fun matches(uri: PreviewUri): Boolean {
    if (uri.workspaceId != workspaceId) return false
    if (modulePath != null && uri.modulePath != modulePath) return false
    if (compiledGlob != null && !compiledGlob.matches(uri.previewFqn)) return false
    return true
  }

  override fun toString(): String =
    "WatchEntry(workspace=$workspaceId, module=$modulePath, fqn=$fqnGlobPattern)"
}
