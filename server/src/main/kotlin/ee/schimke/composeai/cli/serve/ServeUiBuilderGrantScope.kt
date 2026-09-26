package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignListItemV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetPort
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetRead
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetReadResult
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetWrite
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable

/**
 * Holds a grant that names its designs ([ServeAgentGrantStore.Grant.designIds]) to those designs.
 *
 * A grant's holder reaches the design service as an [AuthenticatedUiBuilderActor] carrying the
 * approver as [AuthenticatedUiBuilderActor.onBehalfOfActorId], and the service lets that actor do
 * whatever the approver may. The service has no notion of *which* designs the approver meant to
 * lend, so this port decorator applies it: for a call about a design the grant names, the actor
 * goes through unchanged; for a call about any other design, or about no design at all, the
 * delegation is removed and the holder is left with its own identity's access.
 *
 * Applied here, at the port, rather than in each route, for the same reason
 * [ServeUiBuilderVisibility.withDefault] is: every surface — the HTTP routes, the sidecars, the
 * editor's stream, MCP — reaches designs through the one port, and a limit that one of them forgot
 * would be a limit in name only. A grant that names no design, which is every agent grant and every
 * grant minted before designs could be named, is left exactly as it was.
 *
 * Listing is the one request that is about many designs. A limited holder's listing is its own,
 * with the named designs it can reach through the grant added on the first page, so the design it
 * asked for is where it expects to find it.
 */
internal object ServeUiBuilderGrantScope {

  /**
   * The designs [actorId] may reach through [onBehalfOfActorId], or null when that delegation names
   * none — [ServeAgentGrantStore.designScopeFor].
   */
  fun interface Lookup {
    fun designsFor(actorId: String, onBehalfOfActorId: String): Set<String>?
  }

  fun lookupOf(store: ServeAgentGrantStore): Lookup = Lookup(store::designScopeFor)

  /** [actor], with its delegation kept only when [designId] is one its grant names. */
  fun actorFor(
    actor: AuthenticatedUiBuilderActor,
    designId: String?,
    lookup: Lookup,
  ): AuthenticatedUiBuilderActor {
    val principal = actor.onBehalfOfActorId ?: return actor
    val designs = lookup.designsFor(actor.actorId, principal) ?: return actor
    return if (designId != null && designId in designs) actor else actor.withoutDelegation()
  }

  fun limit(delegate: UiBuilderServicePort, lookup: Lookup): UiBuilderServicePort =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
        val request = call.request
        if (request is UiBuilderServiceRequest.ListDesigns) return list(call, request)
        val actor = actorFor(call.actor, request.designId(), lookup)
        return delegate.execute(if (actor === call.actor) call else call.copy(actor = actor))
      }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable {
        val actor = actorFor(call.actor, call.designId, lookup)
        return delegate.subscribe(
          if (actor === call.actor) call else call.copy(actor = actor),
          listener,
        )
      }

      private suspend fun list(
        call: UiBuilderServiceCall,
        request: UiBuilderServiceRequest.ListDesigns,
      ): UiBuilderServiceResponse {
        val principal = call.actor.onBehalfOfActorId ?: return delegate.execute(call)
        val designs =
          lookup.designsFor(call.actor.actorId, principal) ?: return delegate.execute(call)
        val own = delegate.execute(call.copy(actor = call.actor.withoutDelegation()))
        if (own !is UiBuilderServiceResponse.Designs) return own
        // A named design is described as the grant reaches it, on the first page only; where the
        // holder's own listing also has it (shared with them as a viewer, say), that entry would
        // understate what they may do, so it gives way on every page.
        val lent = delegate.listed(call.actor, designs)
        val lentIds = lent.mapTo(mutableSetOf()) { it.designId }
        val rest = own.designs.filterNot { it.designId in lentIds }
        return UiBuilderServiceResponse.Designs(
          if (request.cursor == null) lent + rest else rest,
          own.nextCursor,
        )
      }
    }

  fun limit(delegate: UiBuilderAssetPort, lookup: Lookup): UiBuilderAssetPort =
    object : UiBuilderAssetPort {
      override suspend fun putAsset(write: UiBuilderAssetWrite): UiBuilderServiceResponse {
        val actor = actorFor(write.actor, write.designId, lookup)
        return delegate.putAsset(
          if (actor === write.actor) write
          else UiBuilderAssetWrite(actor, write.designId, write.assetKey, write.bytes)
        )
      }

      override suspend fun readAsset(read: UiBuilderAssetRead): UiBuilderAssetReadResult =
        delegate.readAsset(read.copy(actor = actorFor(read.actor, read.designId, lookup)))
    }

  /**
   * The listing entries for [designIds] that [actor] can see, read page by page until each is found
   * or the listing ends. Missing ones are designs the approver can no longer reach, or never could.
   */
  suspend fun UiBuilderServicePort.listed(
    actor: AuthenticatedUiBuilderActor,
    designIds: Set<String>,
  ): List<DesignListItemV1> {
    val found = mutableListOf<DesignListItemV1>()
    var cursor: String? = null
    do {
      val page =
        execute(UiBuilderServiceCall(actor, UiBuilderServiceRequest.ListDesigns(cursor, 200)))
          as? UiBuilderServiceResponse.Designs ?: break
      page.designs.filterTo(found) { it.designId in designIds }
      cursor = page.nextCursor
    } while (cursor != null && found.size < designIds.size)
    return found
  }

  private fun AuthenticatedUiBuilderActor.withoutDelegation(): AuthenticatedUiBuilderActor =
    AuthenticatedUiBuilderActor(actorId)

  /**
   * The one design a request is about, or null for a request about none — which a limited grant
   * therefore makes as its holder alone. Exhaustive on purpose: a request type added to the service
   * fails to compile here until someone decides which design it names.
   */
  internal fun UiBuilderServiceRequest.designId(): String? =
    when (this) {
      is UiBuilderServiceRequest.OpenDesign -> designId
      is UiBuilderServiceRequest.GetDesignAccess -> designId
      is UiBuilderServiceRequest.GetDesignActions -> designId
      is UiBuilderServiceRequest.UpdateDesignAccess -> designId
      is UiBuilderServiceRequest.PreviewCatalogUpgrade -> designId
      is UiBuilderServiceRequest.PreviewCurrentCatalogUpgrade -> designId
      is UiBuilderServiceRequest.ApplyOperation -> submission.designId
      is UiBuilderServiceRequest.GetSnapshot -> designId
      is UiBuilderServiceRequest.GetDelta -> designId
      is UiBuilderServiceRequest.UpdatePresence -> designId
      is UiBuilderServiceRequest.ExportDesign -> designId
      is UiBuilderServiceRequest.RenameDesign -> designId
      is UiBuilderServiceRequest.DeleteDesign -> designId
      is UiBuilderServiceRequest.ListRevisions -> designId
      is UiBuilderServiceRequest.RestoreRevision -> designId
      // A new design belongs to whoever creates it; under a limited grant that is the holder.
      is UiBuilderServiceRequest.CreateDesign,
      is UiBuilderServiceRequest.ExportDocument,
      is UiBuilderServiceRequest.ListDesigns,
      UiBuilderServiceRequest.ListCatalogs -> null
    }
}
