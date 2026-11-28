package ee.schimke.composeai.cli.serve

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Where a session is. [holdsMemory] stages have a running process tree; the others cost nothing but
 * their bookkeeping.
 */
enum class SimulatorStage(
  val id: String,
  val step: Int,
  val holdsMemory: Boolean,
  val ended: Boolean = false,
) {
  /** Waiting for a boot slot or for memory: see [SimulatorSessions.Capacity]. */
  QUEUED("queued", 0, holdsMemory = false),
  STARTING("starting", 1, holdsMemory = true),
  CONVERTING("converting", 2, holdsMemory = true),
  BOOTING("booting", 3, holdsMemory = true),
  LIVE("live", 4, holdsMemory = true),
  /** Frozen (`SIGSTOP`): no CPU, memory kept, back to [LIVE] in ~40 ms on any input. */
  PAUSED("paused", 4, holdsMemory = true),
  /**
   * Its process ended to free memory for someone else, its activities' saved state kept: the next
   * input boots it again (through the queue) and restores that state.
   */
  HIBERNATED("hibernated", 4, holdsMemory = false),
  FAILED("failed", 4, holdsMemory = false, ended = true),
  CLOSED("closed", 4, holdsMemory = false, ended = true);

  val booting: Boolean
    get() = this == STARTING || this == CONVERTING || this == BOOTING

  companion object {
    const val STEPS = 4
  }
}

/**
 * The server's simulator sessions: one [SimulatorProcess] each — one simulator, or a paired phone
 * and watch — for one registered app in one [SimulatorMode].
 *
 * **Who may drive one** is a capability: [Session.secret], 32 random bytes held only by its
 * starter.
 *
 * **What one costs**, measured on the released CLI (`docs/design/SIMULATOR.md`): a phone or watch
 * is two JVMs (the CLI's, ~85 MB, and the simulator's, ~450 MB for a trivial app and around 1 GB
 * for a real Compose one), a paired session three; about 10% of a core idle, 20% while a viewer
 * pulls 4 frames a second, **nothing while paused**. A boot is the expensive moment: four at once
 * on four cores took 12.5 s each instead of 5 s. So the pool is shaped by three resources, not a
 * count:
 *
 * * **Boots** run [Capacity.maxConcurrentBoots] at a time; the rest wait as [SimulatorStage.QUEUED]
 *   with their position.
 * * **Memory** is admitted against [Capacity.memoryBudgetMb], each session charged its measured RSS
 *   once it has one, and before that the running average for its app and mode ([estimateMb]).
 * * **Idle time** is tiered. Nobody watching for [pauseHiddenMillis] (a hidden tab, a closed one),
 *   or nobody touching it for [pauseIdleMillis]: **paused**. Memory short for a new session: the
 *   least recently used paused session is **hibernated**. No input for [idleMillis], or past its
 *   deadline: **ended**.
 *
 * Fairness: an owner has at most [Capacity.maxPerOwner] sessions (a new one replaces their oldest),
 * an embedded app its own `maxConcurrentSessions`, and the queue holds [Capacity.maxQueued]. One
 * sweeper thread runs every [sweepMillis].
 */
