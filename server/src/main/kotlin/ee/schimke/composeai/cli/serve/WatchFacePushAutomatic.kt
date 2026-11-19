package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * A public `wff-watchface` design is on every watch without its owner publishing it.
 *
 * Making a design public is already the owner's decision that anyone may read it, so a public watch
 * face is listed on `GET /designs` to every caller, built from a settled revision. Building one
 * runs the packager (`aapt2` and Google's validator in their own JVM), so it happens only on
 * deliberate events, never on a request:
 * - **Events, not requests.** [noteChanged] is called by [WatchFacePushChangeFeed] after a design
 *   is edited, renamed, restored, or its access changes. Listing `/designs` or reading `/status`
 *   only reads what is already built; they start nothing.
 * - **Debounced.** An edit schedules the design [settleMillis] after its *last* edit (ten minutes),
 *   so a live editing session builds once, when it settles. A change of access — becoming public —
 *   is due after [accessSettleMillis].
 * - **One slow scheduler.** A single low-priority thread wakes every [tickMillis] and handles what
 *   is due, one design at a time; a build goes through [WatchFacePushBuilds], which runs one
 *   packager JVM at a time for the whole server. Each design is read on its own
 *   ([WatchFacePush.designSummary] and one snapshot), never by listing every design.
 * - **Versions** come from the same counter as an explicit publication, through the one publish
 *   path ([WatchFacePush.stage]), so they move when the face's content digest does and are never
 *   lowered or reused, and the package name is the one an explicit publish gives the same design.
 * - **Explicit wins.** An owner's publication — public, private, or a pinned revision — and an
 *   owner's unpublish are left alone; an explicit publish that lands during an automatic build is
 *   never overwritten by it (the commit re-checks under the store's lock).
 * - **Lifecycle.** A design made private, trashed or deleted is no longer readable anonymously, so
 *   [WatchFacePush.reach] stops listing its automatic publication at once. A purged design is
 *   retired as before; one recreated under its id is a different design and starts above every
 *   version already installed.
 * - **Refusals** are kept on disk ([WatchFacePushStore.automaticRefusals]) with the design and
 *   revision they were for, reported on `/status` and the owner's publication status, and not built
 *   again until the design changes. A failure around the packager (a timeout, a full disk) is tried
 *   again later, backing off from [retryMillis] to an hour.
 *
 * A design that was public before this ran is picked up on its next edit or change of access.
 */
internal class WatchFacePushAutomatic(
  private val push: WatchFacePush,
  /** `watchFacePushBuilds` ([WatchFacePushBuildsSetting]); off, this does nothing at all. */
  val enabled: Boolean = false,
  /** The designs on disk, for the one sweep at start ([sweep]): read without the service's lock. */
  private val storedDesigns: () -> List<WatchFacePushStoredDesigns.Head> = { emptyList() },
  /** The pause between two designs the startup sweep looks at. */
  private val sweepPauseMillis: Long = DEFAULT_SWEEP_PAUSE_MILLIS,
  private val settleMillis: Long = DEFAULT_SETTLE_MILLIS,
  private val accessSettleMillis: Long = DEFAULT_ACCESS_SETTLE_MILLIS,
  private val retryMillis: Long = DEFAULT_RETRY_MILLIS,
  /** How often the scheduler wakes; null: it never runs on its own (tests call [runDue]). */
  private val tickMillis: Long? = DEFAULT_TICK_MILLIS,
) : Closeable {
  private class Due(val atMillis: Long, val failures: Int = 0)

  private val due = ConcurrentHashMap<String, Due>()
  private val refusals = ConcurrentHashMap<String, WatchFacePushStore.AutomaticRefusal>()
  private val started = AtomicBoolean(false)
  private val queue: WatchFacePushBuildQueue
    get() = push.queue

  init {
    refusals.putAll(push.store.automaticRefusals())
  }

  /**
   * Start the scheduler for [service], with its one startup sweep first; once, and only enabled.
   */
  fun start(service: UiBuilderServicePort) {
    if (!enabled) return
    val tick = tickMillis ?: return
    if (!started.compareAndSet(false, true)) return
    // The build queue's one low-priority thread: these builds take their turn with the owners'.
    queue.scope.launch {
      runCatching { sweep(service) }
        .onFailure { System.err.println("serve: watchface-push: startup sweep: $it") }
      while (isActive) {
        delay(tick)
        runCatching { runDue(service) }
          .onFailure { System.err.println("serve: watchface-push: automatic listing: $it") }
      }
    }
  }

  override fun close() = Unit

  /**
   * [designId] changed: (re)schedule it [settleMillis] from now, or [accessSettleMillis] for a
   * change of access. Cheap and non-blocking; the design is not read here.
   */
  fun noteChanged(designId: String, access: Boolean = false) {
    if (!enabled || designId.isBlank()) return
    val at = push.clock() + if (access) accessSettleMillis else settleMillis
    val scheduled =
      due.merge(designId, Due(at)) { old, new ->
        // An access change does not postpone an edit already waiting less; an edit always does.
        if (access) Due(minOf(old.atMillis, new.atMillis)) else new
      }!!
    // "Will build 10 min after your last edit", unless a build is already running for it.
    if (queue.status(designId)?.state != WatchFacePushBuildQueue.BUILDING) {
      queue.record(
        designId,
        WatchFacePushBuildStatusV1(
          WatchFacePushBuildQueue.PENDING,
          reason = "builds once the design has settled",
          scheduledAtEpochMillis = scheduled.atMillis,
          automatic = true,
        ),
      )
    }
  }

  /** The packager's diagnostics for [designId]'s refused revision, when it refused it. */
  fun refusal(designId: String): List<String>? = refusals[designId]?.diagnostics

  /**
   * The public watch-face designs skipped, by id, with why, for the unauthenticated `/status`: only
   * a refusal for the design that is at its id now (created when it was), and only while anyone may
   * read that design. A design since made private, trashed, deleted or recreated is not named.
   */
  suspend fun skipped(service: UiBuilderServicePort): List<Pair<String, String>> =
    refusals.entries
      .sortedBy { it.key }
      .filter { (id, refusal) ->
        service.canRead(WatchFacePush.ANONYMOUS, id) &&
          push.designSummary(id)?.createdAtEpochMillis == refusal.designCreatedAtEpochMillis
      }
      .map { (id, refusal) ->
        id to "the packager refused it: ${refusal.diagnostics.firstOrNull() ?: "no diagnostic"}"
      }

  /** Whether [designId] is waiting for the scheduler. For tests and `/status`. */
  internal fun isPending(designId: String): Boolean = due.containsKey(designId)

  /** The owner published or unpublished [designId]: nothing is left for the scheduler to do. */
  fun forget(designId: String) {
    due.remove(designId)
    if (queue.status(designId)?.automatic == true) queue.clear(designId)
    if (refusals.remove(designId) != null) persistRefusals()
  }

  /**
   * Once, at start: the public watch-face designs that were public before this host built them (or
   * before the flag was on) have had no event, so look at each design on disk in a watch-face
   * catalog, one at a time with [sweepPauseMillis] between them, and build those whose current
   * revision has no APK. Each is read on its own ([WatchFacePush.designSummary]); nothing lists
   * every design through the service.
   */
  internal suspend fun sweep(service: UiBuilderServicePort) {
    if (!enabled) return
    for (head in storedDesigns()) {
      if (head.catalogSystemId != null && !push.isWatchFaceCatalog(head.catalogSystemId)) continue
      queue.exclusive.withLock {
        val retry = attempt(service, head.designId)
        // A build that failed around the packager is tried again, as the scheduler's own are.
        if (retry != null) scheduleRetry(head.designId, failures = 1, reason = retry)
      }
      if (sweepPauseMillis > 0) delay(sweepPauseMillis)
    }
  }

  /** Handle every design that is due now, one at a time. */
  internal suspend fun runDue(service: UiBuilderServicePort) {
    queue.exclusive.withLock {
      val now = push.clock()
      for ((designId, entry) in due.entries.sortedBy { it.value.atMillis }) {
        if (entry.atMillis > now) continue
        // Taken off first: an edit that lands while this one builds schedules it again.
        if (!due.remove(designId, entry)) continue
        val retry = attempt(service, designId)
        if (retry != null) scheduleRetry(designId, entry.failures + 1, retry)
      }
    }
  }

  /** [consider] [designId]; the reason to try again, or null. The caller holds the queue's lock. */
  private suspend fun attempt(service: UiBuilderServicePort, designId: String): String? {
    val retry = runCatching {
      consider(service, designId)
    }
      .getOrElse {
        System.err.println("serve: watchface-push: automatic listing of $designId: $it")
        it.message ?: it.javaClass.simpleName
      }
    queue.done(designId)
    return retry
  }

  /** Try [designId] again after a backoff that doubles with each of its [failures], to an hour. */
  private fun scheduleRetry(designId: String, failures: Int, reason: String) {
    val backoff = (retryMillis shl minOf(failures - 1, 6)).coerceAtMost(MAX_RETRY_MILLIS)
    val next = push.clock() + backoff
    due.putIfAbsent(designId, Due(next, failures))
    queue.record(
      designId,
      WatchFacePushBuildStatusV1(
        WatchFacePushBuildQueue.FAILED,
        reason = reason,
        nextRetryAtEpochMillis = next,
        automatic = true,
      ),
    )
  }

  /** A pending state for [designId] that nothing will now build is dropped. */
  private fun settle(designId: String) {
    if (queue.status(designId)?.state == WatchFacePushBuildQueue.PENDING) queue.clear(designId)
  }

  /**
   * Build [designId]'s latest revision if it should be listed; the reason to try again later, or
   * null when it is done with.
   */
  private suspend fun consider(service: UiBuilderServicePort, designId: String): String? {
    val summary =
      push.designSummary(designId)
        ?: run {
          // Gone: nothing of it is kept, its refusal included.
          if (refusals.remove(designId) != null) persistRefusals()
          settle(designId)
          return null
        }
    if (
      !push.isWatchFaceCatalog(summary.catalogPin.systemId) ||
        !service.canRead(WatchFacePush.ANONYMOUS, designId)
    ) {
      settle(designId)
      return null
    }
    val owner = summary.ownerActorId
    val existing = push.store.publication(designId)
    if (existing?.isExplicitFor(owner, summary.createdAtEpochMillis) == true) {
      forget(designId)
      return null
    }
    val current =
      existing != null &&
        existing.automatic &&
        existing.published &&
        existing.accountActorId == owner &&
        existing.designCreatedAtEpochMillis == summary.createdAtEpochMillis &&
        existing.revision == summary.revision
    if (current) {
      if (queue.status(designId)?.state == WatchFacePushBuildQueue.PENDING) {
        queue.record(
          designId,
          WatchFacePushBuildStatusV1(
            WatchFacePushBuildQueue.BUILT,
            version = existing!!.version,
            automatic = true,
          ),
        )
      }
      return null
    }
    val refused = refusals[designId]
    if (
      refused != null &&
        refused.revision == summary.revision &&
        refused.designCreatedAtEpochMillis == summary.createdAtEpochMillis
    ) {
      queue.record(designId, refusedStatus(refused.diagnostics))
      return null
    }
    // Read as the owner: the runtime names a design's owner only to its owner, and the publication
    // records the owner, as an explicit one does, so [WatchFacePush.isSameDesign] can tell a
    // recreated id apart.
    val snapshot =
      service.executeMapped(
        GetSnapshotRequestV1(designId = designId),
        AuthenticatedUiBuilderActor(owner),
      ) as? UiBuilderServiceResponse.Snapshot
        ?: run {
          settle(designId)
          return null
        }
    if (snapshot.snapshot.access?.ownerActorId != owner) {
      settle(designId)
      return null
    }
    val document = snapshot.snapshot.state.document
    // The same face an explicit publish would build: the export, with the design's pinned
    // drawables.
    val exported = push.sourceFor(document)
    val packaged = exported?.let {
      packagedWithPinnedDrawables(document, it, emptyMap(), push.assets)
    }
    val source = packaged?.source?.takeIf { packaged.missing.isEmpty() }
    val problem =
      when {
        exported == null -> "this design does not export to Watch Face Format"
        packaged!!.missing.isNotEmpty() -> packaged.missing.joinToString("; ")
        else -> source!!.problem()
      }
    if (source == null || problem != null) {
      refuse(
        designId,
        document.revision,
        document.createdAtEpochMillis,
        listOf("error[bundle]: $problem"),
      )
      return null
    }
    queue.building(designId, automatic = true)
    val staged =
      push.stage(
        candidate = {
          push.store.candidate(
            designId,
            owner,
            document.revision,
            document.title,
            source,
            document.createdAtEpochMillis,
            automatic = true,
          )
        },
        replaces = { stored ->
          stored == null || !stored.isExplicitFor(owner, document.createdAtEpochMillis)
        },
        requireBuild = true,
      )
    return when (staged) {
      is WatchFacePush.Staged.Committed -> {
        if (refusals.remove(designId) != null) persistRefusals()
        queue.record(
          designId,
          WatchFacePushBuildStatusV1(
            WatchFacePushBuildQueue.BUILT,
            version = staged.publication.version,
            automatic = true,
          ),
        )
        null
      }
      is WatchFacePush.Staged.Refused -> {
        if (staged.retryable) return staged.diagnostics.firstOrNull() ?: "the build failed"
        refuse(designId, document.revision, document.createdAtEpochMillis, staged.diagnostics)
        null
      }
      // Not buildable here now, or concurrent publishes: later.
      is WatchFacePush.Staged.Unavailable -> staged.build.reason
      WatchFacePush.Staged.Conflict -> "published concurrently"
      // The owner published it meanwhile.
      WatchFacePush.Staged.Superseded -> {
        settle(designId)
        null
      }
    }
  }

  private fun refusedStatus(diagnostics: List<String>) =
    WatchFacePushBuildStatusV1(
      WatchFacePushBuildQueue.REFUSED,
      reason = "the packager refused it",
      diagnostics = diagnostics,
      automatic = true,
    )

  private fun refuse(
    designId: String,
    revision: Long,
    createdAt: Long?,
    diagnostics: List<String>,
  ) {
    refusals[designId] = WatchFacePushStore.AutomaticRefusal(revision, createdAt, diagnostics)
    persistRefusals()
    queue.record(designId, refusedStatus(diagnostics))
    System.err.println(
      "serve: watchface-push: public design $designId revision $revision is not listed; the " +
        "packager refused it: ${diagnostics.firstOrNull()}",
    )
  }

  private fun persistRefusals() {
    runCatching { push.store.saveAutomaticRefusals(refusals.toMap()) }
  }

  companion object {
    const val DEFAULT_SETTLE_MILLIS = 10 * 60 * 1000L
    const val DEFAULT_ACCESS_SETTLE_MILLIS = 30 * 1000L
    const val DEFAULT_RETRY_MILLIS = 60 * 1000L
    const val DEFAULT_TICK_MILLIS = 30 * 1000L
    const val DEFAULT_SWEEP_PAUSE_MILLIS = 5 * 1000L
    private const val MAX_RETRY_MILLIS = 60 * 60 * 1000L
  }
}

/**
 * The design service, telling [onChanged] which design a successful write changed: an edit, a
 * rename, a restore, a document replacement, or a change of access (`access = true`). It reads
 * nothing and blocks on nothing; the listener only schedules
 * ([WatchFacePushAutomatic.noteChanged]).
 */
internal class WatchFacePushChangeFeed(
  private val delegate: UiBuilderServicePort,
  private val onChanged: (designId: String, access: Boolean) -> Unit,
) : UiBuilderServicePort {
  override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
    val response = delegate.execute(call)
    if (response !is UiBuilderServiceResponse.Error) {
      val request = call.request
      val changed =
        when (request) {
          is UiBuilderServiceRequest.ApplyOperation -> request.submission.designId to false
          is UiBuilderServiceRequest.ReplaceDesignDocument -> request.designId to false
          is UiBuilderServiceRequest.RestoreRevision -> request.designId to false
          is UiBuilderServiceRequest.RenameDesign -> request.designId to false
          is UiBuilderServiceRequest.RestoreDesign -> request.designId to true
          is UiBuilderServiceRequest.UpdateDesignAccess -> request.designId to true
          is UiBuilderServiceRequest.CreateDesign -> request.document.id to true
          else -> null
        }
      changed?.let { (id, access) -> runCatching { onChanged(id, access) } }
    }
    return response
  }

  override fun subscribe(
    call: UiBuilderSubscriptionCall,
    listener: (UiBuilderServiceUpdate) -> Unit,
  ): Closeable = delegate.subscribe(call, listener)
}
