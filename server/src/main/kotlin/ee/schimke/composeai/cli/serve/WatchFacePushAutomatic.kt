package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A public `wff-watchface` design is on every watch without its owner publishing it.
 *
 * Making a design public is already the owner's decision that anyone may read it, so a public watch
 * face is listed on `GET /designs` to every caller, built from its latest revision. This keeps that
 * listing current: it walks the host's designs, and for each public one in a watch-face catalog
 * whose stored publication is not the owner's own ([WatchFacePushStore.Publication.isExplicitFor])
 * it stages the latest revision through [WatchFacePush.stage] — the one publish path — with
 * `automatic = true`.
 *
 * - **Lazily, off the request.** [refresh] starts one pass in the background and returns; the list
 *   shows what is already built, so it never waits on the packager. A design seen at a revision
 *   already considered is not exported again.
 * - **Versions** come from the same counter as an explicit publication, so they move when the
 *   face's content digest does and are never lowered or reused, and the package name is the one an
 *   explicit publish gives the same design.
 * - **Explicit wins.** An owner's publication — public, private, or a pinned revision — and an
 *   owner's unpublish are left alone; an explicit publish that lands during an automatic build is
 *   never overwritten by it (the commit re-checks under the store's lock).
 * - **Lifecycle.** A design made private, trashed or deleted is no longer readable anonymously, so
 *   [WatchFacePush.reach] stops listing its automatic publication at once (and never to its owner
 *   as a private face). A purged design is retired as before; a design recreated under its id is a
 *   different design and starts above every version already installed.
 * - **Refusals.** A face the packager refuses is skipped, with the packager's first line, on
 *   `/status` ([skipped]) and on its owner's publication status ([refusal]); the version already on
 *   watches, if any, stays listed. A refused revision is not rebuilt until the design changes.
 */
internal class WatchFacePushAutomatic(private val push: WatchFacePush) {
  private class Skip(val revision: Long, val diagnostics: List<String>)

  private val skips = ConcurrentHashMap<String, Skip>()

  /**
   * The design (its creation time) and revision each id was last considered at, so an unchanged
   * design is not re-exported, and one recreated under the id is.
   */
  private val considered = ConcurrentHashMap<String, Pair<Long?, Long>>()
  private val running = AtomicBoolean(false)

  /** Whether a pass was asked for while one ran; that pass runs again when it finishes. */
  private val again = AtomicBoolean(false)
  @Volatile private var job: Job? = null

  /** Start a pass in the background unless one is running; never waits for it. */
  fun refresh(service: UiBuilderServicePort) {
    if (!push.packagingStatus().available) return
    if (!running.compareAndSet(false, true)) {
      again.set(true)
      return
    }
    job =
      push.scope.launch {
        try {
          do {
            again.set(false)
            runCatching { pass(service) }
              .onFailure { System.err.println("serve: watchface-push: automatic listing: $it") }
          } while (again.get())
        } finally {
          running.set(false)
        }
      }
  }

  /** Wait for the pass in flight, if any. For tests. */
  internal suspend fun awaitIdle() {
    job?.join()
  }

  /** The packager's diagnostics for [designId]'s latest revision, when it refused it. */
  fun refusal(designId: String): List<String>? = skips[designId]?.diagnostics

  /** Every public watch-face design skipped, by id, with why. */
  fun skipped(): List<Pair<String, String>> =
    skips.entries
      .sortedBy { it.key }
      .map { (id, skip) ->
        id to "the packager refused it: ${skip.diagnostics.firstOrNull() ?: "no diagnostic"}"
      }

  /** The owner published or unpublished [designId]: whatever was recorded for it is stale. */
  fun forget(designId: String) {
    skips.remove(designId)
    considered.remove(designId)
  }

  internal suspend fun pass(service: UiBuilderServicePort) {
    val live = mutableSetOf<String>()
    for (summary in push.designs()) {
      val designId = summary.designId
      val owner = summary.ownerActorId
      if (!push.isWatchFaceCatalog(summary.catalogPin.systemId)) continue
      if (!service.canRead(WatchFacePush.ANONYMOUS, designId)) continue
      live += designId
      val existing = push.store.publication(designId)
      if (existing?.isExplicitFor(owner, summary.createdAtEpochMillis) == true) {
        forget(designId)
        continue
      }
      val current =
        existing != null &&
          existing.automatic &&
          existing.published &&
          existing.accountActorId == owner &&
          existing.designCreatedAtEpochMillis == summary.createdAtEpochMillis
      if (current && existing!!.revision == summary.revision) continue
      val seen = summary.createdAtEpochMillis to summary.revision
      if (considered[designId] == seen) continue
      considered[designId] = seen
      stageLatest(service, designId, owner)
    }
    skips.keys.retainAll(live)
    considered.keys.retainAll(live)
  }

  private suspend fun stageLatest(service: UiBuilderServicePort, designId: String, owner: String) {
    // Read as the owner: the runtime names a design's owner only to its owner, and the publication
    // records the owner, as an explicit one does, so [WatchFacePush.isSameDesign] can tell a
    // recreated id apart.
    val snapshot =
      service.executeMapped(
        GetSnapshotRequestV1(designId = designId),
        AuthenticatedUiBuilderActor(owner),
      ) as? UiBuilderServiceResponse.Snapshot ?: return
    if (snapshot.snapshot.access?.ownerActorId != owner) return
    val document = snapshot.snapshot.state.document
    val source = push.sourceFor(document)
    val problem =
      if (source == null) "this design does not export to Watch Face Format" else source.problem()
    if (source == null || problem != null) {
      skips[designId] = Skip(document.revision, listOf("error[bundle]: $problem"))
      return
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
    when (staged) {
      is WatchFacePush.Staged.Committed -> skips.remove(designId)
      is WatchFacePush.Staged.Refused -> {
        skips[designId] = Skip(document.revision, staged.diagnostics)
        System.err.println(
          "serve: watchface-push: public design $designId revision ${document.revision} is not " +
            "listed; the packager refused it: ${staged.diagnostics.firstOrNull()}",
        )
        // Not the packager's verdict on the face: the next pass builds it again.
        if (staged.retryable) considered.remove(designId)
      }
      // Not built here now, or the owner published it meanwhile: try again on a later pass.
      is WatchFacePush.Staged.Unavailable,
      WatchFacePush.Staged.Conflict -> considered.remove(designId)
      WatchFacePush.Staged.Superseded -> Unit
    }
  }
}
