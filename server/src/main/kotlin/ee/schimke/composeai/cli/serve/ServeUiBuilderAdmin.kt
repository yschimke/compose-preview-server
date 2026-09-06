package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.UiBuilderAdminDesignSummary
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminPort

/**
 * Runtime UI-builder administration: what designs this host holds, and removing one.
 *
 * The operator's tool, not a collaborator's: it lists every design regardless of ownership and
 * deletes without an ACL, which is why it is reachable only through the `--admin-token` routes
 * (`/admin/ui-builder`) and never through a `ui-builder-*` grant. Deleting a design also drops its
 * sidecars — the reference overlay ([ServeUiBuilderReferenceStore]) and the comment board
 * ([ServeUiBuilderCommentStore]) — so nothing is left on disk that names a design no longer there.
 * The sidecar removals are best-effort: the design state is the record, and a stray overlay file is
 * an orphan, not a resurrected design.
 */
class ServeUiBuilderAdmin(
  private val service: UiBuilderAdminPort,
  private val references: ServeUiBuilderReferenceStore? = null,
  private val comments: ServeUiBuilderCommentStore? = null,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  sealed interface Result {
    /** The design is gone, durably. */
    data class Deleted(val designId: String) : Result

    /** Nothing to delete: no design with this id exists here. */
    data class NotFound(val designId: String) : Result

    /** The request itself is malformed (a blank id). */
    data class Invalid(val reason: String) : Result
  }

  /** Every design on the host, oldest first. */
  fun list(): List<UiBuilderAdminDesignSummary> = service.adminListDesigns()

  fun delete(rawDesignId: String): Result {
    val designId = rawDesignId.trim()
    if (designId.isEmpty()) return Result.Invalid("design id is required")
    if (!service.adminDeleteDesign(designId)) return Result.NotFound(designId)
    onLog("serve: admin deleted UI-builder design $designId")
    runCatching { references?.delete(designId) }
      .onFailure { onLog("serve: reference overlay for $designId not removed (${it.message})") }
    runCatching { comments?.delete(designId) }
      .onFailure { onLog("serve: comment board for $designId not removed (${it.message})") }
    return Result.Deleted(designId)
  }
}
