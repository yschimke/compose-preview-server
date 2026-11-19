package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Where a design's watch-face build is, as its owner's publication status shows it: an owner sees
 * "Will build 10 min after your last edit", then "Queued (1 ahead)", then "Packaging…", then "Built
 * v7".
 */
@Serializable
data class WatchFacePushBuildStatusV1(
  /** `pending` (waiting to settle), `queued`, `building`, `built`, `refused`, `failed`. */
  val state: String,
  val reason: String? = null,
  /** `pending`: when the design is due, ten minutes after its last edit. */
  val scheduledAtEpochMillis: Long? = null,
  /** `queued`: how many builds run before this one. */
  val queueAhead: Int? = null,
  /** `building`: what it is doing; the packager is one step from here, so `packaging`. */
  val stage: String? = null,
  val startedAtEpochMillis: Long? = null,
  val elapsedMillis: Long? = null,
  /** `built`: the version it built. */
  val version: Long? = null,
  /** `refused`: the packager's and validator's lines. */
  val diagnostics: List<String>? = null,
  /** `failed`: when it is tried again, for a build the automatic listing retries. */
  val nextRetryAtEpochMillis: Long? = null,
  /** Whether the automatic listing of a public design asked for it, rather than the owner. */
  val automatic: Boolean = false,
)

/** `/status`'s count of the builds in flight. */
@Serializable
data class WatchFacePushBuildSummaryV1(val pending: Int, val queued: Int, val building: Int)

/**
 * Every watch-face build on this host runs here, one at a time, on one low-priority thread: an
 * owner's publish ([submit]) and the automatic listing's ([WatchFacePushAutomatic], under
 * [exclusive]) alike. No request waits on the packager; each design's last state is kept for its
 * owner's status ([status]).
 */
internal class WatchFacePushBuildQueue(private val clock: () -> Long) {
  private val executor = lazy {
    Executors.newSingleThreadExecutor { task ->
      Thread(task, "watchface-push-builds").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
      }
    }
  }

  private val scopeHolder = lazy {
    CoroutineScope(SupervisorJob() + executor.value.asCoroutineDispatcher())
  }

  /** The one thread every build is started from. */
  val scope: CoroutineScope
    get() = scopeHolder.value

  /** Set by [close]: nothing more starts, and nothing more is committed. */
  @Volatile
  var closed: Boolean = false
    private set

  /**
   * The server that owns this stopped: start nothing more, and let a commit of a build already
   * running be refused ([WatchFacePush.stage] checks [closed]). The thread is shut down, not
   * interrupted: a packager already running finishes and cleans up its own subprocess tree.
   */
  fun close() {
    closed = true
    if (scopeHolder.isInitialized()) scopeHolder.value.cancel()
    if (executor.isInitialized()) executor.value.shutdown()
  }

  /** Held for the whole of a build, by an owner's job and the automatic listing alike. */
  val exclusive = Mutex()

  private val states = ConcurrentHashMap<String, WatchFacePushBuildStatusV1>()
  private val waiting = ConcurrentLinkedDeque<String>()
  private val jobs = ConcurrentHashMap.newKeySet<Job>()
  @Volatile private var building: String? = null

  fun record(designId: String, status: WatchFacePushBuildStatusV1) {
    states[designId] = status
  }

  fun clear(designId: String) {
    states.remove(designId)
  }

  /** [designId]'s build now, with its place in the queue and time so far worked out. */
  fun status(designId: String): WatchFacePushBuildStatusV1? {
    val status = states[designId] ?: return null
    return when (status.state) {
      QUEUED ->
        status.copy(
          queueAhead =
            waiting.indexOf(designId).coerceAtLeast(0) + (if (building != null) 1 else 0),
        )
      BUILDING -> status.copy(elapsedMillis = status.startedAtEpochMillis?.let { clock() - it })
      else -> status
    }
  }

  fun summary(): WatchFacePushBuildSummaryV1 {
    val values = states.values
    return WatchFacePushBuildSummaryV1(
      pending = values.count { it.state == PENDING },
      queued = values.count { it.state == QUEUED },
      building = values.count { it.state == BUILDING },
    )
  }

  /**
   * Queue [build] for [designId] behind every build already waiting, and return at once; [build]
   * runs under [exclusive] and answers the state it ends in.
   */
  fun submit(designId: String, build: suspend () -> WatchFacePushBuildStatusV1?) {
    if (closed) {
      record(designId, WatchFacePushBuildStatusV1(FAILED, reason = "the server is stopping"))
      return
    }
    record(designId, WatchFacePushBuildStatusV1(QUEUED))
    waiting.addLast(designId)
    val job = scope.launch {
      exclusive.withLock {
        waiting.remove(designId)
        if (closed) return@withLock
        building(designId)
        val result = runCatching {
          build()
        }
          .getOrElse {
            WatchFacePushBuildStatusV1(FAILED, reason = it.message ?: it.javaClass.simpleName)
          }
        // Null: cancelled (unpublished before it was built), so it leaves no state.
        if (result != null) record(designId, result) else clear(designId)
        building = null
      }
    }
    jobs += job
    job.invokeOnCompletion { jobs -= job }
  }

  /** Mark [designId] as building now; the caller holds [exclusive]. */
  fun building(designId: String, automatic: Boolean = false) {
    building = designId
    record(
      designId,
      WatchFacePushBuildStatusV1(
        BUILDING,
        stage = "packaging",
        startedAtEpochMillis = clock(),
        automatic = automatic,
      ),
    )
  }

  fun done(designId: String) {
    if (building == designId) building = null
  }

  /** Wait for every submitted build. For tests. */
  internal suspend fun awaitIdle() {
    // A snapshot by array: `toList()` on a one-element concurrent set can lose that element.
    while (jobs.isNotEmpty()) jobs.toTypedArray().toList().joinAll()
  }

  companion object {
    const val PENDING = "pending"
    const val QUEUED = "queued"
    const val BUILDING = "building"
    const val BUILT = "built"
    const val REFUSED = "refused"
    const val FAILED = "failed"
  }
}
