package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.UiBuilderAdminDesignSummary
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminPort
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminRepair

/**
 * Runtime UI-builder administration: what designs this host holds, repairing one the host cannot
 * serve, and removing one.
 *
 * The operator's tool, not a collaborator's: it lists every design regardless of ownership and
 * deletes without an ACL, which is why it is reachable only through the `--admin-token` routes
 * (`/admin/ui-builder`) and never through a `ui-builder-*` grant. Deleting a design also drops its
 * sidecars — the reference overlay ([ServeUiBuilderReferenceStore]) and the comment board
 * ([ServeUiBuilderCommentStore]) and the links record ([ServeUiBuilderLinksStore]) — so nothing is
 * left on disk that names a design no longer there. Leaving one behind is not merely untidy: a
 * design id recreated or re-imported later would inherit the previous design's issue, pull request
 * and thread. The sidecar removals are best-effort: the design state is the record, and a stray
 * overlay file is an orphan, not a resurrected design.
 */
class ServeUiBuilderAdmin(
  private val service: UiBuilderAdminPort,
  private val references: ServeUiBuilderReferenceStore? = null,
  private val comments: ServeUiBuilderCommentStore? = null,
  private val links: ServeUiBuilderLinksStore? = null,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  sealed interface Result {
    /** The design is gone, durably. */
    data class Deleted(val designId: String) : Result

    /** The design is servable again, at this revision. */
    data class Repaired(val designId: String, val revision: Long) : Result

    /** Nothing to delete: no design with this id exists here. */
    data class NotFound(val designId: String) : Result

    /** The request itself is malformed (a blank id). */
    data class Invalid(val reason: String) : Result
  }

  /** Every design on the host, oldest first. */
  fun list(): List<UiBuilderAdminDesignSummary> = service.adminListDesigns()

  /** Designs this build cannot serve, by id, each with the reason. */
  fun unusable(): Map<String, String> = service.adminUnusableDesigns()

  /**
   * Of those, the ones whose document cannot be produced at all.
   *
   * The two kinds of quarantine look alike in [unusable] and are not: a design the catalog outgrew
   * still has a document to download and repair, and a design whose stored files would not read
   * does not. Offering those two actions on a row where they cannot work is worse than not offering
   * them, because retiring it is then the operator's only move and they have no way to know that.
   */
  fun unreadable(): Set<String> = service.adminUnreadableDesigns()

  /**
   * The stored document for one design as JSON, or null when there is no such design.
   *
   * Deliberately reachable for a design the host cannot serve: it is the only way to get a
   * quarantined design's content off the host, and the alternative an operator is otherwise left
   * with is [delete].
   */
  fun document(rawDesignId: String): String? =
    rawDesignId.trim().takeIf { it.isNotEmpty() }?.let { service.adminDesignDocument(it) }

  /**
   * Put a repaired document back in place of a quarantined one.
   *
   * Logged like [delete] because it is the same kind of act: an operator changing a design nobody
   * granted them access to. What it repaired is worth the line — a host that quarantined a design
   * on Tuesday and served it again on Wednesday should say so somewhere an operator can read.
   */
  fun repair(rawDesignId: String, documentJson: String): Result {
    val designId = rawDesignId.trim()
    if (designId.isEmpty()) return Result.Invalid("design id is required")
    if (documentJson.isBlank()) return Result.Invalid("a repaired design document is required")
    return when (val outcome = service.adminRepairDesign(designId, documentJson)) {
      is UiBuilderAdminRepair.Repaired -> {
        onLog(
          "serve: admin repaired UI-builder design $designId at revision ${outcome.revision} " +
            "(was unusable: ${outcome.previousReason})"
        )
        Result.Repaired(designId, outcome.revision)
      }
      is UiBuilderAdminRepair.NotFound -> Result.NotFound(designId)
      is UiBuilderAdminRepair.Rejected -> Result.Invalid(outcome.reason)
    }
  }

  fun delete(rawDesignId: String): Result {
    val designId = rawDesignId.trim()
    if (designId.isEmpty()) return Result.Invalid("design id is required")
    if (!service.adminDeleteDesign(designId)) return Result.NotFound(designId)
    onLog("serve: admin deleted UI-builder design $designId")
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
    return Result.Deleted(designId)
  }
}
