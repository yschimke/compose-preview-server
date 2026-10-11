package ee.schimke.composeai.cli.serve

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Builds (forks) a tenant's session state on demand, the expensive discover/build step, behind a
 * seam tests can fake. Returns `null` when no such session can be created.
 */
fun interface ServeSessionFactory {
  fun create(sessionId: String): ServeSessionState?
}

/**
 * Multi-tenant registry of serve sessions behind one HTTP server.
 *
 * Sessions follow an Activity-style lifecycle so daemons don't run forever:
 * - created lazily via [factory] (the expensive build) on first use;
 * - opened into a daemon-backed host via [open] (cheap, from the built descriptor);
 * - suspended when idle ([suspendIdle]): the daemon closes but the [ServeSessionState] is kept, so
 *   the next request resumes without a rebuild.
 *
 * Never suspended while it has an open [lease] or active streams. At most one build per id under
 * racing callers.
 */
class ServeSessionRegistry(
  private val open: (ServeSessionState) -> ServeHost?,
  private val factory: ServeSessionFactory = ServeSessionFactory { null },
  private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
  reaperIntervalMillis: Long = idleTimeoutMillis,
  /**
   * Second-level idle window: a forked session suspended this long is removed and its worktree
   * pruned ([ServeSessionState.reclaim]), so a project-mode server doesn't accumulate worktrees
   * (see #2022). Must exceed [idleTimeoutMillis]; non-positive disables it.
   */
  private val suspendedGcTimeoutMillis: Long = DEFAULT_SUSPENDED_GC_TIMEOUT_MILLIS,
  /**
   * Idle window for shedding pooled daemons ([releaseIdleDaemons]), separate from session
   * suspension: a replica reopens cheaply but holds a weighted live seat while idle, so it gets a
   * shorter window. Non-positive falls back to [idleTimeoutMillis].
   */
  private val daemonIdleMillis: Long = DEFAULT_DAEMON_IDLE_MILLIS,
  /**
   * How recently a leaseholder must have been active for its lease to count as busy on
   * [idleMillis]. See [DEFAULT_LEASE_BUSY_MILLIS].
   */
  private val leaseBusyMillis: Long = DEFAULT_LEASE_BUSY_MILLIS,
  /**
   * Whether the box is under memory pressure, checked every reaper sweep; wired to the same gate
   * `/status.json` publishes. Defaults to never.
   */
  private val underMemoryPressure: () -> Boolean = { false },
  private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

  private class Entry(
    /** How to (re)open the host on resume; null for pinned sessions that are never suspended. */
    val state: ServeSessionState?,
    /** The live host, or null while suspended. */
    @Volatile var host: ServeHost?,
    /** Pinned sessions (e.g. static bundle hosts — no daemon to reclaim) are never suspended. */
    val pinned: Boolean,
    /**
     * True only for sessions built on demand by [factory] (project mode `?session=<rev>`, each with
     * a worktree); only these are removed by [reclaimIdleForked]. Registered sessions stay
     * resumable.
     */
    val forked: Boolean,
    @Volatile var lastAccess: Long,
    /**
     * Open request-scoped holders (`withLeasedSession`). Busy for their whole life, since a cold
     * `/render` or `/bundle.zip` can legitimately take minutes.
     */
    @Volatile var requestLeases: Int = 0,
    /**
     * Open connection holders (viewer WebSockets): keep the session resident, but count as busy
     * only while [lastLeaseActivity] is recent (see [idleMillis]).
     */
    @Volatile var connectionLeases: Int = 0,
    /**
     * Last time a leaseholder did something (lease taken, socket message via [Lease.touch],
     * acquire). Unlike the generous [lastAccess] (suspension), this answers "is someone being
     * served now?" against [leaseBusyMillis]; read only by [idleMillis].
     */
    @Volatile var lastLeaseActivity: Long = lastAccess,
    /**
     * When [host] last became resident (null while suspended); basis for `/status`'s per-daemon
     * uptime.
     */
    @Volatile var startedAt: Long? = null,
    /**
     * Set while a suspension closes this entry's detached host outside the lock. [liveHost] waits
     * it out so a resume never runs two daemons for one session.
     */
    /**
     * When [suspendIdle] released this host, or null while resident. The rotation key for
     * [resumeIdleOptimizers]; not [lastAccess], which would favour whatever was just parked.
     */
    @Volatile var suspendedAt: Long? = null,
    @Volatile var closing: Boolean = false,
    /**
     * True while [liveHost] reopens this host with the registry lock released; a second caller
     * waits on [closeFinished] instead of opening a duplicate.
     */
    @Volatile var opening: Boolean = false,
  ) {
    /** Every open holder, of either kind — the residency question. */
    val leases: Int
      get() = requestLeases + connectionLeases
  }

  /**
   * Snapshot of one resident session for `/status`'s running-servers view. [hasLiveStream]
   * distinguishes a live daemon from a static bundle host.
   */
  data class RunningDaemon(
    val id: String,
    val label: String,
    val pinned: Boolean,
    val hasLiveStream: Boolean,
    val liveSeatWeight: Int,
    val activeStreams: Int,
    val leases: Int,
    val startedAt: Long?,
    /** Render-latency counters for the host's live lane; null for hosts that don't track them. */
    val renderStats: RenderPerfSnapshot? = null,
    val daemonPools: List<DaemonPoolSnapshot> = emptyList(),
  )

  /** A live hold on a session that keeps it from being suspended until [close] (idempotent). */
  class Lease
  internal constructor(
    val host: ServeHost,
    private val onTouch: () -> Unit = {},
    private val onRelease: () -> Unit,
  ) : AutoCloseable {
    private val released = AtomicBoolean(false)

    /**
     * Serialises [touch] against [close], so a touch after release is truly a no-op rather than
     * marking a departed holder busy. A private monitor, not the registry lock (held across session
     * builds), so the socket message path never waits on another tenant's Gradle work. Always taken
     * before the registry lock, never after, so no deadlock.
     */
    private val gate = Any()

    /**
     * Report that the holder is doing something (e.g. a socket message). This keeps a connection
     * lease counting as busy on [idleMillis]; an untouched connection goes quiet while staying
     * resident, so an unattended tab doesn't hold off the optimizer. No-op once [close]d.
     */
    fun touch() {
      synchronized(gate) { if (!released.get()) onTouch() }
    }

    override fun close() {
      synchronized(gate) { if (released.compareAndSet(false, true)) onRelease() }
    }
  }

  private val lock = ReentrantLock()
  private val sessions = HashMap<String, Entry>()
  private var closed = false

  /** Signalled when a detached host finishes closing, releasing waiters in [liveHost]. */
  private val closeFinished = lock.newCondition()

  /**
   * Observers notified as a session goes resident→suspended, with the host about to close, so
   * callers can snapshot facts only readable off a live host ([peekHost] never resumes). Invoked
   * outside the lock; failures are swallowed.
   */
  private val suspendListeners = CopyOnWriteArrayList<(String, ServeHost) -> Unit>()

  /** Register a resident→suspended observer. See [suspendListeners]. */
  fun addSuspendListener(listener: (sessionId: String, host: ServeHost) -> Unit) {
    suspendListeners += listener
  }

  /**
   * Observers notified when a session is retired ([unregister]), so holders of a [peekHost]
   * snapshot can drop it instead of retaining it forever.
   */
  private val unregisterListeners = CopyOnWriteArrayList<(String) -> Unit>()

  /** Register a retirement observer. See [unregisterListeners]. */
  fun addUnregisterListener(listener: (sessionId: String) -> Unit) {
    unregisterListeners += listener
  }

  /**
   * A projection of a session's host that must outlive residency, captured and discarded as part of
   * the registry's own transitions. Unlike [addSuspendListener] (outside the lock), [capture] runs
   * under the lock just before detach and [discard] under the same lock as removal, so readers
   * never see a gap and slow writers can't resurrect retired entries.
   *
   * Both run with the registry lock held: no I/O, no blocking, no re-entry.
   */
  interface SessionSnapshots {
    /** Under the lock, immediately before [host] is detached from [sessionId]. */
    fun capture(sessionId: String, host: ServeHost)

    /** Under the lock, as [sessionId] is retired. */
    fun discard(sessionId: String)
  }

  @Volatile private var snapshots: SessionSnapshots? = null

  /** Install the [SessionSnapshots] hook. See its KDoc for the contract it must honour. */
  fun setSessionSnapshots(snapshots: SessionSnapshots?) {
    this.snapshots = snapshots
  }

  // Last acquire/lease/touch/release across all sessions; the basis for [idleMillis] and
  // [connectionIdleMillis].
  @Volatile private var lastActivity: Long = clock()

  // Disabled when either knob is non-positive; tests drive suspension directly.
  private val reaper: ScheduledExecutorService? =
    if (idleTimeoutMillis > 0 && reaperIntervalMillis > 0) {
      Executors.newSingleThreadScheduledExecutor { r ->
          Thread(r, "serve-session-reaper").apply { isDaemon = true }
        }
        .also {
          it.scheduleWithFixedDelay(
            {
              // Pressure first: `suspendIdle` needs ten untouched minutes, which a box filling in
              // five doesn't have.
              runCatching { if (underMemoryPressure()) shedUnderPressure() }
              // Suspend first, then GC: a session must be suspended (host released) before it's
              // eligible for the longer-window forked-session reclaim below.
              runCatching { suspendIdle() }
              runCatching { releaseIdleDaemons() }
              runCatching { reclaimIdleForked() }
              // After the shedding, not before: the lane a parked catalog is resumed into is
              // usually the one this sweep's suspensions just freed.
              runCatching { resumeIdleOptimizers() }
            },
            reaperIntervalMillis,
            reaperIntervalMillis,
            TimeUnit.MILLISECONDS,
          )
          // Pooled daemons are swept on their own shorter cadence (replicas hold weighted seats and
          // reopen cheaply). The pressure shed rides this faster cadence too, since a ten-minute
          // sweep is too slow for a box filling in five. Same thread, so sweeps never overlap.
          if (daemonIdleMillis > 0 && daemonIdleMillis < reaperIntervalMillis) {
            it.scheduleWithFixedDelay(
              {
                runCatching { if (underMemoryPressure()) shedUnderPressure() }
                runCatching { releaseIdleDaemons() }
              },
              daemonIdleMillis,
              daemonIdleMillis,
              TimeUnit.MILLISECONDS,
            )
          }
        }
    } else {
      null
    }

  /**
   * Seed a session from known [state], optionally with an open [host], replacing any prior entry.
   * Participates in suspend/resume like a forked one.
   *
   * Re-registration closes the replaced host (outside the lock), since a catalog refresh
   * re-registers the same id and the old daemon would otherwise leak.
   */
  fun register(
    sessionId: String,
    state: ServeSessionState? = null,
    host: ServeHost? = null,
    pinned: Boolean = false,
  ) {
    // A session may never take a top-level route name ([ServeSites.RESERVED_SYSTEMS]): it would be
    // unreachable, and `/api/` would fall through to `/{system}/` on a site host. Enforced here and
    // in [entryFor], the only two places a session id is bound.
    if (sessionId in ServeSites.RESERVED_SYSTEMS) {
      System.err.println(
        "serve: refusing session '$sessionId' — that name is one of the server's own routes"
      )
      return
    }
    val replaced = lock.withLock {
      check(!closed) { "ServeSessionRegistry is closed" }
      val prior = sessions[sessionId]
      // Registered sessions (the current-checkout default, bundle/catalog hosts) are never GC'd:
      // forked = false keeps them permanently resumable regardless of pinning.
      sessions[sessionId] =
        Entry(state, host, pinned, forked = false, lastAccess = clock()).also {
          if (host != null) it.startedAt = clock()
        }
      prior?.host?.takeIf { it !== host }
    }
    replaced?.let { runCatching { it.close() } }
  }

  /**
   * Drop [sessionId] entirely (a catalog retired at runtime, [ServeCatalogAdmin]), closing its host
   * outside the lock. False when nothing was registered.
   */
  fun unregister(sessionId: String): Boolean {
    val removed = lock.withLock {
      val removed = sessions.remove(sessionId)
      // Discarded under the same lock as the removal, so a concurrent detach can't resurrect the
      // snapshot.
      if (removed != null) snapshots?.let { runCatching { it.discard(sessionId) } }
      removed
    }
    removed?.host?.let { runCatching { it.close() } }
    // Outside the lock and guarded, exactly like the suspend notification: a listener may re-enter
    // the registry, and one that throws must not leave the session half-retired.
    if (removed != null) {
      unregisterListeners.forEach { listener -> runCatching { listener(sessionId) } }
    }
    return removed != null
  }

  /**
   * The live host for [sessionId], resuming or forking via [factory]; null if it can't be created.
   * Touches the idle clock.
   */
  fun acquire(sessionId: String): ServeHost? = lock.withLock {
    check(!closed) { "ServeSessionRegistry is closed" }
    val entry = entryFor(sessionId) ?: return null
    entry.lastAccess = clock()
    entry.lastLeaseActivity = clock()
    lastActivity = clock()
    liveHost(entry)
  }

  /**
   * Acquire [sessionId] and keep it resident for the [Lease]'s lifetime, or null.
   *
   * [connection] chooses how the hold counts on the idle clock:
   * - false (default): request-scoped, busy for its whole life (a cold `/render` can run 30-70s);
   * - true: a viewer WebSocket, resident unconditionally but busy only while active
   *   ([Lease.touch]); see [idleMillis].
   */
  fun lease(sessionId: String, connection: Boolean = false): Lease? = lock.withLock {
    check(!closed) { "ServeSessionRegistry is closed" }
    val entry = entryFor(sessionId) ?: return null
    entry.lastAccess = clock()
    entry.lastLeaseActivity = clock()
    lastActivity = clock()
    val host = liveHost(entry) ?: return null
    if (connection) entry.connectionLeases++ else entry.requestLeases++
    Lease(
      host,
      onTouch = {
        // No registry lock here — see [Lease.gate] for why, and for what serialises this against
        // the release below. Three independent volatile writes, on the per-message path.
        val now = clock()
        entry.lastLeaseActivity = now
        entry.lastAccess = now
        lastActivity = now
      },
    ) {
      lock.withLock {
        if (connection) entry.connectionLeases-- else entry.requestLeases--
        entry.lastAccess = clock() // start the idle clock fresh once the holder leaves
        entry.lastLeaseActivity = clock()
        lastActivity = clock()
      }
    }
  }

  /**
   * True when [sessionId] is a registered static (pinned) session that holds no daemon, so leasing
   * it spawns nothing. Unknown and daemon-backed sessions return false so the seat gate reserves a
   * seat. Never opens a host.
   */
  fun isKnownStatic(sessionId: String): Boolean = lock.withLock {
    sessions[sessionId]?.pinned == true
  }

  /**
   * Whether [sessionId] exists, without opening anything. Lets the live-seat budget (checked before
   * leasing) ignore requests for nonexistent sessions so its refusal counter isn't inflatable.
   */
  fun isKnownSession(sessionId: String): Boolean = lock.withLock { sessions.containsKey(sessionId) }

  /**
   * Live-seat cost of [sessionId]'s daemon ([ServeSessionState.liveSeatWeight]), or 1 for an
   * unknown or not-yet-built session. Read before leasing.
   */
  fun liveSeatWeight(sessionId: String): Int = lock.withLock {
    sessions[sessionId]?.state?.liveSeatWeight ?: 1
  }

  /**
   * Milliseconds the whole server has been idle, or null while someone is being served. Drives the
   * theme optimizer's quiet gate.
   *
   * A connection lease answers busy only while its holder is active (within [leaseBusyMillis]);
   * otherwise one forgotten tab held the gate shut indefinitely (see #4312). It still keeps its
   * session resident. Request-scoped leases never age out, since that work may be a long foreground
   * render. Use [connectionIdleMillis] where any open connection must count.
   */
  fun idleMillis(now: Long = clock()): Long? = lock.withLock {
    if (sessions.values.any { it.isBusy(now) }) null else now - lastActivity
  }

  /**
   * [idleMillis] under the strict rule: any open lease is busy. Used by `--exit-when-idle`, since
   * exiting would drop a live socket.
   */
  fun connectionIdleMillis(now: Long = clock()): Long? = lock.withLock {
    if (sessions.values.any { it.leases > 0 }) null else now - lastActivity
  }

  /**
   * Whether this entry has a busy holder: any request lease, or a recently active connection lease.
   */
  private fun Entry.isBusy(now: Long): Boolean =
    requestLeases > 0 || (connectionLeases > 0 && now - lastLeaseActivity < leaseBusyMillis)

  /**
   * Sessions holding at least one open lease, sorted: exactly what keeps sessions resident and
   * makes [connectionIdleMillis] null. Published on `/status.json` so a leaked lease (e.g. from a
   * cancelled request) is attributable. A superset of [busyLeasedSessions].
   */
  fun leasedSessions(): List<String> = lock.withLock {
    sessions.entries.filter { it.value.leases > 0 }.map { it.key }.sorted()
  }

  /**
   * The subset of [leasedSessions] whose holder is currently busy (what makes [idleMillis] null).
   * Leased-but-not-busy is the idle-tab case.
   */
  fun busyLeasedSessions(now: Long = clock()): List<String> = lock.withLock {
    sessions.entries.filter { it.value.isBusy(now) }.map { it.key }.sorted()
  }

  /**
   * Suspend resident sessions idle past the timeout. Only detaching happens under the lock;
   * listener notification and host `close()` run after, with each entry marked [Entry.closing] so a
   * concurrent resume waits for the old daemon (see [liveHost]).
   */
  fun suspendIdle(): Int = suspendResident(idleTimeoutMillis, limit = Int.MAX_VALUE)

  /**
   * Suspend resident sessions now because the box is out of memory, least-recently-touched first,
   * without waiting out [idleTimeoutMillis]. Sheds one per sweep by default (suspending costs a
   * visitor a rebuild); [limit] raises it. Every [suspendIdle] guard still applies: pinned, leased,
   * streaming or background-active sessions are never shed.
   */
  fun shedUnderPressure(limit: Int = 1): Int =
    suspendResident(idleMillis = 0, limit = limit, leastRecentlyUsedFirst = true)

  /**
   * Shared sweep behind [suspendIdle] and [shedUnderPressure], kept as one body because the detach
   * (snapshot under the lock, the `closing` gate, and the outside-lock close that must always clear
   * it) is the delicate part.
   */
  private fun suspendResident(
    idleMillis: Long,
    limit: Int,
    leastRecentlyUsedFirst: Boolean = false,
  ): Int {
    if (limit <= 0) return 0
    val detached = lock.withLock {
      if (closed) return 0
      val now = clock()
      val detached = mutableListOf<Triple<String, Entry, ServeHost>>()
      val ordered =
        if (leastRecentlyUsedFirst) sessions.entries.sortedBy { it.value.lastAccess }
        else sessions.entries.toList()
      for ((id, entry) in ordered) {
        if (detached.size >= limit) break
        val host = entry.host ?: continue
        if (
          !entry.pinned &&
            entry.leases == 0 &&
            host.activeStreamCount() == 0 &&
            !host.backgroundWorkActive &&
            now - entry.lastAccess >= idleMillis
        ) {
          // Under the lock, BEFORE the detach: this and `entry.host = null` are one transition,
          // so no reader can catch the session detached with its snapshot not yet published.
          snapshots?.let { runCatching { it.capture(id, host) } }
          if (optimizerUnfinished(entry.state)) {
            runCatching { entry.state?.backgroundWork?.recordOptimizerHostSuspended() }
          }
          entry.host = null
          entry.startedAt = null
          entry.suspendedAt = now
          entry.closing = true
          detached += Triple(id, entry, host)
        }
      }
      detached
    }
    for ((id, entry, host) in detached) {
      try {
        suspendListeners.forEach { listener -> runCatching { listener(id, host) } }
        runCatching { host.close() }
      } finally {
        // Always clear the gate, even if a listener threw something runCatching doesn't hold —
        // a stuck `closing` flag would block this session's resume forever.
        lock.withLock {
          entry.closing = false
          closeFinished.signalAll()
        }
      }
    }
    return detached.size
  }

  /**
   * Ask every resident host to close daemons idle past the window, returning the count. Complements
   * [suspendIdle], which skips pinned sessions: their pooled daemons otherwise accumulate. Runs
   * outside the lock since closing can block.
   */
  fun releaseIdleDaemons(): Int {
    val window = if (daemonIdleMillis > 0) daemonIdleMillis else idleTimeoutMillis
    if (window <= 0) return 0
    val hosts = lock.withLock {
      if (closed) emptyList() else sessions.values.mapNotNull { it.host }
    }
    return hosts.sumOf { host -> runCatching { host.releaseIdleDaemons(window) }.getOrDefault(0) }
  }

  /**
   * Bring back the longest-parked catalog with unfinished optimization while a lane is free.
   * Progress survives in [ServeSessionState.catalogThemeCache], but restarting the pass relies on
   * visitor heartbeats ([ServeHost.keepLiveWarm]); this is the heartbeat for an unbrowsed box.
   *
   * Bounded by [ServeBackgroundWork.optimizerResumeSlots] (free lanes plus one challenger, so
   * parked catalogs reach the door instead of waiting for incumbents to finish), since a resume
   * costs a cold daemon and ~1 GB. Ordered by [Entry.suspendedAt]. Only on a quiet server.
   */
  fun resumeIdleOptimizers(): Int {
    val toWarm = lock.withLock {
      if (closed) return 0
      if (idleMillis() == null) return 0
      val candidates =
        sessions.values
          .filter { it.host == null && !it.closing && !it.opening && optimizerUnfinished(it.state) }
          // Longest-parked first — see [Entry.suspendedAt] for why this is not `lastAccess`.
          .sortedBy { it.suspendedAt ?: Long.MIN_VALUE }
      val resumed = mutableListOf<ServeHost>()
      // Read once: all catalogs share one [ServeBackgroundWork], and re-reading would let this loop
      // widen its own budget.
      var slots = candidates.firstOrNull()?.state?.backgroundWork?.optimizerResumeSlots() ?: 0
      for (entry in candidates) {
        if (slots <= 0) break
        val host = liveHost(entry) ?: continue
        // The session's own idle clock, not the server-wide `lastActivity` (which would make the
        // optimizer see the server as busy); buys the host one suspension window to win a lane.
        entry.lastAccess = clock()
        entry.suspendedAt = null
        runCatching { entry.state?.backgroundWork?.recordOptimizerHostResumed() }
        resumed += host
        slots--
      }
      resumed
    }
    // Outside the lock: `keepLiveWarm` re-enters the optimization pass, and a pass that starts
    // synchronously here would hold the registry lock across a daemon warm.
    toWarm.forEach { runCatching { it.keepLiveWarm() } }
    return toWarm.size
  }

  /**
   * Put one named catalog's optimizer back to work, reviving its host if suspended. The explicit
   * counterpart to [resumeIdleOptimizers]: marking renders dirty wakes nobody by itself. Not
   * bounded by resume slots (this is a request), but admission still applies once the pass runs.
   */
  fun wakeOptimizer(sessionId: String): Boolean {
    val host =
      lock.withLock {
        if (closed) return false
        val entry = sessions[sessionId] ?: return false
        if (entry.closing) return false
        // Buys the host one suspension window; see [resumeIdleOptimizers] for why `lastAccess`.
        entry.lastAccess = clock()
        if (entry.suspendedAt != null) {
          entry.suspendedAt = null
          runCatching { entry.state?.backgroundWork?.recordOptimizerHostResumed() }
        }
        liveHost(entry)
      } ?: return false
    // Outside the lock, for the reason [resumeIdleOptimizers] gives: the pass this re-enters can
    // warm a daemon, and holding the registry lock across that stalls every other session.
    runCatching { host.keepLiveWarm() }
    return true
  }

  /**
   * Whether this session is a catalog with optimization targets left, read from [ServeSessionState]
   * since the host may be gone. No cache or optimizer off (`total` 0) ⇒ not a candidate.
   */
  private fun optimizerUnfinished(state: ServeSessionState?): Boolean {
    val snapshot = state?.catalogThemeCache?.snapshot() ?: return false
    // `converged`, not `fullyOptimized`: warm entries from another build (or a requested
    // regenerate) still need work.
    return snapshot.total > 0 && !snapshot.converged
  }

  /**
   * Second-level reclaim: remove forked sessions (project-mode revisions with worktrees) suspended
   * past [suspendedGcTimeoutMillis], running [ServeSessionState.reclaim] to prune worktrees (see
   * #2022). Registered sessions are never removed; a reclaimed revision just rebuilds. Idle is
   * measured from [Entry.lastAccess]. Returns the count.
   */
  fun reclaimIdleForked(): Int = lock.withLock {
    if (closed || suspendedGcTimeoutMillis <= 0) return 0
    val now = clock()
    val stale = sessions.filterValues { entry ->
      entry.forked &&
        entry.host == null &&
        entry.leases == 0 &&
        now - entry.lastAccess >= suspendedGcTimeoutMillis
    }
    for ((id, entry) in stale) {
      sessions.remove(id)
      // Discard the snapshot too (under the same lock, like [unregister]), or one would leak per
      // reclaimed revision.
      snapshots?.let { runCatching { it.discard(id) } }
      runCatching { entry.state?.reclaim?.invoke() }
    }
    stale.size
  }

  /**
   * The resident host for [sessionId] without resuming, for read-only introspection that mustn't
   * wake daemons. Null means not resident, not "no metadata"; keep a snapshot via
   * [addSuspendListener] for suspended sessions.
   */
  fun peekHost(sessionId: String): ServeHost? = lock.withLock { sessions[sessionId]?.host }

  /**
   * The retained state for [sessionId] without resuming; null when unregistered. Unlike [peekHost],
   * distinguishes "no such catalog" from "idle", and reaches durable state like `catalogThemeCache`
   * while the daemon sleeps.
   */
  fun peekState(sessionId: String): ServeSessionState? = lock.withLock {
    sessions[sessionId]?.state
  }

  /** Total known sessions (resident + suspended). */
  fun activeCount(): Int = lock.withLock { sessions.size }

  /** Stable snapshot of every registered session id, without opening or resuming any session. */
  fun knownSessionIds(): List<String> = lock.withLock { sessions.keys.sorted() }

  /**
   * Any registered session id, or null; the module-less server's last-resort landing session (order
   * isn't guaranteed).
   */
  fun anySessionId(): String? = lock.withLock { sessions.keys.firstOrNull() }

  /** Sessions with a live daemon right now (resident, not suspended). */
  fun residentCount(): Int = lock.withLock { sessions.values.count { it.host != null } }

  override fun close() {
    val hosts = lock.withLock {
      if (closed) return
      closed = true
      // Release anyone parked on a mid-suspension close; they re-check `closed` and give up.
      closeFinished.signalAll()
      sessions.values.mapNotNull { it.host }.also { sessions.clear() }
    }
    reaper?.shutdownNow()
    hosts.forEach { runCatching { it.close() } }
  }

  /**
   * Existing entry, or one forked via [factory]; caller holds [lock]. Applies [register]'s
   * reserved-name guard, since forked refs never pass through [register].
   */
  private fun entryFor(sessionId: String): Entry? {
    sessions[sessionId]?.let {
      return it
    }
    if (sessionId in ServeSites.RESERVED_SYSTEMS) return null
    // The lock is held across the build so racing first-callers can't build twice; correctness
    // beats build concurrency for few tenants.
    val state = factory.create(sessionId) ?: return null
    // forked = true: built on demand (a git worktree on disk), so it's GC-eligible once long idle.
    return Entry(state, host = null, pinned = false, forked = true, lastAccess = clock()).also {
      sessions[sessionId] = it
    }
  }

  /**
   * The entry's live host, resuming from its state if suspended; caller holds [lock].
   *
   * Waits out an in-progress close ([Entry.closing]) so two daemons never coexist for one session
   * (`await` releases the lock). [open] itself runs with the lock released, because reopening can
   * take seconds to a minute and would stall every other session; [Entry.opening] keeps one opener
   * per session. If the entry was retired, replaced or the registry closed meanwhile, the new host
   * is closed and null returned.
   */
  private fun liveHost(entry: Entry): ServeHost? {
    // `!closed` first: [close] signals on its way out, and a waiter that re-parked because an
    // opener still held the flag would otherwise stay parked until that (possibly stuck) open ends.
    while (!closed && (entry.closing || entry.opening)) closeFinished.awaitUninterruptibly()
    // The registry may have been closed while we were parked; don't resurrect a daemon into it.
    if (closed) return null
    // …nor into an entry retired or replaced while we were parked. Every caller queued behind a
    // slow open wakes here, and each would otherwise launch (and then discard) a daemon of its own.
    if (sessions.values.none { it === entry }) return null
    entry.host?.let {
      return it
    }
    val state = entry.state ?: return null
    entry.opening = true
    // Every hold this thread has, not one: a caller may have re-entered the lock (e.g.
    // `resumeIdleOptimizers` → `idleMillis`), and a single unlock would keep it held.
    val holds = lock.holdCount
    val resumed =
      try {
        repeat(holds) { lock.unlock() }
        try {
          open(state)
        } finally {
          repeat(holds) { lock.lock() }
        }
      } finally {
        entry.opening = false
        closeFinished.signalAll()
      }
    if (resumed == null) return null
    if (closed || sessions.values.none { it === entry }) {
      repeat(holds) { lock.unlock() }
      try {
        runCatching { resumed.close() }
      } finally {
        repeat(holds) { lock.lock() }
      }
      return null
    }
    entry.host = resumed
    entry.startedAt = clock()
    return resumed
  }

  /**
   * Every resident session for `/status`, id-sorted. Reads cheap host getters under the lock.
   * Filter on [RunningDaemon.hasLiveStream] for live daemons only.
   */
  fun runningDaemons(): List<RunningDaemon> = lock.withLock {
    sessions
      .mapNotNull { (id, entry) ->
        val host = entry.host ?: return@mapNotNull null
        // A registered-but-never-woken catalog has a host but no subprocess, so it isn't reported.
        if (!host.daemonStarted) return@mapNotNull null
        RunningDaemon(
          id = id,
          label = host.label,
          pinned = entry.pinned,
          hasLiveStream = host.hasLiveStream,
          liveSeatWeight = entry.state?.liveSeatWeight ?: 1,
          activeStreams = runCatching { host.activeStreamCount() }.getOrDefault(0),
          leases = entry.leases,
          startedAt = entry.startedAt,
          renderStats = runCatching { host.renderPerfStats() }.getOrNull(),
          daemonPools = runCatching { host.daemonPoolStats() }.getOrDefault(emptyList()),
        )
      }
      .sortedBy { it.id }
  }

  internal companion object {
    /**
     * Default idle window before a resident session's daemon is suspended. Public because the
     * page's presence heartbeat ([ServeWeb.PRESENCE_INTERVAL_SECONDS]) must fit inside it with room
     * for a dropped ping.
     */
    const val DEFAULT_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000L

    /**
     * Default window before a forked suspended session is removed (an hour), well past the suspend
     * window.
     */
    const val DEFAULT_SUSPENDED_GC_TIMEOUT_MILLIS = 60 * 60 * 1000L

    /**
     * Default idle window before a pooled daemon closes (a minute): replicas reopen cheaply, while
     * suspended sessions cost a rebuild.
     */
    const val DEFAULT_DAEMON_IDLE_MILLIS = 60 * 1000L

    /**
     * Default quiet window before an open lease stops counting as busy on [idleMillis]. Short
     * because a background pass under a visitor costs only one render, and it must be below the
     * optimizer's 60s entry window or a held lease would keep the gate shut. Also well below the
     * 240s presence heartbeat, so a tab's keepalive can't keep it permanently active.
     */
    const val DEFAULT_LEASE_BUSY_MILLIS = 30 * 1000L
  }
}