class SimulatorSessions(
  private val registry: SimulatorAppRegistry,
  internal val runtime: SimulatorRuntime,
  private val clock: () -> Long = System::currentTimeMillis,
  val capacity: Capacity = Capacity(),
  private val idleMillis: Long = TimeUnit.MINUTES.toMillis(10),
  private val pauseIdleMillis: Long = TimeUnit.MINUTES.toMillis(2),
  private val pauseHiddenMillis: Long = TimeUnit.SECONDS.toMillis(10),
  private val detachedGraceMillis: Long = TimeUnit.SECONDS.toMillis(60),
  /** How long a session may take to its first frame: a first conversion is minutes. */
  private val bootTimeoutMillis: Long = TimeUnit.MINUTES.toMillis(10),
  /** Frames are taken at most this often per device right after input, however many sockets ask. */
  internal val frameIntervalMillis: Long = 100,
  private val sweepMillis: Long = TimeUnit.SECONDS.toMillis(5),
  /** How a session's process tree is measured; Linux `/proc` by default. */
  private val meter: (List<Long>) -> ProcessMeter.Sample? = ProcessMeter::sample,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {

  /** What this host lends the simulator. */
  data class Capacity(
    /** Running process trees, whatever their size. */
    val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    /** The memory running sessions may hold together, in MB. */
    val memoryBudgetMb: Long = defaultMemoryBudgetMb(),
    /** Boots at once: a boot saturates about one core for several seconds. */
    val maxConcurrentBoots: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1),
    /** Sessions waiting to boot, beyond which a start is refused (429). */
    val maxQueued: Int = 8,
    /** Sessions one owner (an account, or the operator) holds at once. */
    val maxPerOwner: Int = 2,
  )

  enum class Kind {
    PAGE,
    EMBED,
  }

  class Session
  internal constructor(
    val id: String,
    val secret: String,
    val appId: String,
    val mode: SimulatorMode,
    val kind: Kind,
    val tier: SimulatorControlPolicy.Tier,
    /** The signed-in account or the operator; null for an embed. */
    val ownerKey: String?,
    val createdAtMillis: Long,
    val deadlineMillis: Long,
    internal val launch: SimulatorLaunch,
  ) {
    @Volatile
    var stage: SimulatorStage = SimulatorStage.QUEUED
      internal set

    @Volatile
    var stageAtMillis: Long = createdAtMillis
      internal set

    @Volatile
    var failureCode: String? = null
      internal set

    /** Why it failed, fit for whoever is looking: no host paths. */
    @Volatile
    var failureReason: String? = null
      internal set

    /** The process's stderr when it failed, for the operator only. */
    @Volatile
    var diagnostics: List<String> = emptyList()
      internal set

    /** Measured resident memory of its process tree, MB; null before the first sample. */
    @Volatile
    var rssMb: Long? = null
      internal set

    /** CPU over the last sample interval, percent of one core. */
    @Volatile
    var cpuPercent: Double? = null
      internal set

    /** Times it was paused, hibernated and woken: what the tiers actually did. */
    val pauses = AtomicInteger(0)
    val hibernations = AtomicInteger(0)

    @Volatile internal var process: SimulatorProcess? = null
    /** The last command anyone sent: what idleness is measured from. */
    @Volatile internal var lastInputMillis: Long = createdAtMillis
    /** The last sign of a client at all (a command, a status read, a socket). */
    @Volatile internal var lastActivityMillis: Long = createdAtMillis
    @Volatile internal var detachedSinceMillis: Long? = createdAtMillis
    @Volatile internal var hiddenSinceMillis: Long? = createdAtMillis
    internal val sockets = AtomicInteger(0)
    /** Commands running now: a session someone is waiting on is never abandoned. */
    internal val inFlight = AtomicInteger(0)
    internal val visibleSockets = AtomicInteger(0)
    internal val ready = ConcurrentHashMap.newKeySet<SimulatorDevice>()
    internal val frames = ConcurrentHashMap<SimulatorDevice, Frame>()
    internal val frameLocks = SimulatorDevice.entries.associateWith { Any() }
    internal val listeners = ConcurrentHashMap.newKeySet<(SimulatorDevice, JsonObject) -> Unit>()
    internal val displays = ConcurrentHashMap<SimulatorDevice, Display>()
    /** A hibernated session's top activity and its saved state, per device. */
    internal val restore = ConcurrentHashMap<SimulatorDevice, Pair<String, String>>()
    @Volatile internal var cpuSample: Pair<Long, Double>? = null
    @Volatile internal var resuming = false

    val devices: List<SimulatorDevice>
      get() = mode.devices

    fun displayOf(device: SimulatorDevice): Display? = displays[device]
  }

  data class Display(val widthPx: Int, val heightPx: Int, val round: Boolean)

  data class Frame(
    val device: SimulatorDevice,
    val seq: Long,
    val pngBase64: String,
    val widthPx: Int,
    val heightPx: Int,
    val digest: String,
    val takenAtMillis: Long,
  )

  sealed interface Started {
    data class Ok(val session: Session) : Started

    data class Invalid(val status: Int, val code: String, val reason: String) : Started

    data class Busy(val reason: String) : Started

    data class Unavailable(val code: String, val reason: String) : Started
  }

  private val sessions = ConcurrentHashMap<String, Session>()
  /** Sessions waiting for a boot, oldest first. Guarded by `this`. */
  private val queue = ArrayDeque<Session>()
  private val starter = Executors.newCachedThreadPool { r ->
    Thread(r, "simulator-start").apply { isDaemon = true }
  }
  private val sweeper: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "simulator-sweep").apply { isDaemon = true }
  }
  private val seq = AtomicLong(0)
  /** Running average RSS per `appId/mode`, MB: what the next such session is charged up front. */
  private val estimates = ConcurrentHashMap<String, Double>()

  init {
    sweeper.scheduleWithFixedDelay(
      { runCatching { sweep() }.onFailure { onLog("serve: simulator sweep: $it") } },
      sweepMillis,
      sweepMillis,
      TimeUnit.MILLISECONDS,
    )
  }

  val maxSessions: Int
    get() = capacity.maxSessions

  fun get(id: String): Session? = sessions[id]

  /**
   * Whether the pool still holds [session]: once closed it is never booted, woken or hibernated.
   */
  private fun isOpen(session: Session): Boolean = sessions[session.id] === session

  fun authorized(id: String, secret: String?): Session? {
    val session = sessions[id] ?: return null
    if (secret == null) return null
    return session.takeIf { MessageDigest.isEqual(it.secret.toByteArray(), secret.toByteArray()) }
  }

  fun all(): List<Session> = sessions.values.sortedBy { it.createdAtMillis }

  /** What [appId] in [mode] is charged before it has been measured, MB. */
  fun estimateMb(appId: String, mode: SimulatorMode): Long =
    estimates["$appId/${mode.id}"]?.toLong() ?: DEFAULT_ESTIMATE_MB.getValue(mode)

  private fun chargeMb(s: Session): Long = s.rssMb ?: estimateMb(s.appId, s.mode)

  fun start(
    appId: String,
    mode: SimulatorMode,
    kind: Kind,
    tier: SimulatorControlPolicy.Tier,
    ownerKey: String?,
  ): Started {
    sweep()
    val app =
      registry.get(appId) ?: return Started.Invalid(404, "simulator_app_unknown", "no app $appId")
    if (mode.id !in app.modes) {
      return Started.Invalid(
        422,
        "simulator_mode_unavailable",
        "$appId cannot run ${mode.id}: it has ${app.apks.map { it.device }.ifEmpty { listOf("no") }} APK(s)",
      )
    }
    val apks =
      mode.devices.associateWith {
        registry.apkFile(appId, it)
          ?: return Started.Invalid(422, "simulator_mode_unavailable", "$appId has no ${it.id} APK")
      }
    when (val availability = runtime.availability()) {
      is SimulatorRuntime.Availability.Unavailable ->
        return Started.Unavailable(availability.code, availability.reason)
      SimulatorRuntime.Availability.Ready -> Unit
    }
    if (estimateMb(appId, mode) > capacity.memoryBudgetMb) {
      return Started.Unavailable(
        "simulator_too_large",
        "$appId in ${mode.id} needs about ${estimateMb(appId, mode)} MB; this server lends the " +
          "simulator ${capacity.memoryBudgetMb} MB",
      )
    }
    val now = clock()
    val minutes =
      if (kind == Kind.EMBED) app.config.embed.maxSessionMinutes else app.config.maxSessionMinutes
    val replaced = mutableListOf<Session>()
    val session =
      synchronized(this) {
        val open = sessions.values.filter { !it.stage.ended }
        if (kind == Kind.EMBED) {
          val embedded = open.count { it.appId == appId && it.kind == Kind.EMBED }
          if (embedded >= app.config.embed.maxConcurrentSessions) {
            return Started.Busy("$appId is already running in $embedded embedded session(s)")
          }
        }
        if (ownerKey != null) {
          // An owner's new session replaces their oldest: a reload, a second tab, a mode switch.
          val theirs = open.filter { it.ownerKey == ownerKey }.sortedBy { it.createdAtMillis }
          replaced += theirs.take((theirs.size - capacity.maxPerOwner + 1).coerceAtLeast(0))
        }
        if (queue.size >= capacity.maxQueued) {
          return Started.Busy("${queue.size} simulator sessions are already waiting on this server")
        }
        Session(
            id = token(16),
            secret = token(32),
            appId = appId,
            mode = mode,
            kind = kind,
            tier = tier,
            ownerKey = ownerKey,
            createdAtMillis = now,
            deadlineMillis = now + TimeUnit.MINUTES.toMillis(minutes.toLong()),
            launch =
              SimulatorLaunch(
                sessionId = "",
                mode = mode,
                apks = apks,
                packages =
                  app.apks
                    .mapNotNull { a -> SimulatorDevice.of(a.device)?.let { it to a.packageName } }
                    .toMap(),
                network = app.config.network,
                launcher = app.config.launcher,
              ),
          )
          .also {
            sessions[it.id] = it
            queue.addLast(it)
          }
      }
    replaced.forEach { close(it.id, "replaced by its owner's new session") }
    onLog(
      "serve: simulator session ${session.id}: $appId ${mode.id} (${kind.name.lowercase()}) queued",
    )
    pump()
    return Started.Ok(session)
  }

  /** Where [session] is in the queue (0 = next), or null when it is not waiting. */
  fun queuePosition(session: Session): Int? =
    synchronized(this) { queue.indexOf(session).takeIf { it >= 0 } }

  /**
   * Admit queued sessions, oldest first, while a boot slot and the memory are free, hibernating
   * paused sessions nobody is looking at when that frees enough. Head-of-line: a large session at
   * the front waits for room rather than being overtaken for ever.
   */
  private fun pump() {
    val admitted = mutableListOf<Session>()
    val reclaim = mutableListOf<Session>()
    synchronized(this) {
      while (queue.isNotEmpty()) {
        val next = queue.first()
        if (next.stage.ended || !isOpen(next)) {
          queue.removeFirst()
          continue
        }
        // A session admitted earlier in this pass is already STARTING, so it is counted here and
        // only here: `admitted` is not added again.
        val running = sessions.values.filter { it.stage.holdsMemory && it !in reclaim }
        if (running.count { it.stage.booting } >= capacity.maxConcurrentBoots) break
        val need = estimateMb(next.appId, next.mode)
        var used = running.sumOf(::chargeMb)
        var count = running.size
        if (count >= capacity.maxSessions || used + need > capacity.memoryBudgetMb) {
          // Room can be made from paused sessions with no visible viewer, least recently used
          // first.
          val candidates =
            running
              .filter { it.stage == SimulatorStage.PAUSED && it.visibleSockets.get() == 0 }
              .sortedBy { it.lastInputMillis }
          val freed = mutableListOf<Session>()
          for (c in candidates) {
            if (count < capacity.maxSessions && used + need <= capacity.memoryBudgetMb) break
            freed += c
            used -= chargeMb(c)
            count--
          }
          if (count >= capacity.maxSessions || used + need > capacity.memoryBudgetMb) break
          reclaim += freed
        }
        queue.removeFirst()
        next.stage = SimulatorStage.STARTING
        next.stageAtMillis = clock()
        admitted += next
      }
    }
    reclaim.forEach(::hibernate)
    admitted.forEach { s -> starter.execute { boot(s) } }
  }

  private fun boot(session: Session) {
    val process =
      try {
        runtime.start(session.launch.copy(sessionId = session.id)) { device, event ->
          onEvent(session, device, event)
        }
      } catch (e: Exception) {
        fail(session, "simulator_start_failed", "the simulator could not start", listOf("$e"))
        return
      }
    session.process = process
    if (!isOpen(session)) {
      runCatching { process.close() }
      pump()
      return
    }
    try {
      val deadline = clock() + bootTimeoutMillis
      while (session.ready.size < session.devices.size) {
        if (session.stage.ended) return
        if (!process.alive) {
          fail(
            session,
            "simulator_exited",
            "the simulator exited before the app's first frame",
            process.stderrTail(),
          )
          return
        }
        if (clock() > deadline) {
          fail(
            session,
            "simulator_boot_timeout",
            "the app did not reach its first frame in ${bootTimeoutMillis / 1000}s",
            process.stderrTail(),
          )
          return
        }
        Thread.sleep(BOOT_POLL_MILLIS)
      }
      restoreState(session)
      for (device in session.devices) runCatching { learnDisplay(session, device) }
      if (!session.stage.ended) {
        session.stage = SimulatorStage.LIVE
        session.stageAtMillis = clock()
        session.lastInputMillis = clock()
        session.lastActivityMillis = clock()
        // Hidden-time counts from going live, not from the request: a boot is not idleness.
        if (session.visibleSockets.get() == 0) session.hiddenSinceMillis = clock()
        session.resuming = false
      }
    } finally {
      if (session.stage.ended) runCatching { process.close() }
      pump()
    }
  }

  /** A woken hibernated session: each device's top activity, recreated from what it saved. */
  private fun restoreState(session: Session) {
    val process = session.process ?: return
    for ((device, saved) in session.restore) {
      val (activity, state) = saved
      val pkg = session.launch.packages[device] ?: continue
      runCatching {
        process.command(
          device,
          JsonObject(
            mapOf(
              "cmd" to JsonPrimitive("startActivity"),
              "forceStop" to JsonPrimitive(true),
              "intent" to JsonObject(mapOf("component" to JsonPrimitive("$pkg/$activity"))),
              "savedState" to JsonPrimitive(state),
            ),
          ),
          COMMAND_TIMEOUT_MILLIS,
        )
      }
        .onFailure { onLog("serve: simulator ${session.id}: could not restore ${device.id}: $it") }
    }
    session.restore.clear()
  }

  private fun learnDisplay(session: Session, device: SimulatorDevice) {
    val info = session.process?.command(device, command("info"), INFO_TIMEOUT_MILLIS) as? JsonObject
    val round =
      (info?.get("round") as? JsonPrimitive)?.booleanOrNull ?: (device == SimulatorDevice.WATCH)
    val width = (info?.get("width") as? JsonPrimitive)?.intOrNull
    val height = (info?.get("height") as? JsonPrimitive)?.intOrNull
    if (width != null && height != null) {
      session.displays[device] = Display(width, height, round)
      return
    }
    val frame = frame(session, device, force = true) ?: return
    session.displays[device] = Display(frame.widthPx, frame.heightPx, round)
  }

  private fun onEvent(session: Session, device: SimulatorDevice, event: JsonObject) {
    when ((event["event"] as? JsonPrimitive)?.contentOrNull) {
      "conversion" ->
        if (session.stage == SimulatorStage.STARTING) setStage(session, SimulatorStage.CONVERTING)
      "ready" -> {
        if (session.stage.booting) setStage(session, SimulatorStage.BOOTING)
        session.ready += device
      }
    }
    session.listeners.forEach { runCatching { it(device, event) } }
  }

  private fun setStage(session: Session, stage: SimulatorStage) {
    if (session.stage.ended) return
    session.stage = stage
    session.stageAtMillis = clock()
  }

  private fun fail(session: Session, code: String, reason: String, diagnostics: List<String>) {
    if (session.stage.ended) return
    session.failureCode = code
    session.failureReason = reason
    session.diagnostics = diagnostics.takeLast(40)
    setStage(session, SimulatorStage.FAILED)
    onLog("serve: simulator session ${session.id} failed: $code $reason")
    runCatching { session.process?.close() }
    pump()
  }

  /**
   * A client wants [session] now: thaw a paused one, re-queue a hibernated one. Returns whether it
   * is live, so the caller can run its command or tell the client it is coming back.
   */
  fun wake(session: Session): Boolean {
    when (session.stage) {
      SimulatorStage.PAUSED ->
        synchronized(session) {
          if (session.stage == SimulatorStage.PAUSED) {
            runCatching { session.process?.resume() }
            session.stage = SimulatorStage.LIVE
            session.stageAtMillis = clock()
          }
        }
      SimulatorStage.HIBERNATED -> {
        synchronized(this) {
          // Only a session the pool still holds is woken: a removed one could never be swept again.
          if (session.stage == SimulatorStage.HIBERNATED && isOpen(session)) {
            session.stage = SimulatorStage.QUEUED
            session.stageAtMillis = clock()
            session.resuming = true
            session.ready.clear()
            queue.addLast(session)
          }
        }
        pump()
      }
      else -> Unit
    }
    session.lastInputMillis = clock()
    session.lastActivityMillis = clock()
    return session.stage == SimulatorStage.LIVE
  }

  /**
   * Count a command or screenshot into [session], under its lock, so [pause] can never freeze the
   * process tree in the middle of one (the command would then hang to its timeout). Returns its
   * process, or null when it is not live.
   */
  private fun enter(session: Session): SimulatorProcess? =
    synchronized(session) {
      val process = session.process
      if (session.stage != SimulatorStage.LIVE || process == null) return null
      session.inFlight.incrementAndGet()
      process
    }

  /** Freeze an idle session; [wake] undoes it. Never while a command is running. */
  fun pause(session: Session) {
    synchronized(session) {
      if (session.stage != SimulatorStage.LIVE || session.inFlight.get() > 0) return
      runCatching { session.process?.pause() }
      session.stage = SimulatorStage.PAUSED
      session.stageAtMillis = clock()
      session.pauses.incrementAndGet()
    }
  }

  /**
   * End a paused session's process to free its memory, keeping each device's top activity and saved
   * state for [wake] to restore: what `rememberSaveable`, `onSaveInstanceState` and
   * `SavedStateHandle` hold comes back; in-memory state such as a `ViewModel`'s does not.
   */
  fun hibernate(session: Session) {
    val process = session.process ?: return
    synchronized(session) {
      if (session.stage != SimulatorStage.PAUSED && session.stage != SimulatorStage.LIVE) return
      runCatching { process.resume() }
      for (device in session.devices) {
        runCatching {
          val saved =
            process.command(device, command("savedState"), COMMAND_TIMEOUT_MILLIS) as? JsonObject
          val activity = (saved?.get("activity") as? JsonPrimitive)?.contentOrNull
          val state = (saved?.get("savedState") as? JsonPrimitive)?.contentOrNull
          if (activity != null && state != null) session.restore[device] = activity to state
        }
      }
      runCatching { process.close() }
      session.process = null
      session.frames.clear()
      // Closed while its state was being saved: it stays closed (close() does not wait on this
      // lock).
      if (session.stage.ended || !isOpen(session)) return
      session.stage = SimulatorStage.HIBERNATED
      session.stageAtMillis = clock()
      session.hibernations.incrementAndGet()
    }
    onLog(
      "serve: simulator session ${session.id} hibernated (saved ${session.restore.keys.map { it.id }})",
    )
  }

  fun command(session: Session, decision: SimulatorControlPolicy.Decision.Allowed): JsonElement? {
    if (!wake(session)) {
      throw SimulatorCommandException(
        if (session.stage == SimulatorStage.QUEUED || session.stage.booting)
          "the session is resuming; try again in a moment"
        else "the session is ${session.stage.id}, not live",
      )
    }
    val process =
      enter(session)
        ?: throw SimulatorCommandException("the session is ${session.stage.id}, not live")
    val timeout = if (decision.cmd == "touch") TOUCH_TIMEOUT_MILLIS else COMMAND_TIMEOUT_MILLIS
    val began = clock()
    val result =
      try {
        process.command(decision.device, decision.command, timeout)
      } finally {
        session.inFlight.decrementAndGet()
        session.lastActivityMillis = clock()
        if (clock() - began > SLOW_COMMAND_MILLIS) {
          onLog(
            "serve: simulator ${session.id}: ${decision.cmd} on ${decision.device.id} took ${clock() - began}ms",
          )
        }
      }
    if (decision.cmd != "touch") session.frames.remove(decision.device)
    return result?.let { SimulatorControlPolicy.redact(it, runtime.hostRoots()) }
  }

  /**
   * How often [session]'s frames are worth taking now: fast just after input, slower as the screen
   * is left alone. Frames are deduplicated anyway; this is what the screenshots cost (measured: ~35
   * ms and ~2.5% of a core per frame per second).
   */
  fun frameIntervalFor(session: Session): Long {
    val since = clock() - session.lastInputMillis
    return when {
      since < 2_000 -> frameIntervalMillis
      since < 15_000 -> frameIntervalMillis * 3
      else -> frameIntervalMillis * 10
    }
  }

  /**
   * [device]'s current frame: the cached one while it is fresher than [frameIntervalFor], else a
   * new screenshot. One screenshot at a time per device, so ten viewers cost one. A paused session
   * is not woken for a frame: its last one stands.
   */
  fun frame(session: Session, device: SimulatorDevice, force: Boolean = false): Frame? {
    if (session.stage != SimulatorStage.LIVE && !force) return session.frames[device]
    val process =
      (if (force) session.process?.also { session.inFlight.incrementAndGet() } else enter(session))
        ?: return session.frames[device]
    try {
      if (!process.alive) return null
      return shoot(session, process, device, force)
    } finally {
      session.inFlight.decrementAndGet()
    }
  }

  private fun shoot(
    session: Session,
    process: SimulatorProcess,
    device: SimulatorDevice,
    force: Boolean,
  ): Frame? {
    synchronized(session.frameLocks.getValue(device)) {
      val cached = session.frames[device]
      if (
        !force && cached != null && clock() - cached.takenAtMillis < frameIntervalFor(session) / 2
      ) {
        return cached
      }
      val shot =
        process.command(device, command("screenshot"), SCREENSHOT_TIMEOUT_MILLIS) as? JsonObject
          ?: return cached
      val png = (shot["png"] as? JsonPrimitive)?.contentOrNull ?: return cached
      val digest = sha256(png)
      if (cached != null && cached.digest == digest) {
        return cached.copy(takenAtMillis = clock()).also { session.frames[device] = it }
      }
      return Frame(
          device = device,
          seq = seq.incrementAndGet(),
          pngBase64 = png,
          widthPx = (shot["width"] as? JsonPrimitive)?.intOrNull ?: 0,
          heightPx = (shot["height"] as? JsonPrimitive)?.intOrNull ?: 0,
          digest = digest,
          takenAtMillis = clock(),
        )
        .also { session.frames[device] = it }
    }
  }

  fun touch(session: Session) {
    session.lastActivityMillis = clock()
  }

  fun attach(session: Session) {
    session.sockets.incrementAndGet()
    session.visibleSockets.incrementAndGet()
    session.detachedSinceMillis = null
    session.hiddenSinceMillis = null
    session.lastActivityMillis = clock()
    wake(session)
  }

  /** A socket's page became hidden or visible again. */
  fun visibility(session: Session, visible: Boolean) {
    if (visible) {
      session.visibleSockets.incrementAndGet()
      session.hiddenSinceMillis = null
      wake(session)
    } else if (session.visibleSockets.decrementAndGet() <= 0) {
      session.visibleSockets.set(0)
      session.hiddenSinceMillis = clock()
    }
  }

  fun detach(session: Session, wasVisible: Boolean) {
    if (wasVisible && session.visibleSockets.decrementAndGet() <= 0) {
      session.visibleSockets.set(0)
      session.hiddenSinceMillis = clock()
    }
    if (session.sockets.decrementAndGet() <= 0) {
      session.sockets.set(0)
      session.detachedSinceMillis = clock()
      session.lastActivityMillis = clock()
    }
  }

  fun close(id: String, why: String = "closed") {
    val session = sessions.remove(id) ?: return
    val was = session.stage
    synchronized(this) { queue.remove(session) }
    session.stage = SimulatorStage.CLOSED
    session.stageAtMillis = clock()
    runCatching { session.process?.close() }
    session.listeners.clear()
    onLog("serve: simulator session $id ended (was ${was.id}): $why")
    pump()
  }

  /** Apply the idle tiers, measure what runs, and admit whatever now fits. */
  fun sweep() {
    val now = clock()
    for (session in sessions.values) {
      val why =
        when {
          now >= session.deadlineMillis -> "reached its time limit"
          session.stage == SimulatorStage.FAILED &&
            now - session.stageAtMillis > detachedGraceMillis -> "failed"
          // Abandonment is judged from when a session went live: a boot, however slow, is bounded
          // by bootTimeoutMillis instead, and a queued one costs nothing.
          !session.stage.booting &&
            session.stage != SimulatorStage.QUEUED &&
            session.inFlight.get() == 0 &&
            session.detachedSinceMillis?.let {
              now - maxOf(it, session.lastActivityMillis) > detachedGraceMillis
            } == true ->
            "no viewer for ${(now - maxOf(session.detachedSinceMillis!!, session.lastActivityMillis)) / 1000}s"
          !session.stage.ended &&
            !session.stage.booting &&
            session.stage != SimulatorStage.QUEUED &&
            now - session.lastInputMillis > idleMillis -> "no input for ${idleMillis / 1000}s"
          else -> null
        }
      if (why != null) {
        close(session.id, why)
        continue
      }
      if (session.stage == SimulatorStage.LIVE) {
        if (session.process?.alive == false) {
          fail(session, "simulator_exited", "the simulator exited", session.process!!.stderrTail())
          continue
        }
        // Hidden for a while, and nobody (an agent over REST included) has asked anything lately.
        val hidden =
          session.hiddenSinceMillis?.let {
            now - maxOf(it, session.lastActivityMillis) > pauseHiddenMillis
          } == true
        if (hidden || now - session.lastInputMillis > pauseIdleMillis) pause(session)
      }
      if (session.stage.holdsMemory) measure(session, now)
    }
    pump()
  }

  private fun measure(session: Session, now: Long) {
    val pids = session.process?.pids().orEmpty()
    if (pids.isEmpty()) return
    val sample = runCatching { meter(pids) }.getOrNull() ?: return
    session.rssMb = sample.rssMb
    session.cpuSample?.let { (at, cpu) ->
      if (now > at)
        session.cpuPercent =
          ((sample.cpuSeconds - cpu) / ((now - at) / 1000.0) * 100).coerceAtLeast(0.0)
    }
    session.cpuSample = now to sample.cpuSeconds
    if (session.stage == SimulatorStage.LIVE || session.stage == SimulatorStage.PAUSED) {
      val key = "${session.appId}/${session.mode.id}"
      estimates.merge(key, sample.rssMb.toDouble()) { old, new ->
        maxOf(new, old * 0.8 + new * 0.2)
      }
    }
  }

  /** The pool as an operator reads it. */
  fun capacityStatus(): SimulatorCapacityV1 {
    val open = sessions.values.filter { !it.stage.ended }
    val running = open.filter { it.stage.holdsMemory }
    return SimulatorCapacityV1(
      maxSessions = capacity.maxSessions,
      memoryBudgetMb = capacity.memoryBudgetMb,
      memoryChargedMb = running.sumOf(::chargeMb),
      maxConcurrentBoots = capacity.maxConcurrentBoots,
      maxQueued = capacity.maxQueued,
      maxPerOwner = capacity.maxPerOwner,
      running = running.size,
      live = open.count { it.stage == SimulatorStage.LIVE },
      booting = open.count { it.stage.booting },
      paused = open.count { it.stage == SimulatorStage.PAUSED },
      hibernated = open.count { it.stage == SimulatorStage.HIBERNATED },
      queued = open.count { it.stage == SimulatorStage.QUEUED },
      cpuPercent = running.sumOf { it.cpuPercent ?: 0.0 },
      estimatesMb =
        SimulatorMode.entries.associate { it.id to DEFAULT_ESTIMATE_MB.getValue(it) } +
          estimates.mapValues { it.value.toLong() },
    )
  }

  override fun close() {
    sweeper.shutdownNow()
    sessions.keys.toList().forEach { close(it, "server stopping") }
    starter.shutdownNow()
  }

  companion object {
    const val DEFAULT_MAX_SESSIONS = 8

    /**
     * What a session is charged before it is measured, MB. Measured on the released CLI: a trivial
     * phone 540-610, watch 540-580, paired 1000-1050; a real Compose app's simulator adds several
     * hundred more (`docs/design/SIMULATOR.md`). Erring high keeps the first sessions of a new app
     * from overcommitting; [estimateMb] replaces these with what each app really uses.
     */
    val DEFAULT_ESTIMATE_MB: Map<SimulatorMode, Long> =
      mapOf(SimulatorMode.PHONE to 900, SimulatorMode.WATCH to 800, SimulatorMode.PAIRED to 1_700)

    /**
     * Half the machine, at least 2 GB: the server, its daemons and the page cache need the rest.
     */
    fun defaultMemoryBudgetMb(): Long {
      val total =
        (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            as? com.sun.management.OperatingSystemMXBean)
          ?.totalMemorySize ?: 0L
      return (total / (1L shl 20) / 2).coerceAtLeast(2_048)
    }

    private const val BOOT_POLL_MILLIS = 100L
    private const val INFO_TIMEOUT_MILLIS = 30_000L
    private const val SCREENSHOT_TIMEOUT_MILLIS = 30_000L
    private const val COMMAND_TIMEOUT_MILLIS = 60_000L
    private const val TOUCH_TIMEOUT_MILLIS = 10_000L
    private const val SLOW_COMMAND_MILLIS = 5_000L
    private val RANDOM = SecureRandom()

    internal fun command(name: String) = JsonObject(mapOf("cmd" to JsonPrimitive(name)))

    internal fun token(bytes: Int): String =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(bytes).also(RANDOM::nextBytes))

    private fun sha256(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
        "%02x".format(it)
      }
  }
}

