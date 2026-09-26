package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.RevokeActorAccessMutationV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminDesignSummary
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminPort
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminRepair
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse

/**
 * Runtime UI-builder administration: what designs this host holds, repairing one the host cannot
 * serve, and removing one.
 *
 * The operator's tool, not a collaborator's: it lists every design regardless of ownership and
 * deletes without an ACL, which is why it is reachable only through the UI-builder admin gate:
 * `--admin-token`, or a configured `--ui-builder-admin-actors` identity acting as itself (a grant
 * that identity approved does not reach it). Deleting a design also drops its sidecars — the
 * reference overlay ([ServeUiBuilderReferenceStore]), comment board ([ServeUiBuilderCommentStore])
 * and links record ([ServeUiBuilderLinksStore]) — so nothing is left on disk that names a design no
 * longer there. Leaving one behind is not merely untidy: a design id recreated or re-imported later
 * would inherit the previous design's issue, pull request and thread. The sidecar removals are
 * best-effort: the design state is the record, and a stray overlay file is an orphan, not a
 * resurrected design.
 */
class ServeUiBuilderAdmin(
  private val service: UiBuilderAdminPort,
  private val references: ServeUiBuilderReferenceStore? = null,
  private val comments: ServeUiBuilderCommentStore? = null,
  private val links: ServeUiBuilderLinksStore? = null,
  /** The design list's card pictures, forgotten with the design they were drawn from. */
  private val thumbnails: ServeUiBuilderThumbnails? = null,
  /**
   * The same design service as an actor port, for [eraseActor]: a grant is revoked the way its
   * owner would revoke it, through the service's own access update, so the change is revisioned and
   * audited like every other. Null leaves grants alone and only [comments] are rewritten.
   */
  private val designs: UiBuilderServicePort? = null,
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

  /** Designs that remain usable but carry catalog-undeclared properties. */
  fun degraded(): Map<String, String> = service.adminDegradedDesigns()

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
    // A repaired document is a new picture, whatever revision it lands at.
    thumbnails?.evict(designId)
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

  /**
   * What [eraseActor] did.
   *
   * [ownedDesigns] are designs the actor owns. Those are not changed: a design cannot be left
   * without an owner, so the operator decides — delete it here, or have its owner transfer it.
   */
  data class ActorErasure(
    val actorId: String,
    val revokedFrom: List<String>,
    val ownedDesigns: List<String>,
    val commentBoards: Int,
  )

  /**
   * Remove one person from this host's UI-builder records, for an operator asked to.
   *
   * Their grant is revoked on every design that names them, and on every comment board their id is
   * replaced by [ERASED_ACTOR_ID] — as author, resolver, reader and reactor
   * ([ServeUiBuilderCommentStore.eraseActor]).
   *
   * Revision history is not rewritten. The runtime's operation log and commit audit are append-only
   * and hash-bound to the documents they produced, so each revision this actor committed still
   * names them until it ages out of retention or the design is deleted. That is stated here, and in
   * the log line, rather than discovered later.
   */
  suspend fun eraseActor(rawActorId: String): ActorErasure? {
    val actorId = rawActorId.trim()
    if (actorId.isEmpty() || actorId.equals(ERASED_ACTOR_ID, ignoreCase = true)) return null
    val revoked = mutableListOf<String>()
    val owned = mutableListOf<String>()
    val port = designs
    for (design in service.adminListDesigns()) {
      if (design.ownerActorId.equals(actorId, ignoreCase = true)) {
        owned += design.designId
        continue
      }
      if (port == null) continue
      if (revokeGrant(port, design, actorId)) revoked += design.designId
    }
    val boards =
      comments?.let { store ->
        runCatching { store.eraseActor(actorId, ERASED_ACTOR_ID) }.getOrDefault(0)
      } ?: 0
    onLog(
      "serve: admin removed UI-builder actor $actorId: grants revoked on ${revoked.size} " +
        "design(s), ${boards} comment board(s) rewritten, ${owned.size} owned design(s) left " +
        "for the operator; revision history keeps the id"
    )
    return ActorErasure(actorId, revoked, owned, boards)
  }

  /** Revoke [actorId]'s grant on [design] as its owner; true when there was one to revoke. */
  private suspend fun revokeGrant(
    port: UiBuilderServicePort,
    design: UiBuilderAdminDesignSummary,
    actorId: String,
  ): Boolean {
    if (design.collaborators == 0) return false
    val owner =
      runCatching { AuthenticatedUiBuilderActor(design.ownerActorId) }.getOrNull() ?: return false
    // Twice at most: an owner changing the access list between the read and the revoke moves its
    // revision on, and one retry against the new revision is enough for an operator's action.
    repeat(2) {
      val access =
        (port.execute(
            UiBuilderServiceCall(owner, UiBuilderServiceRequest.GetDesignAccess(design.designId))
          ) as? UiBuilderServiceResponse.DesignAccess)
          ?.access ?: return false
      val grants = access.actorGrants.filter { it.actorId.equals(actorId, ignoreCase = true) }
      if (grants.isEmpty()) return false
      val updated =
        port.execute(
          UiBuilderServiceCall(
            owner,
            UiBuilderServiceRequest.UpdateDesignAccess(
              design.designId,
              access.accessRevision,
              grants.map { RevokeActorAccessMutationV1(it.actorId) },
            ),
          )
        )
      if (updated is UiBuilderServiceResponse.DesignAccess) return true
    }
    onLog("serve: grant for $actorId on UI-builder design ${design.designId} not revoked")
    return false
  }

  fun delete(rawDesignId: String): Result {
    val designId = rawDesignId.trim()
    if (designId.isEmpty()) return Result.Invalid("design id is required")
    if (!service.adminDeleteDesign(designId)) return Result.NotFound(designId)
    onLog("serve: admin deleted UI-builder design $designId")
    thumbnails?.evict(designId)
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

/**
 * What an erased actor's id is replaced with in the records that keep what they said.
 *
 * One value for everybody rather than one per person: a per-person placeholder would still tie
 * every comment one person wrote together, which is most of what the id was.
 */
const val ERASED_ACTOR_ID: String = "removed-user"
