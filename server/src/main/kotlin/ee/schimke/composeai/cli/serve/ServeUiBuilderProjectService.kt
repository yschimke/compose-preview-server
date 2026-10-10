package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.service.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/** The project ACL is enforced on the editor, exports, sidecars, MCP and live subscriptions. */
internal class ServeUiBuilderProjectService(
  private val delegate: UiBuilderServicePort,
  private val projects: ServeUiBuilderProjectStore,
) : UiBuilderServicePort {
  override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
    val id = call.request.projectDesignId()
    val project = id?.let(projects::projectForDesign)
    if (project == null) {
      val result = delegate.execute(call)
      return if (result is UiBuilderServiceResponse.Designs)
        result.copy(
          designs =
            result.designs.filter { row ->
              val p = projects.projectForDesign(row.designId)
              p == null || p.role(call.actor) != null
            }
        )
      else result
    }
    val role =
      project.role(call.actor)
        ?: return projectServiceError(ServiceErrorCodeV1.NOT_FOUND, "design not found")
    val allowed = projectActions(role)
    val request = call.request
    val action =
      when (request) {
        is UiBuilderServiceRequest.OpenDesign,
        is UiBuilderServiceRequest.GetSnapshot,
        is UiBuilderServiceRequest.GetDelta,
        is UiBuilderServiceRequest.ListRevisions,
        is UiBuilderServiceRequest.GetDesignActions,
        is UiBuilderServiceRequest.UpdatePresence -> DesignAccessActionV1.READ
        is UiBuilderServiceRequest.ExportDesign -> DesignAccessActionV1.EXPORT
        is UiBuilderServiceRequest.UpdateDesignAccess,
        is UiBuilderServiceRequest.GetDesignAccess,
        is UiBuilderServiceRequest.DeleteDesign,
        is UiBuilderServiceRequest.MoveDesignHome ->
          return projectServiceError(
            ServiceErrorCodeV1.FORBIDDEN,
            "manage this file and its access from its project",
          )
        else -> DesignAccessActionV1.WRITE
      }
    if (action !in allowed)
      return projectServiceError(ServiceErrorCodeV1.FORBIDDEN, "project edit access required")
    if (request is UiBuilderServiceRequest.GetDesignActions)
      return UiBuilderServiceResponse.DesignActions(id, allowed)
    // Delegation is scoped to this one project-owned design. Audit still names the actual caller.
    val actions =
      when (request) {
        is UiBuilderServiceRequest.ReplaceDesignDocument ->
          request.document.nodes.values.flatMap { it.eventBindings.values.flatten() }
        is UiBuilderServiceRequest.ApplyOperation ->
          (request.submission as? UiBuilderSubmission.Batch)?.operations.orEmpty().flatMap {
            mutation ->
            when (mutation) {
              is ee.schimke.composeai.uibuilder.protocol.SetEventBindingMutationV1 ->
                mutation.actions
              is ee.schimke.composeai.uibuilder.protocol.InsertNodeMutationV1 ->
                mutation.node.eventBindings.values.flatten()
              else -> emptyList()
            }
          }
        else -> emptyList()
      }
    val projectDesigns = project.files.flatMap { it.designs.values }.toSet()
    if (
      actions.filterIsInstance<ee.schimke.composeai.uibuilder.protocol.NavigatePageActionV1>().any {
        it.pageKey !in projectDesigns
      }
    ) {
      return projectServiceError(
        ServiceErrorCodeV1.FORBIDDEN,
        "navigation must stay within this project",
      )
    }
    val actor = projectActor(call.actor, project)
    return delegate.execute(UiBuilderServiceCall(actor, request))
  }

  override fun subscribe(
    call: UiBuilderSubscriptionCall,
    listener: (UiBuilderServiceUpdate) -> Unit,
  ): Closeable {
    val project =
      projects.projectForDesign(call.designId) ?: return delegate.subscribe(call, listener)
    if (project.role(call.actor) == null) return Closeable {}
    val handle = AtomicReference<Closeable?>()
    val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    val subscription =
      delegate.subscribe(call.copy(actor = projectActor(call.actor, project))) { update ->
        val current = projects.read(project.id)
        if (current?.role(call.actor) != null) listener(update)
        else {
          closed.set(true)
          handle.get()?.close()
        }
      }
    handle.set(subscription)
    if (closed.get()) subscription.close()
    return subscription
  }
}

internal fun projectActor(
  actor: AuthenticatedUiBuilderActor,
  project: BuilderProject,
): AuthenticatedUiBuilderActor =
  if (actor.actorId == project.owner) actor.copy(onBehalfOfActorId = null)
  else AuthenticatedUiBuilderActor(actor.actorId, project.owner)

internal fun projectActions(role: ProjectRole): List<DesignAccessActionV1> =
  listOf(DesignAccessActionV1.READ, DesignAccessActionV1.EXPORT) +
    if (role == ProjectRole.VIEWER) emptyList() else listOf(DesignAccessActionV1.WRITE)

private fun projectServiceError(code: ServiceErrorCodeV1, message: String) =
  UiBuilderServiceResponse.Error(UiBuilderServiceError(code, message))

private fun UiBuilderServiceRequest.projectDesignId(): String? =
  when (this) {
    is UiBuilderServiceRequest.OpenDesign -> designId
    is UiBuilderServiceRequest.GetDesignActions -> designId
    is UiBuilderServiceRequest.GetDesignAccess -> designId
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
    is UiBuilderServiceRequest.MoveDesignHome -> designId
    is UiBuilderServiceRequest.ReplaceDesignDocument -> designId
    else -> null
  }

/** Asset reads and writes obey the same project membership as the document that names them. */
internal class ServeUiBuilderProjectAssets(
  private val delegate: UiBuilderAssetPort,
  private val projects: ServeUiBuilderProjectStore,
) : UiBuilderAssetPort {
  override suspend fun putAsset(write: UiBuilderAssetWrite): UiBuilderServiceResponse {
    val project = projects.projectForDesign(write.designId) ?: return delegate.putAsset(write)
    val role =
      project.role(write.actor)
        ?: return projectServiceError(ServiceErrorCodeV1.NOT_FOUND, "design not found")
    if (role == ProjectRole.VIEWER)
      return projectServiceError(ServiceErrorCodeV1.FORBIDDEN, "project edit access required")
    return delegate.putAsset(
      UiBuilderAssetWrite(
        projectActor(write.actor, project),
        write.designId,
        write.assetKey,
        write.bytes,
      )
    )
  }

  override suspend fun readAsset(read: UiBuilderAssetRead): UiBuilderAssetReadResult {
    val project = projects.projectForDesign(read.designId) ?: return delegate.readAsset(read)
    if (project.role(read.actor) == null)
      return UiBuilderAssetReadResult.Failed(
        UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "design not found")
      )
    return delegate.readAsset(read.copy(actor = projectActor(read.actor, project)))
  }
}