/** Resident memory and CPU time of a process tree, from Linux `/proc`. */
object ProcessMeter {
  data class Sample(val rssMb: Long, val cpuSeconds: Double)

  private const val PAGE_BYTES = 4096L
  private const val CLOCK_TICKS = 100.0

  fun sample(pids: List<Long>): Sample? {
    if (!File("/proc/self/statm").isFile) return null
    var rssPages = 0L
    var ticks = 0L
    for (pid in pids) {
      runCatching {
        rssPages += File("/proc/$pid/statm").readText().split(' ')[1].toLong()
        val fields = File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ')
        ticks += fields[11].toLong() + fields[12].toLong()
      }
    }
    return Sample(rssPages * PAGE_BYTES / (1L shl 20), ticks / CLOCK_TICKS)
  }
}

@kotlinx.serialization.Serializable
data class SimulatorCapacityV1(
  val schema: String = "compose-preview/simulator-capacity/v1",
  val maxSessions: Int,
  val memoryBudgetMb: Long,
  /** What running sessions are charged: measured RSS, or the estimate until measured. */
  val memoryChargedMb: Long,
  val maxConcurrentBoots: Int,
  val maxQueued: Int,
  val maxPerOwner: Int,
  val running: Int,
  val live: Int,
  val booting: Int,
  val paused: Int,
  val hibernated: Int,
  val queued: Int,
  /** CPU of every running session together, percent of one core. */
  val cpuPercent: Double,
  /** What a session of each mode (or `app/mode`, once measured) is charged up front, MB. */
  val estimatesMb: Map<String, Long>,
)

