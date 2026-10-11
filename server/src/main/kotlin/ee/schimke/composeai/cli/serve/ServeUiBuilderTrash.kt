package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.UiBuilderAdminPort
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Everything this host keeps beside a design rather than in it: the reference overlay, comment
 * board, links record, review verdicts, guidelines result, folder and card picture. [sweep] forgets them
 * all, and is called only once the design itself is gone for good. Before the trash, a delete swept
 * them at once. Now a deleted design can come back within its retention, with its discussion and
 * links intact, so they wait for its purge (`UI_BUILDER_TRASH.md` in compose-ui-builder).
 *
 * Leaving one behind is not merely untidy: a design id recreated later would inherit the previous
 * design's issue, pull request and thread. Each removal is best-effort, logged on failure, since the
 * design state is the record and a stray file is an orphan, not a resurrected design.
 */
internal class ServeUiBuilderSidecars(
  private val references: ServeUiBuilderReferenceStore? = null,
  private val comments: ServeUiBuilderCommentStore? = null,
  private val links: ServeUiBuilderLinksStore? = null,
  private val reviews: ServeUiBuilderReviewStore? = null,
  private val thumbnails: ServeUiBuilderThumbnails? = null,
  private val guidelineRecords: ServeUiBuilderGuidelineStore? = null,
  /** Which shared folder the design was filed in. */
  private val folders: ServeUiBuilderFolderStore? = null,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  fun sweep(designId: String) {
    thumbnails?.evict(designId)
    runCatching {
      if (folders?.move(designId, null) is FolderWriteResult.Failed) {
        onLog("serve: folder record for $designId not removed")
      }
    }
      .onFailure { onLog("serve: folder record for $designId not removed (${it.message})") }
    runCatching { references?.delete(designId) }
      .onFailure { onLog("serve: reference overlay for $designId not removed (${it.message})") }
    runCatching { comments?.delete(designId) }
      .onFailure { onLog("serve: comment board for $designId not removed (${it.message})") }
    runCatching {
      if (links?.delete(designId) == LinksDeleteResult.FAILED) {
        onLog("serve: links record for $designId not removed")
      }
    }
      .onFailure { onLog("serve: links record for $designId not removed (${it.message})") }
    runCatching {
      if (reviews?.delete(designId) == false) {
        onLog("serve: review record for $designId not removed")
      }
    }
      .onFailure { onLog("serve: review record for $designId not removed (${it.message})") }
    runCatching {
      if (guidelineRecords?.delete(designId) == false) {
        onLog("serve: guidelines record for $designId not removed")
      }
    }
      .onFailure { onLog("serve: guidelines record for $designId not removed (${it.message})") }
  }
}

/**
 * Empties the UI builder's trash of designs past their retention, and their [sidecars] with them.
 * The runtime runs no timer of its own, so this is the clock: once at [start] (a host that was down
 * past a design's deadline purges it on the way up) and every [periodMinutes] after.
 */
internal class ServeUiBuilderTrashPurger(
  private val service: UiBuilderAdminPort,
  private val sidecars: ServeUiBuilderSidecars,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  /** One pass: the ids purged, each with its sidecars swept. Never throws. */
  fun purge(): List<String> {
    val purged =
      runCatching { service.purgeExpiredTrash() }
        .getOrElse {
          onLog("serve: UI-builder trash purge failed (${it.message})")
          return emptyList()
        }
    purged.forEach { designId ->
      sidecars.sweep(designId)
      onLog("serve: purged UI-builder design $designId from the trash")
    }
    return purged
  }

  /** Purges now and on a daemon thread every [periodMinutes]; the executor dies with the JVM. */
  fun start(periodMinutes: Long = DEFAULT_PERIOD_MINUTES): ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ui-builder-trash-purge").apply { isDaemon = true }
      }
      .also { it.scheduleWithFixedDelay(::purge, 0, periodMinutes, TimeUnit.MINUTES) }

  companion object {
    const val DEFAULT_PERIOD_MINUTES: Long = 60
  }
}
