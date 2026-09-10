package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.client.SandboxSparePool
import ee.schimke.composeai.daemon.client.SubprocessDaemonClientFactory
import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionBackend
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionFactory
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions

/**
 * The server's pool of pre-booted Android sandbox workers, and the [RenderSessionFactory] that
 * hands them to every catalog daemon this server spawns.
 *
 * A Robolectric sandbox does not depend on the catalog it renders, so `daemon-client`'s
 * [SandboxSparePool] boots generic workers ahead of demand — keyed by the daemon's overlay
 * signature — and `SubprocessDaemonClientFactory` puts their ports on each Android launch
 * descriptor. The daemon adopts them instead of booting (`initialize` in well under a second
 * instead of 4-9 s), and hands them back warm when it is reaped, so the next daemon of that catalog
 * adopts the same sandboxes. Desktop daemons and single-sandbox daemons are unaffected: the factory
 * reserves spares only for an Android launch whose sandbox pool has more than one slot.
 *
 * **Ownership.** One per server, built by [ServeRunner] before any catalog loads and closed with
 * the server's other closeables — the pool's JVMs are children of this process and die with it
 * either way, but a clean close is what lets a reap-in-progress return its worker rather than leak
 * it. It is *not* charged to the live-seat budget: a spare is capacity held in reserve for the
 * daemons the budget admits, sized separately by [ServeOptions.spareSandboxes] because it is a
 * memory decision (~450-500 MB resident per warm spare) rather than a concurrency one.
 *
 * The session factory is the seam `ServeRenderHost.open` already has; nothing about how a daemon is
 * materialized, initialized or fronted changes, only which `DaemonClientFactory` forks the JVM.
 */
internal class ServeSpareSandboxes private constructor(private val pool: SandboxSparePool) :
  AutoCloseable {

  /** Opens subprocess sessions whose Android daemons take their sandbox workers from [pool]. */
  val sessions: RenderSessionFactory =
    object : RenderSessionFactory {
      override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

      override fun open(config: RenderSessionConfig): RenderSession =
        SubprocessRenderSessions.open(config, factory = SubprocessDaemonClientFactory(pool))
    }

  fun snapshot(): SandboxSparePool.Snapshot = pool.snapshot()

  override fun close() = pool.close()

  companion object {
    /**
     * A pool for a budget of [maxSpares] warm workers, or `null` when there is nothing to keep —
     * `maxSpares <= 0` (the default: a local `serve` boots its daemons as before), or a daemon
     * sandbox count that leaves no worker slot to adopt into.
     */
    fun forBudget(
      maxSpares: Int,
      sandboxCount: Int = daemonSandboxCount(),
      log: (String) -> Unit = { System.err.println("[serve spares] $it") },
    ): ServeSpareSandboxes? {
      val config = config(maxSpares, sandboxCount) ?: return null
      log(
        "keeping up to ${config.maxSpares} warm Android sandbox worker(s), " +
          "${config.perSignature} per daemon classpath"
      )
      return ServeSpareSandboxes(SandboxSparePool(config, log = log))
    }

    /**
     * The pool's shape for a budget: each daemon adopts `sandboxCount - 1` workers (its own
     * in-process sandbox fills the remaining slot), so that is what one signature keeps warm,
     * capped by the budget. Pure; the test reads it.
     */
    internal fun config(maxSpares: Int, sandboxCount: Int): SandboxSparePool.Config? {
      if (maxSpares <= 0) return null
      val perDaemon = sandboxCount - 1
      if (perDaemon <= 0) return null
      return SandboxSparePool.Config(
        maxSpares = maxSpares,
        perSignature = minOf(perDaemon, maxSpares),
      )
    }

    /**
     * The sandbox pool size every Android daemon on this box gets: the same `composeai.daemon.*`
     * property the daemon JVMs inherit (the image sets it in `JAVA_TOOL_OPTIONS`), else the
     * daemon's own default. `SubprocessDaemonClientFactory` reads the descriptor first and falls
     * back to this too, so the two agree.
     */
    internal fun daemonSandboxCount(): Int =
      System.getProperty(DaemonProperties.Names.SANDBOX_COUNT)?.toIntOrNull()?.coerceAtLeast(1)
        ?: DEFAULT_DAEMON_SANDBOX_COUNT

    /** `DaemonMain`'s default pool size (warm-spare mode), mirrored by `daemon-client`. */
    private const val DEFAULT_DAEMON_SANDBOX_COUNT = 5
  }
}