@kotlinx.serialization.Serializable
data class SimulatorSessionStatusV1(
  val schema: String = "compose-preview/simulator-session/v1",
  val sessionId: String,
  val appId: String,
  val mode: String,
  val devices: List<String>,
  val stage: String,
  val step: Int,
  val steps: Int = SimulatorStage.STEPS,
  val label: String,
  val elapsedMillis: Long,
  val remainingMillis: Long,
  /** While [stage] is `queued`: sessions ahead of this one. */
  val queueAhead: Int? = null,
  val code: String? = null,
  val reason: String? = null,
  val diagnostics: List<String>? = null,
  val displays: Map<String, SimulatorDisplayV1> = emptyMap(),
  val rssMb: Long? = null,
  val cpuPercent: Double? = null,
  val pauses: Int = 0,
  val hibernations: Int = 0,
)

@kotlinx.serialization.Serializable
data class SimulatorDisplayV1(val widthPx: Int, val heightPx: Int, val round: Boolean)

internal fun SimulatorSessions.Session.status(
  now: Long,
  queueAhead: Int? = null,
): SimulatorSessionStatusV1 =
  SimulatorSessionStatusV1(
    sessionId = id,
    appId = appId,
    mode = mode.id,
    devices = devices.map { it.id },
    stage = stage.id,
    step = stage.step,
    label =
      when (stage) {
        SimulatorStage.QUEUED ->
          when (queueAhead) {
            null,
            0 ->
              if (resuming) "Waking up (next in line)"
              else "Waiting for a free simulator (next in line)"
            else -> "Waiting for a free simulator ($queueAhead ahead)"
          }
        SimulatorStage.STARTING -> if (resuming) "Waking the app" else "Starting the simulator"
        SimulatorStage.CONVERTING -> "Converting the app (once per build of it)"
        SimulatorStage.BOOTING ->
          if (mode == SimulatorMode.PAIRED) "Booting the phone and the watch"
          else "Booting the ${mode.id}"
        SimulatorStage.LIVE -> "Live"
        SimulatorStage.PAUSED -> "Paused while idle; touch to resume"
        SimulatorStage.HIBERNATED -> "Asleep to make room; touch to wake it"
        SimulatorStage.FAILED -> failureReason ?: "Failed"
        SimulatorStage.CLOSED -> "Ended"
      },
    elapsedMillis = now - createdAtMillis,
    remainingMillis = (deadlineMillis - now).coerceAtLeast(0),
    queueAhead = queueAhead,
    code = failureCode,
    reason = failureReason,
    diagnostics =
      diagnostics.takeIf { tier == SimulatorControlPolicy.Tier.OPERATOR && it.isNotEmpty() },
    displays =
      displays
        .mapKeys { it.key.id }
        .mapValues { SimulatorDisplayV1(it.value.widthPx, it.value.heightPx, it.value.round) },
    rssMb = rssMb,
    cpuPercent = cpuPercent?.let { Math.round(it * 10) / 10.0 },
    pauses = pauses.get(),
    hibernations = hibernations.get(),
  )

