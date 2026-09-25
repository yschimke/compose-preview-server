package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradeMutationV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewStatusV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminPort
import ee.schimke.composeai.uibuilder.service.UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse

/**
 * Moves every design whose catalog pin stopped resolving onto the pin now served, when the move
 * would succeed (#1054).
 *
 * A republished catalog swaps the running service's catalogs, and a design pinned to the runtime it
 * replaced cannot be opened until someone previews the recovery and applies it — the editor's
 * *Re-pin and reopen*. Doing that by hand for every design after every publish is busywork with
 * exactly one right answer whenever the preview is READY: the server computed the candidate, found
 * no error-severity issue, and the catalog it would move to accepts it. So the server applies those
 * itself. A BLOCKED preview is left alone for the owner, because it needs a decision.
 *
 * Every write runs as [ACTOR_ID] on behalf of the design's owner. The owner's access is the
 * authority — the service honours a delegate exactly as far as its principal reaches — and the
 * design's history records that the catalog refresh made the change, not the owner.
 *
 * The apply quotes the preview's revision and digest, so an edit that lands between the two is
 * refused as a conflict rather than overwritten; the next refresh tries again.
 */
internal class ServeUiBuilderCatalogRecovery(
  private val service: UiBuilderServicePort,
  private val admin: UiBuilderAdminPort,
  private val catalogs: UiBuilderCatalogExecutor,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  /** What one pass did, for the log line and the tests. */
  internal data class Result(
    val upgraded: List<String>,
    val blocked: List<String>,
    val failed: Map<String, String>,
  )

  suspend fun recoverStranded(): Result {
    val upgraded = mutableListOf<String>()
    val blocked = mutableListOf<String>()
    val failed = linkedMapOf<String, String>()
    for (design in admin.adminListDesigns()) {
      if (catalogs.resolve(design.catalogPin) != null) continue
      val actor = actorFor(design.ownerActorId) ?: continue
      val preview =
        when (
          val response =
            service.execute(
              UiBuilderServiceCall(
                actor,
                UiBuilderServiceRequest.PreviewCurrentCatalogUpgrade(design.designId),
              )
            )
        ) {
          is UiBuilderServiceResponse.CatalogUpgradePreview -> response.preview
          is UiBuilderServiceResponse.Error -> {
            failed[design.designId] = response.error.message
            continue
          }
          else -> {
            failed[design.designId] = "unexpected preview response ${response::class.simpleName}"
            continue
          }
        }
      val command = preview.recoveryCommand(actor.actorId)
      if (command == null) {
        blocked += design.designId
        continue
      }
      when (val applied = service.executeMapped(ApplyOperationRequestV1(command), actor)) {
        is UiBuilderServiceResponse.OperationOutcome ->
          if (applied.outcome is AcceptedOutcomeV1) upgraded += design.designId
          else failed[design.designId] = applied.outcome.toString()
        is UiBuilderServiceResponse.Error -> failed[design.designId] = applied.error.message
        else -> failed[design.designId] = "unexpected apply response ${applied::class.simpleName}"
      }
    }
    val result = Result(upgraded, blocked, failed)
    if (upgraded.isNotEmpty() || blocked.isNotEmpty() || failed.isNotEmpty()) {
      onLog(
        "serve: UI-builder catalog recovery upgraded ${upgraded.size} design(s)" +
          upgraded.joinToString(
            prefix = if (upgraded.isEmpty()) "" else " (",
            postfix = if (upgraded.isEmpty()) "" else ")",
          ) +
          (if (blocked.isEmpty()) ""
          else "; ${blocked.size} need their owner (blocked preview): ${blocked.joinToString()}") +
          (if (failed.isEmpty()) ""
          else
            "; ${failed.size} failed: " + failed.entries.joinToString { "${it.key}: ${it.value}" })
      )
    }
    return result
  }

  internal companion object {
    /** The identity every automatic recovery is recorded under. */
    const val ACTOR_ID = "system:catalog-refresh"

    /** Null for an owner the actor type cannot delegate for, which only a corrupt record has. */
    fun actorFor(ownerActorId: String): AuthenticatedUiBuilderActor? = runCatching {
      AuthenticatedUiBuilderActor(ACTOR_ID, onBehalfOfActorId = ownerActorId)
    }
      .getOrNull()

    /**
     * The same write the editor's *Re-pin and reopen* makes for a READY preview, and null for
     * anything else. The operation id is the preview's digest, so a retried pass that races a
     * browser doing the same recovery is one idempotent write, not two.
     */
    fun CatalogUpgradePreviewV1.recoveryCommand(actorId: String): DesignCommandV1? {
      if (status != CatalogUpgradePreviewStatusV1.READY) return null
      val targetHash = candidateDocumentHash ?: return null
      return DesignCommandV1(
        designId = designId,
        operationId = "catalog-recovery:$previewDigest",
        actorId = actorId,
        clientId = ACTOR_ID,
        baseRevision = baseRevision,
        operations =
          listOf(
            CatalogUpgradeMutationV1(
              sourceCatalogPin = sourceCatalogPin,
              targetCatalogPin = targetCatalogPin,
              sourceDocumentHash = sourceDocumentHash,
              targetDocumentHash = targetHash,
              previewDigest = previewDigest,
            )
          ),
      )
    }
  }
}
