package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A lazy pool of identical monolithic catalog daemons used only by leased theme-render batches.
 * Slot zero is the catalog's shared daemon, still the sole lane for browsing, knobs, streams and
 * unleased renders; overlapping leased requests borrow it first, then lazily open up to [capacity]
 * - 1 replicas from the same launch descriptor. The primary is owned by [ServeCatalogLiveHost];
 *   this pool owns only replicas.
 */
class ServeSharedDaemonPool(
  private val primary: ServeHost,
  val capacity: Int = DEFAULT_CAPACITY,
  private val clock: () -> Long = System::currentTimeMillis,
  /**
   * Box-wide daemon budget ([LiveSeatLimiter]): a replica holds [seatWeight] permits while open, so
   * burst width is bounded by what the box can afford, not just per-catalog [capacity]. Null leaves
   * it unbudgeted.
   *
   * A visitor's burst is charged as foreground ([LiveSeatLimiter.acquire]): someone is waiting, and
   * replicas are reaped once idle ([reapIdle]). The idle theme optimizer uses the same leased path
   * but is background residency that doesn't end, so it passes `background = true` to [render] and
   * takes the background remainder. Never the per-preview slice.
   */
  private val liveSeats: LiveSeatLimiter? = null,
  private val seatWeight: () -> Int = { 1 },
  private val openReplica: () -> ServeHost,
) : AutoCloseable {
  private val lock = ReentrantLock()
  private val permits = Semaphore(capacity, true)
  private val available = ArrayDeque<ServeHost>().apply { add(primary) }
  private val hostReturned = lock.newCondition()
  private val seatTickets = mutableMapOf<ServeHost, LiveSeatLimiter.Ticket>()
  private val replicas = mutableListOf<ServeHost>()
  // Wall-clock of the last render each replica finished, for [reapIdle]. The primary isn't tracked:
  // it belongs to the catalog host and this pool never closes it.
  private val replicaLastUsed = mutableMapOf<ServeHost, Long>()
  // Concurrent borrows and their high-water mark: the number of daemons actually rendering at once,
  // not jobs submitted. See [takePeakInFlight].
  private val inFlight = AtomicInteger(0)
  private val peakInFlight = AtomicInteger(0)
  // Replicas opened but not yet rendered, and the longest first render since last taken; a
  // replica's first render carries its full cold start. See [takeColdStartMillis].
  private val coldReplicas = mutableSetOf<ServeHost>() // guarded by [lock]
  private val peakColdStartMillis = AtomicLong(0)
  // Replicas whose seat was taken on the FOREGROUND budget, i.e. opened by a visitor's burst. A
  // prefetch that reuses one must not quietly inherit that pricing — see [render].
  private val foregroundSeated = mutableSetOf<ServeHost>() // guarded by [lock]
  private var closed = false

  init {
    require(capacity >= 1) { "capacity must be >= 1, got $capacity" }
  }

  /**
   * Peak concurrent borrows since the last call, resetting the mark. Without a replica, N jobs can
   * take turns on one daemon, which counting jobs can't reveal; read-and-reset catches the peak
   * within a batch. Pool-wide, so it can only overstate width; a narrow reading is trustworthy.
   */
  fun takePeakInFlight(): Int = peakInFlight.getAndSet(inFlight.get())

  /**
   * Longest replica cold start since the last call, resetting the mark, so the caller can attribute
   * it to warm-up rather than per-entry render cost. The longest, not the sum, since cold starts
   * overlap within a batch. A replica stays cold until a render enters its session (a `NotFound` or
   * throw doesn't).
   */
  fun takeColdStartMillis(): Long = peakColdStartMillis.getAndSet(0)

  /**
   * [background] prices any replica this render opens against the background remainder
   * ([LiveSeatLimiter.acquireBackground]), leaving [LiveSeatLimiter.STREAM_RESERVE] free.
   * Foreground suits a visitor's burst; the endless idle optimizer must use background, or it holds
   * the stream reserve for hours.
   */
  fun render(
    previewId: String,
    overrides: PreviewOverrides,
    background: Boolean = false,
  ): RenderOutcome {
    permits.acquire()
    var borrowed: ServeHost? = null
    // An explicit flag, not a `coldStartFrom > 0` sentinel: [clock] is injectable and a test clock
    // legitimately starts at 0, which would silently disable the measurement.
    var cold = false
    var coldStartFrom = 0L
    var startedDaemon = false
    // Whether this borrow may extend the replica's life — see the repricing block below.
    var refreshLastUsed = true
    try {
      borrowed = lock.withLock {
        check(!closed) { "shared daemon pool is closed" }
        val host =
          if (background && !primary.daemonStarted && capacity > 1) {
            // Background work never borrows the primary, so optimizer slices don't keep every
            // catalog's primary resident; replicas are reaped after the burst.
            available.firstOrNull { it !== primary }?.also { available.remove(it) }
              ?: if (replicas.size < capacity - 1) {
                openSeatedReplica(background = true, avoidPrimaryFallback = replicas.isNotEmpty())
              } else {
                awaitAvailableReplica()
              }
          } else {
            available.removeFirstOrNull() ?: openSeatedReplica(background)
          }
        // Claimed under the lock so exactly one borrow times the cold start.
        cold = coldReplicas.remove(host)
        if (cold) coldStartFrom = clock()
        // Reprice a visitor-opened (foreground) replica that prefetch is now reusing; otherwise a
        // continuously running optimizer keeps it alive inside the stream reserve.
        if (background && liveSeats != null && host in foregroundSeated) {
          val repriced = liveSeats.acquireBackground(seatWeight(), dedicatedSlice = false)
          if (repriced != null) {
            seatTickets.put(host, repriced)?.close()
            foregroundSeated -= host
          } else {
            // No background headroom: serve the render but don't refresh its last-used time, so the
            // idle sweep returns the foreground seat after the burst.
            refreshLastUsed = false
          }
        }
        host
      }
      peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
      return borrowed.render(previewId, overrides).also {
        // `ServeRenderHost.render` answers NotFound before starting its session, so the replica is
        // still cold; only a real render consumes the marker.
        startedDaemon = it !is RenderOutcome.NotFound
      }
    } finally {
      if (cold && startedDaemon) {
        peakColdStartMillis.accumulateAndGet(clock() - coldStartFrom, ::maxOf)
      }
      borrowed?.let { host ->
        inFlight.decrementAndGet()
        lock.withLock {
          if (!closed) {
            if (cold && !startedDaemon) coldReplicas += host
            available.addLast(host)
            if (refreshLastUsed) replicaLastUsed[host] = clock()
            hostReturned.signalAll()
          }
        }
      }
      permits.release()
    }
  }

  /**
   * Open one replica charged to the seat budget; caller holds [lock]. When the budget is exhausted
   * the pool neither spawns nor fails: it waits for one of its in-flight borrows (the primary is
   * always in circulation), narrowing the batch. [background] takes the background remainder; see
   * [render].
   */
  private fun openSeatedReplica(
    background: Boolean,
    avoidPrimaryFallback: Boolean = false,
  ): ServeHost {
    var ticket: LiveSeatLimiter.Ticket? = null
    if (liveSeats != null) {
      // `countRefusal = false`: a miss only narrows the burst, so counting it would misreport
      // visitors as refused in the metric budget decisions rely on. `dedicatedSlice = false`: that
      // slice belongs to supplement-only previews.
      ticket =
        if (background) liveSeats.acquireBackground(seatWeight(), dedicatedSlice = false)
        else liveSeats.acquire(seatWeight(), countRefusal = false)
      if (ticket == null) {
        if (avoidPrimaryFallback) return awaitAvailableReplica()
        while (available.isEmpty() && !closed) hostReturned.await()
        check(!closed) { "shared daemon pool is closed" }
        return available.removeFirst()
      }
    }
    // Release the seat if the launch throws; the ticket isn't tracked yet, so it would otherwise
    // leak permanently.
    val replica =
      try {
        openReplica()
      } catch (e: Throwable) {
        ticket?.close()
        throw e
      }
    replicas += replica
    // Not warm yet: its daemon session starts on the first render. [takeColdStartMillis].
    coldReplicas += replica
    if (!background && ticket != null) foregroundSeated += replica
    ticket?.let { seatTickets[replica] = it }
    return replica
  }

  /** Wait for a reapable replica without consuming the idle primary. Caller holds [lock]. */
  private fun awaitAvailableReplica(): ServeHost {
    while (available.none { it !== primary } && !closed) hostReturned.await()
    check(!closed) { "shared daemon pool is closed" }
    return available.first { it !== primary }.also { available.remove(it) }
  }

  /**
   * Width background prefetch may use without waking a cold primary; a visitor-warmed primary can
   * take part.
   */
  fun backgroundCapacity(): Int =
    if (!primary.daemonStarted && capacity > 1) capacity - 1 else capacity

  /**
   * Close every replica idle for [idleMillis], returning the count; never the primary. Needed
   * because catalog sessions are pinned, so nothing else closes replicas. Takes a permit per
   * replica (renders hold one per borrow), so it never closes a host mid-render; busy permits just
   * wait for the next sweep.
   */
  fun reapIdle(idleMillis: Long): Int {
    if (idleMillis <= 0) return 0
    var reaped = 0
    while (permits.tryAcquire()) {
      val victim = lock.withLock {
        if (closed) null
        else {
          val now = clock()
          available.firstOrNull { host ->
            host !== primary && now - (replicaLastUsed[host] ?: now) >= idleMillis
          }
        }
      }
      if (victim == null) {
        permits.release()
        break
      }
      lock.withLock {
        // Remove from every per-host collection, including `coldReplicas` (a replica reaped before
        // its first render), or closed hosts stay reachable indefinitely.
        available.remove(victim)
        replicas.remove(victim)
        replicaLastUsed.remove(victim)
        coldReplicas.remove(victim)
        foregroundSeated.remove(victim)
        seatTickets.remove(victim)?.close()
      }
      permits.release()
      runCatching { victim.close() }
      reaped++
    }
    return reaped
  }

  /** Actual replica subprocesses (the primary is counted separately by the composite host). */
  fun replicaProcessCount(): Int = lock.withLock { replicas.sumOf { it.daemonProcessCount } }

  fun renderPerfStats(): List<RenderPerfSnapshot> = lock.withLock {
    replicas.mapNotNull { it.renderPerfStats() }
  }

  fun snapshot(): DaemonPoolSnapshot = lock.withLock {
    DaemonPoolSnapshot(
      name = "shared-replicas",
      open = replicas.count { it.daemonProcessCount > 0 },
      maxOpen = capacity - 1,
      activeStreams = 0,
    )
  }

  override fun close() {
    val toClose = lock.withLock {
      if (closed) return
      closed = true
      available.clear()
      replicaLastUsed.clear()
      coldReplicas.clear()
      foregroundSeated.clear()
      seatTickets.values.forEach { it.close() }
      seatTickets.clear()
      // Wake anyone parked in [openSeatedReplica]; the `closed` check turns their wait into the
      // pool's ordinary closed-state error rather than a hang.
      hostReturned.signalAll()
      replicas.toList().also { replicas.clear() }
    }
    toClose.forEach { runCatching { it.close() } }
  }

  companion object {
    const val DEFAULT_CAPACITY = 5
  }
}