/**
 * Signed, short-lived permission to embed one app: what an embedding site's backend mints with the
 * admin token and puts in its page's iframe URL. `base64url(payload).base64url(HMAC-SHA256)`,
 * payload `{"a":appId,"e":expiresAtSeconds,"n":nonce}`.
 */
class SimulatorEmbedTokens(
  private val key: ByteArray,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun mint(appId: String, ttlSeconds: Long): String {
    val exp = clock() / 1000 + ttlSeconds.coerceIn(60, MAX_TTL_SECONDS)
    val payload =
      JsonObject(
          mapOf(
            "a" to JsonPrimitive(appId),
            "e" to JsonPrimitive(exp),
            "n" to JsonPrimitive(SimulatorSessions.token(9)),
          ),
        )
        .toString()
        .toByteArray()
    return "${B64.encodeToString(payload)}.${B64.encodeToString(mac(payload))}"
  }

  fun verify(token: String?, appId: String): Boolean {
    if (token == null || token.length > 512) return false
    val parts = token.split('.')
    if (parts.size != 2) return false
    val payload = runCatching { B64D.decode(parts[0]) }.getOrNull() ?: return false
    val sig = runCatching { B64D.decode(parts[1]) }.getOrNull() ?: return false
    if (!MessageDigest.isEqual(mac(payload), sig)) return false
    val obj =
      runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(payload.decodeToString()).jsonObject
      }
        .getOrNull() ?: return false
    val app = (obj["a"] as? JsonPrimitive)?.contentOrNull
    val exp = (obj["e"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return false
    return app == appId && exp > clock() / 1000
  }

  private fun mac(payload: ByteArray): ByteArray =
    javax.crypto.Mac.getInstance("HmacSHA256")
      .apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")) }
      .doFinal(payload)

  companion object {
    const val MAX_TTL_SECONDS = 3_600L
    private val B64 = Base64.getUrlEncoder().withoutPadding()
    private val B64D = Base64.getUrlDecoder()

    fun fromFile(file: File): SimulatorEmbedTokens {
      if (!file.isFile) {
        file.parentFile.mkdirs()
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeBytes(key)
        runCatching {
          java.nio.file.Files.setPosixFilePermissions(
            temp.toPath(),
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
          )
        }
        java.nio.file.Files.move(
          temp.toPath(),
          file.toPath(),
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
      }
      return SimulatorEmbedTokens(file.readBytes())
    }
  }
}
