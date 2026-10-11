package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeSharedDaemonPoolTest {
  private class LazyHost(private val name: String) : ServeHost {
    override val previews: List<ServePreview> = emptyList()
    override val label: String = name
    private var started = false
    override val daemonProcessCount: Int
      get() = if (started) 1 else 0

    override val daemonStarted: Boolean
      get() = started

    var closed = false

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      started = true
      return RenderOutcome.Ok(name.encodeToByteArray())
    }

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? = null

    override fun activeStreamCount(): Int = 0

    override fun close() {
      closed = true
    }
  }

  private class BlockingHost(
    private val name: String,
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
  ) : ServeHost {
    override val previews: List<ServePreview> = emptyList()
    override val label: String = name
    override val daemonProcessCount: Int = 1
    var closed = false

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      entered.countDown()
      assertTrue(release.await(5, TimeUnit.SECONDS), "timed out waiting to release $name")
      return RenderOutcome.Ok(name.encodeToByteArray())
    }

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? = null

    override fun activeStreamCount(): Int = 0

    override fun close() {
      closed = true
    }
  }

  @Test
  fun `replicas use distinct output roots and remove them when closed`() {
    val descriptorDir = java.nio.file.Files.createTempDirectory("serve-replica-descriptor").toFile()
    val descriptor = File(descriptorDir, "daemon-launch.json").apply { writeText("unused") }
    val outputRoots = mutableListOf<File>()
    val delegates = mutableListOf<BlockingHost>()
    val entered = CountDownLatch(0)
    val release = CountDownLatch(0)

    fun openReplica(): ServeHost =
      openIsolatedSharedDaemonReplica(descriptor) { properties ->
        outputRoots += File(properties.getValue("composeai.render.outputDir")).parentFile
        BlockingHost("replica", entered, release).also(delegates::add)
      }

    val first = openReplica()
    val second = openReplica()
    assertEquals(2, outputRoots.distinct().size)
    assertTrue(outputRoots.all { it.isDirectory })

    first.close()
    second.close()
    assertTrue(delegates.all { it.closed })
    assertTrue(outputRoots.none { it.exists() })
    descriptorDir.deleteRecursively()
    assertFalse(descriptorDir.exists())
  }

  @Test
  fun `five overlapping leased renders use five shared daemon instances`() {
    val entered = CountDownLatch(5)
    val release = CountDownLatch(1)
    val opened = AtomicInteger()
    val replicas = Collections.synchronizedList(mutableListOf<BlockingHost>())
    val primary = BlockingHost("primary", entered, release)
    val pool =
      ServeSharedDaemonPool(primary = primary) {
        BlockingHost("replica-${opened.incrementAndGet()}", entered, release).also(replicas::add)
      }
    val executor = Executors.newFixedThreadPool(5)

    try {
      val results =
        (0 until 5).map { i ->
          executor.submit<RenderOutcome> { pool.render("preview-$i", PreviewOverrides()) }
        }

      assertTrue(entered.await(5, TimeUnit.SECONDS), "all five daemon renders should overlap")
      assertEquals(4, opened.get())
      assertEquals(5, 1 + pool.replicaProcessCount())
      assertEquals(DaemonPoolSnapshot("shared-replicas", 4, 4, 0), pool.snapshot())

      release.countDown()
      results.forEach { assertTrue(it.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok) }
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
    }

    assertEquals(4, replicas.count { it.closed })
    assertEquals(false, primary.closed, "the composite owns the primary daemon")
  }

  @Test
  fun `background optimization leaves a cold primary idle and reaps its replica`() {
    var now = 0L
    val primary = LazyHost("primary")
    val replicas = mutableListOf<LazyHost>()
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 2, clock = { now }) {
        LazyHost("replica").also(replicas::add)
      }
    try {
      assertEquals(1, pool.backgroundCapacity())
      assertTrue(pool.render("preview", PreviewOverrides(), background = true) is RenderOutcome.Ok)
      assertEquals(0, primary.daemonProcessCount, "prefetch must not make the primary resident")
      assertEquals(1, pool.replicaProcessCount())

      now = ServeSessionRegistry.DEFAULT_DAEMON_IDLE_MILLIS
      assertEquals(1, pool.reapIdle(ServeSessionRegistry.DEFAULT_DAEMON_IDLE_MILLIS))
      assertTrue(replicas.single().closed)
      assertEquals(0, pool.replicaProcessCount(), "quiet time returns the optimizer RAM")
    } finally {
      pool.close()
    }
    assertFalse(primary.closed, "the composite still owns the untouched primary")
  }

  /**
   * The reported batch width must count daemons actually rendering, not jobs submitted: without
   * seat budget for a replica, five jobs take turns on one daemon.
   */
  @Test
  fun `peak in-flight counts daemons that rendered at once, not jobs submitted`() {
    // Budget fully spent, so every job must share the primary — see the affordability test below.
    val seats = LiveSeatLimiter(totalPermits = 1)
    val held = requireNotNull(seats.acquire(1))
    val opened = AtomicInteger()
    val primary = InstantHost("primary")
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 5, liveSeats = seats) {
        opened.incrementAndGet()
        InstantHost("replica")
      }
    val executor = Executors.newFixedThreadPool(5)
    try {
      assertEquals(0, pool.takePeakInFlight(), "nothing borrowed yet")

      (1..5)
        .map { executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) } }
        .forEach { assertTrue(it.get(10, TimeUnit.SECONDS) is RenderOutcome.Ok) }

      assertEquals(0, opened.get(), "precondition: the budget afforded no replica")
      // Five jobs, one daemon. The old job-count reading would have said 5.
      assertEquals(1, pool.takePeakInFlight(), "five jobs served by one daemon is width 1")
      assertEquals(0, pool.takePeakInFlight(), "and the mark resets, so the next batch is its own")
    } finally {
      executor.shutdownNow()
      pool.close()
      held.close()
    }
  }

  /**
   * A replica's daemon session starts on its first render, carrying the full cold start; that must
   * be reported as warm time, not lumped into per-entry render time.
   */
  @Test
  fun `a replica's first render is reported as cold-start time, and only the first`() {
    var now = 0L
    val slowFirstRender = mutableSetOf<String>()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    // Primary held mid-render, so the second request must open a replica.
    val primary = BlockingHost("primary", entered, release)
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 2, clock = { now }) {
        object : ServeHost by InstantHost("replica") {
          override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
            // A cold start on the first render only, exactly as a real daemon session behaves.
            if (slowFirstRender.add("replica")) now += 40_000
            return RenderOutcome.Ok("replica".encodeToByteArray())
          }
        }
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      assertEquals(0L, pool.takeColdStartMillis(), "nothing opened yet")

      val held = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "primary is mid-render")
      assertTrue(
        executor
          .submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
          .get(10, TimeUnit.SECONDS) is RenderOutcome.Ok
      )

      assertEquals(40_000L, pool.takeColdStartMillis(), "the replica's first render was a cold one")
      assertEquals(0L, pool.takeColdStartMillis(), "and the mark resets")

      // Reuse: the daemon is warm now, so nothing more is charged to cold start.
      assertTrue(pool.render("p", PreviewOverrides()) is RenderOutcome.Ok)
      assertEquals(0L, pool.takeColdStartMillis(), "a warm reuse is not a cold start")

      release.countDown()
      assertTrue(held.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok)
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
    }
  }

  /**
   * `ServeRenderHost.render` answers `NotFound` from its id set before touching its lazy session,
   * so consuming the cold marker there would mark a never-started daemon warm.
   */
  @Test
  fun `a NotFound leaves the replica cold, so the real cold start is still reported`() {
    var now = 0L
    var started = false
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val primary = BlockingHost("primary", entered, release)
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 2, clock = { now }) {
        object : ServeHost by InstantHost("replica") {
          override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
            // An id this host doesn't carry never reaches the session — no cold start is paid.
            if (previewId == "absent") return RenderOutcome.NotFound
            if (!started) {
              started = true
              now += 40_000
            }
            return RenderOutcome.Ok("replica".encodeToByteArray())
          }
        }
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val held = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "primary is mid-render")

      // Opens the replica, but asks for an id it doesn't have.
      assertEquals(
        RenderOutcome.NotFound,
        executor
          .submit<RenderOutcome> { pool.render("absent", PreviewOverrides()) }
          .get(10, TimeUnit.SECONDS),
      )
      assertEquals(0L, pool.takeColdStartMillis(), "no session was entered, so no cold start yet")

      // The next render on that same replica is the one that actually starts the daemon.
      assertTrue(
        executor
          .submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
          .get(10, TimeUnit.SECONDS) is RenderOutcome.Ok
      )
      assertEquals(
        40_000L,
        pool.takeColdStartMillis(),
        "and it is reported, not lost to batch time",
      )

      release.countDown()
      assertTrue(held.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok)
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
    }
  }

  /**
   * The stream reserve guarantees a visitor can always start a stream. A leased browse burst may
   * take it (someone is waiting); the idle optimizer, also leased, is background residency that
   * doesn't end, so it must not.
   */
  @Test
  fun `a background render may not open a replica inside the stream reserve`() {
    // Exactly the reserve free: foreground may take it, background may not.
    val seats = LiveSeatLimiter(totalPermits = LiveSeatLimiter.STREAM_RESERVE)
    val opened = AtomicInteger()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val primary = BlockingHost("primary", entered, release)
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 2, liveSeats = seats) {
        opened.incrementAndGet()
        InstantHost("replica")
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val held = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "primary is mid-render")

      // Prefetch overlapping the busy primary: it must NOT spawn into the reserve, so it waits.
      val prefetch =
        executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides(), background = true) }
      assertTrue(
        runCatching { prefetch.get(1, TimeUnit.SECONDS) }.isFailure,
        "the prefetch waits for the primary rather than taking the stream reserve",
      )
      assertEquals(0, opened.get(), "no replica opened against the reserve")

      release.countDown()
      assertTrue(held.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok)
      // It still gets served — narrowing the lane, never failing the render.
      assertTrue(prefetch.get(10, TimeUnit.SECONDS) is RenderOutcome.Ok)
      assertEquals(
        LiveSeatLimiter.STREAM_RESERVE,
        seats.availablePermits(),
        "the reserve is intact, so a stream could still start",
      )
      assertEquals(0L, seats.refusalCount(), "and narrowing is not a refused visitor")
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
    }
  }

  /**
   * A visitor's burst leaves a foreground-priced replica; a running optimizer reusing it would keep
   * it alive in the stream reserve, so reuse reprices or leaves it to expire.
   */
  @Test
  fun `a prefetch cannot extend the life of a replica it could not reprice`() {
    var now = 0L
    // One seat above the reserve: no background headroom to move the replica onto, so the reprice
    // fails and the prefetch must not extend the replica's life on a foreground seat.
    val seats = LiveSeatLimiter(totalPermits = 1 + LiveSeatLimiter.STREAM_RESERVE)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val primary = BlockingHost("primary", entered, release)
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 2, clock = { now }, liveSeats = seats) {
        InstantHost("replica")
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      // A visitor's burst opens the replica on the FOREGROUND budget, at t=0.
      val held = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "primary is mid-render")
      assertTrue(
        executor
          .submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
          .get(10, TimeUnit.SECONDS) is RenderOutcome.Ok
      )
      release.countDown()
      assertTrue(held.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok)
      assertEquals(1, pool.replicaProcessCount(), "the burst left a replica behind")
      assertEquals(
        LiveSeatLimiter.STREAM_RESERVE,
        seats.availablePermits(),
        "precondition: only the reserve is left, so there is nowhere to reprice to",
      )

      // The optimizer reuses it a second later. It still gets its render …
      now = 1_000
      assertTrue(pool.render("p", PreviewOverrides(), background = true) is RenderOutcome.Ok)

      // The replica's clock still reads from the visitor's use: this only passes if the prefetch
      // left `replicaLastUsed` alone.
      now = 60_500
      assertEquals(1, pool.reapIdle(idleMillis = 60_000), "the prefetch did not buy it more time")
      assertEquals(
        1 + LiveSeatLimiter.STREAM_RESERVE,
        seats.availablePermits(),
        "and the foreground seat is back",
      )
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
    }
  }

  /**
   * `acquireBackground` tries the per-preview slice first; a shared prefetch replica must not take
   * that seat, which is reserved for supplement-only previews.
   */
  @Test
  fun `a background replica never takes the per-preview slice`() {
    // A one-seat slice plus a general lane just wide enough for one background holder (weight +
    // STREAM_RESERVE = 3), so the two outcomes diverge.
    val seats = LiveSeatLimiter(totalPermits = 4, perPreviewReserve = 1)
    assertEquals(1, seats.perPreviewPermits, "precondition: the box affords a one-seat slice")

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val primary = BlockingHost("primary", entered, release)
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 2, liveSeats = seats) {
        InstantHost("replica")
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val held = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "primary is mid-render")
      // A background replica: the general lane has room, and it must be taken from there.
      assertTrue(
        executor
          .submit<RenderOutcome> { pool.render("p", PreviewOverrides(), background = true) }
          .get(10, TimeUnit.SECONDS) is RenderOutcome.Ok
      )

      // With the general lane full, the slice is all that stands between a supplement-only preview
      // and a 503.
      assertNotNull(seats.acquire(2), "the general lane still had room for a stream")
      assertNotNull(
        seats.acquireBackground(1),
        "the per-preview lane still has the seat carved out for it",
      )

      release.countDown()
      assertTrue(held.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok)
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
    }
  }

  /** The other direction: when replicas ARE affordable, the peak sees them. */
  @Test
  fun `peak in-flight rises with genuinely concurrent renders`() {
    val entered = CountDownLatch(3)
    val release = CountDownLatch(1)
    val primary = BlockingHost("primary", entered, release)
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 3) {
        BlockingHost("replica", entered, release)
      }
    val executor = Executors.newFixedThreadPool(3)
    try {
      val results =
        (1..3).map { executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) } }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "all three should be rendering at once")

      release.countDown()
      results.forEach { assertTrue(it.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok) }
      assertEquals(3, pool.takePeakInFlight(), "three daemons really did render concurrently")
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
    }
  }

  /** A trivially-completing host, for the tests that don't need to hold a render open. */
  private class InstantHost(private val name: String) : ServeHost {
    override val previews: List<ServePreview> = emptyList()
    override val label: String = name
    override val daemonProcessCount: Int = 1
    var closed = false

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
      RenderOutcome.Ok(name.encodeToByteArray())

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? = null

    override fun activeStreamCount(): Int = 0

    override fun close() {
      closed = true
    }
  }

  /**
   * Replicas outlive their burst unless reaped; catalog sessions are pinned, so
   * [ServeSessionRegistry.suspendIdle] won't.
   */
  @Test
  fun `reaps idle replicas and never the primary`() {
    var now = 0L
    val primary = InstantHost("primary")
    val replicas = Collections.synchronizedList(mutableListOf<InstantHost>())
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 3, clock = { now }) {
        InstantHost("replica-${replicas.size}").also { replicas.add(it) }
      }
    try {
      // Overlapping renders are what open replicas at all; a sequential batch stays on the primary.
      val executor = Executors.newFixedThreadPool(3)
      try {
        (1..3)
          .map { executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) } }
          .forEach { it.get(5, TimeUnit.SECONDS) }
      } finally {
        executor.shutdownNow()
      }
      // Whether the race actually opened replicas is timing-dependent; the reap assertions below
      // are written against however many it opened, and the primary must survive either way.

      now = 60_000
      val reaped = pool.reapIdle(idleMillis = 30_000)
      assertEquals(replicas.size, reaped, "every idle replica is closed")
      assertTrue(replicas.all { it.closed })
      assertFalse(primary.closed, "the primary belongs to the catalog host")
      assertEquals(0, pool.replicaProcessCount())

      // The pool still works afterwards, reopening on demand.
      assertTrue(pool.render("p", PreviewOverrides()) is RenderOutcome.Ok)
    } finally {
      pool.close()
    }
  }

  @Test
  fun `does not open a replica the live-seat budget cannot afford`() {
    // Budget fully spent elsewhere; since a leased replica is charged as foreground, "cannot
    // afford" must mean nothing free at all.
    val seats = LiveSeatLimiter(totalPermits = 1)
    val held = requireNotNull(seats.acquire(1))
    assertEquals(0, seats.availablePermits(), "precondition: nothing left to spend")

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    // Held mid-render so the overlapping request must take the replica-launch path (an instant
    // primary made this racy).
    val primary = BlockingHost("primary", entered, release)
    val opened = AtomicInteger()
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 3, liveSeats = seats) {
        opened.incrementAndGet()
        InstantHost("replica")
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val first = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "the primary should be mid-render")

      val second = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      // It must still be waiting: the only host is out and the budget affords no replica. Had one
      // been spawned this would have completed, so the timeout is the assertion.
      assertTrue(
        runCatching { second.get(1, TimeUnit.SECONDS) }.isFailure,
        "the overlapping render waits for the primary instead of spawning a replica",
      )
      assertEquals(0, opened.get(), "no replica was spawned against an exhausted budget")

      // Both still succeed — the second serialises onto the primary instead of spawning a JVM.
      release.countDown()
      assertTrue(first.get(10, TimeUnit.SECONDS) is RenderOutcome.Ok)
      assertTrue(second.get(10, TimeUnit.SECONDS) is RenderOutcome.Ok)
      assertEquals(0, opened.get(), "still no replica once the burst drains")
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
      held.close()
    }
  }

  @Test
  fun `a replica whose launch throws hands its seat back`() {
    val total = 4 + LiveSeatLimiter.STREAM_RESERVE
    val seats = LiveSeatLimiter(totalPermits = total)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    // The primary is held mid-render, so a concurrent request has nothing to borrow and must go
    // down the replica-launch path — which is the one that can strand a ticket.
    val primary = BlockingHost("primary", entered, release)
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 3, liveSeats = seats) {
        throw IllegalStateException("daemon launch failed")
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val holder = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "the primary should be mid-render")

      val failed = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      val thrown = runCatching { failed.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
      assertTrue(thrown != null, "the launch failure reaches the caller")

      assertEquals(total, seats.availablePermits(), "no seat is stranded on a failed launch")

      release.countDown()
      assertTrue(holder.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok)
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
    }
  }

  /**
   * A leased burst's replicas draw on the foreground budget, like streams; the per-preview pool
   * stays on the background path behind the stream reserve.
   */
  @Test
  fun `a leased replica may use the seats reserved against background work`() {
    // Exactly the stream reserve free: a background holder would be refused, a leased burst may
    // take it. `perPreviewReserve = 0` so the per-preview slice can't satisfy the background holder
    // and void the precondition (that case is LiveSeatLimiterPerPreviewReserveTest's).
    val seats =
      LiveSeatLimiter(totalPermits = LiveSeatLimiter.STREAM_RESERVE, perPreviewReserve = 0)
    assertNull(seats.acquireBackground(1), "precondition: background cannot touch the reserve")

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val primary = BlockingHost("primary", entered, release)
    val opened = AtomicInteger()
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 2, liveSeats = seats) {
        opened.incrementAndGet()
        InstantHost("replica")
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val held = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "primary is mid-render")

      // Overlapping leased render: the primary is out, so this must open a replica.
      val second = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(second.get(10, TimeUnit.SECONDS) is RenderOutcome.Ok)
      assertEquals(1, opened.get(), "the burst widened onto a replica")

      release.countDown()
      assertTrue(held.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok)
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
    }
  }

  /**
   * `liveSeatRefusals` must count only real refusals; a burst that can't widen still renders on an
   * existing host.
   */
  @Test
  fun `replica backpressure is not counted as a live-seat refusal`() {
    val seats = LiveSeatLimiter(totalPermits = 1)
    val held = requireNotNull(seats.acquire(1))
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val primary = BlockingHost("primary", entered, release)
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 2, liveSeats = seats) {
        InstantHost("replica")
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val first = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      // Wants to widen, cannot (budget spent elsewhere), so it waits for the primary instead.
      val second = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }

      release.countDown()
      assertTrue(first.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok)
      assertTrue(second.get(10, TimeUnit.SECONDS) is RenderOutcome.Ok, "the render still succeeded")
      assertEquals(0L, seats.refusalCount(), "narrowing a burst is not a refusal")
      assertEquals(0L, seats.unverifiedRefusalCount())
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
      held.close()
    }
  }
}
