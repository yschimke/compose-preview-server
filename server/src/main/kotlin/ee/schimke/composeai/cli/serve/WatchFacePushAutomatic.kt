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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
  private val settleMillis: Long = DEFAULT_SETTLE_MILLIS,
  private val accessSettleMillis: Long = DEFAULT_ACCESS_SETTLE_MILLIS,
  private val retryMillis: Long = DEFAULT_RETRY_MILLIS,
  /** How often the scheduler wakes; null: it never runs on its own (tests call [runDue]). */
  private val tickMillis: Long? = DEFAULT_TICK_MILLIS,
) : Closeable {
  private class Due(val atMillis: Long, val failures: Int = 0)

  private val due = ConcurrentHashMap<String, Due>()
  private val refusals = ConcurrentHashMap<String, WatchFacePushStore.AutomaticRefusal>()
  private val running = Mutex()
  private val started = AtomicBoolean(false)
  private val executor = lazy {
    Executors.newSingleThreadExecutor { task ->
      Thread(task, "watchface-push-automatic").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
      }
    }
  }
  private var scope: CoroutineScope? = null

  init {
    refusals.putAll(push.store.automaticRefusals())
  }

  /** Start the scheduler for [service]; once. */
  fun start(service: UiBuilderServicePort) {
    val tick = tickMillis ?: return
    if (!started.compareAndSet(false, true)) return
    val scope = CoroutineScope(SupervisorJob() + executor.value.asCoroutineDispatcher())
    this.scope = scope
    scope.launch {
      while (isActive) {
        delay(tick)
        runCatching { runDue(service) }
          .onFailure { System.err.println("serve: watchface-push: automatic listing: $it") }
      }
    }
  }

  override fun close() {
    if (executor.isInitialized()) executor.value.shutdownNow()
  }

  /**
   * [designId] changed: (re)schedule it [settleMillis] from now, or [accessSettleMillis] for a
   * change of access. Cheap and non-blocking; the design is not read here.
   */
  fun noteChanged(designId: String, access: Boolean = false) {
    if (designId.isBlank()) return
    val at = push.clock() + if (access) accessSettleMillis else settleMillis
    due.merge(designId, Due(at)) { old, new ->
      // An access change does not postpone an edit already waiting less; an edit always does.
      if (access) Due(minOf(old.atMillis, new.atMillis)) else new
    }
  }

  /** The packager's diagnostics for [designId]'s refused revision, when it refused it. */
  fun refusal(designId: String): List<String>? = refusals[designId]?.diagnostics

  /** Every public watch-face design skipped, by id, with why. */
  fun skipped(): List<Pair<String, String>> =
    refusals.entries
      .sortedBy { it.key }
      .map { (id, refusal) ->
        id to "the packager refused it: ${refusal.diagnostics.firstOrNull() ?: "no diagnostic"}"
      }

  /** Whether [designId] is waiting for the scheduler. For tests and `/status`. */
  internal fun isPending(designId: String): Boolean = due.containsKey(designId)

  /** The owner published or unpublished [designId]: nothing is left for the scheduler to do. */
  fun forget(designId: String) {
    due.remove(designId)
    if (refusals.remove(designId) != null) persistRefusals()
  }

  /** Handle every design that is due now, one at a time. */
  internal suspend fun runDue(service: UiBuilderServicePort) {
    running.withLock {
      val now = push.clock()
      for ((designId, entry) in due.entries.sortedBy { it.value.atMillis }) {
        if (entry.atMillis > now) continue
        // Taken off first: an edit that lands while this one builds schedules it again.
        if (!due.remove(designId, entry)) continue
        val retry = runCatching {
          consider(service, designId)
        }
          .getOrElse {
            System.err.println("serve: watchface-push: automatic listing of $designId: $it")
            true
          }
        if (retry) {
          val failures = entry.failures + 1
          val backoff = (retryMillis shl minOf(failures - 1, 6)).coerceAtMost(MAX_RETRY_MILLIS)
          due.putIfAbsent(designId, Due(push.clock() + backoff, failures))
        }
      }
    }
  }

  /** Build [designId]'s latest revision if it should be listed; true to try again later. */
  private suspend fun consider(service: UiBuilderServicePort, designId: String): Boolean {
    val summary = push.designSummary(designId) ?: return false
    if (!push.isWatchFaceCatalog(summary.catalogPin.systemId)) return false
    if (!service.canRead(WatchFacePush.ANONYMOUS, designId)) return false
    val owner = summary.ownerActorId
    val existing = push.store.publication(designId)
    if (existing?.isExplicitFor(owner, summary.createdAtEpochMillis) == true) {
      forget(designId)
      return false
    }
    val current =
      existing != null &&
        existing.automatic &&
        existing.published &&
        existing.accountActorId == owner &&
        existing.designCreatedAtEpochMillis == summary.createdAtEpochMillis &&
        existing.revision == summary.revision
    if (current) return false
    val refused = refusals[designId]
    if (
      refused != null &&
        refused.revision == summary.revision &&
        refused.designCreatedAtEpochMillis == summary.createdAtEpochMillis
    ) {
      return false
    }
    // Read as the owner: the runtime names a design's owner only to its owner, and the publication
    // records the owner, as an explicit one does, so [WatchFacePush.isSameDesign] can tell a
    // recreated id apart.
    val snapshot =
      service.executeMapped(
        GetSnapshotRequestV1(designId = designId),
        AuthenticatedUiBuilderActor(owner),
      ) as? UiBuilderServiceResponse.Snapshot ?: return false
    if (snapshot.snapshot.access?.ownerActorId != owner) return false
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
      return false
    }
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
        false
      }
      is WatchFacePush.Staged.Refused -> {
        if (staged.retryable) return true
        refuse(designId, document.revision, document.createdAtEpochMillis, staged.diagnostics)
        false
      }
      // Not buildable here now, or concurrent publishes: later.
      is WatchFacePush.Staged.Unavailable,
      WatchFacePush.Staged.Conflict -> true
      // The owner published it meanwhile.
      WatchFacePush.Staged.Superseded -> false
    }
  }

  private fun refuse(
    designId: String,
    revision: Long,
    createdAt: Long?,
    diagnostics: List<String>,
  ) {
    refusals[designId] = WatchFacePushStore.AutomaticRefusal(revision, createdAt, diagnostics)
    persistRefusals()
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
