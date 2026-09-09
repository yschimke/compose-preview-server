package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse

/**
 * What a design's own access control says about the actor in front of a sidecar route.
 *
 * ## Why the sidecars need this at all
 *
 * A sidecar — the reference overlay, the comment board, the links record — is not the design
 * document, so it never passes through the service's operation log and never meets the check that
 * `ApplyOperation` makes. Each route therefore has to ask the design's access control itself, and
 * until this existed the only question it could ask was "may this actor read the design", by
 * reading it. That was the right question for a `GET` and the wrong one for a `PUT`: an actor
 * shared in as a `VIEWER` who also holds the host's write capability — a repository-authorised
 * browser session, or an approved agent grant — passed a route's WRITE gate and then a READ check,
 * and could replace or erase material on a design it had no per-design WRITE action for.
 *
 * ## Which sidecars use which
 *
 * [canWrite] guards a mutation that changes what the design *is made of or is for*: the reference
 * overlay a design reproduces, and the links record naming its issue, frame, pull request and
 * thread. Those are authoring, and a viewer does not do them.
 *
 * The comment board deliberately stays on [canRead], and that is not an oversight. Its whole
 * motivating case is a designer who may see a screen saying "The play icon looks like a cross" on
 * it while an agent edits — see
 * [`UI_BUILDER_COMMENTS.md`](../../../../../../../../docs/design/UI_BUILDER_COMMENTS.md). Requiring
 * WRITE to comment would lock reviewers out of the review surface, which is the opposite of what
 * the board is for. Reading a design remains the right bar for discussing it.
 */
internal suspend fun UiBuilderServicePort.designActions(
  actor: AuthenticatedUiBuilderActor,
  designId: String,
): List<DesignAccessActionV1>? {
  if (designId.isBlank()) return null
  val response =
    execute(UiBuilderServiceCall(actor, UiBuilderServiceRequest.GetDesignActions(designId)))
  return (response as? UiBuilderServiceResponse.DesignActions)?.actions
}

/**
 * Whether [actor] may open [designId] at all.
 *
 * A design this actor may not read is indistinguishable here from one that does not exist, which is
 * what lets every sidecar answer 404 to both without leaking which it was.
 */
internal suspend fun UiBuilderServicePort.canRead(
  actor: AuthenticatedUiBuilderActor,
  designId: String,
): Boolean = designActions(actor, designId) != null

/**
 * Whether [actor] holds the design's own WRITE action on [designId] — not merely the host
 * capability that let the request reach the route.
 */
internal suspend fun UiBuilderServicePort.canWrite(
  actor: AuthenticatedUiBuilderActor,
  designId: String,
): Boolean = designActions(actor, designId)?.contains(DesignAccessActionV1.WRITE) == true
