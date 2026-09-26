package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CommittedOperationV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.PresenceLeaveV1
import ee.schimke.composeai.uibuilder.protocol.PresenceUpsertV1
import ee.schimke.composeai.uibuilder.protocol.PresenceV1
import ee.schimke.composeai.uibuilder.protocol.RedoCommandV1
import ee.schimke.composeai.uibuilder.protocol.ServiceDeltaV1
import ee.schimke.composeai.uibuilder.protocol.ServiceSnapshotV1
import ee.schimke.composeai.uibuilder.protocol.UndoCommandV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderPublicAccess
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What a reader who reaches a design only through its public grant is told about the people on it.
 *
 * A public design is readable by anyone with its link, and that reader gets the design: its
 * document, its discussion, its history. What they do not need is *who* — the logins behind each
 * comment, reaction, acknowledgement, cursor and revision. Those are the collaborators' identities,
 * and a design being public is its owner's decision about the design, not about the people who
 * worked on it.
 *
 * So every identity other than the reader's own is replaced with a pseudonym, `collaborator-` and
 * eight hex characters. It is stable for one design within one process, so a public reader still
 * sees that two comments came from the same person and a cursor keeps its colour; it is keyed by a
 * secret drawn at startup, so the same person reads differently on two designs and after a restart,
 * and nothing about the login can be recovered from it. Display names go the same way
 * ([COLLABORATOR_DISPLAY_NAME]): they are usually a person's name.
 *
 * A reader with their own place on the design — its owner, anyone it is shared with by name, and an
 * agent acting for one of them — sees everything exactly as before. That is decided by
 * [publicReaderView], and every route that hands a public reader a board, a snapshot, a delta, a
 * presence update or a revision list goes through it and the one [PublicReaderView] it returns.
 */
internal class PublicReaderView(
  private val designId: String,
  /** The reader's own identities, which are never replaced: they are the reader's to see. */
  private val readerIds: Set<String>,
) {
  /** [actorId] as this reader may see it. */
  fun actor(actorId: String): String =
    if (actorId.isBlank() || readerIds.any { it.equals(actorId, ignoreCase = true) }) actorId
    else collaboratorPseudonym(designId, actorId)

  private fun isReader(actorId: String): Boolean = readerIds.any {
    it.equals(actorId, ignoreCase = true)
  }

  /**
   * The discussion, with every other participant replaced.
   *
   * Acknowledgements are dropped rather than relabelled except for the reader's own: they are
   * per-person read receipts, and "some collaborator read this at sequence 41" is the one thing a
   * public reader has no use for. Reactions keep their counts — the chip still says three — with
   * each other actor relabelled.
   */
  fun board(board: StoredCommentBoard): StoredCommentBoard =
    board.copy(
      threads =
        board.threads.map { thread ->
          thread.copy(
            resolvedBy = thread.resolvedBy?.let(::actor),
            acknowledgedBy = thread.acknowledgedBy.filterKeys(::isReader),
            comments =
              thread.comments.map { comment ->
                val own = isReader(comment.authorId)
                comment.copy(
                  authorId = actor(comment.authorId),
                  displayName = if (own) comment.displayName else COLLABORATOR_DISPLAY_NAME,
                  reactions = comment.reactions.mapValues { (_, actors) -> actors.map(::actor) },
                )
              },
          )
        }
    )

  fun snapshot(snapshot: ServiceSnapshotV1): ServiceSnapshotV1 =
    snapshot.copy(
      presence = snapshot.presence.map(::presence),
      // The runtime already answers the access list to the owner alone; a public reader is never
      // the owner, so this only makes that explicit at the one place a reader's view is built.
      access = null,
    )

  fun delta(delta: ServiceDeltaV1): ServiceDeltaV1 =
    delta.copy(operations = delta.operations.map(::operation))

  fun presence(presence: PresenceV1): PresenceV1 =
    if (isReader(presence.actorId)) presence
    else presence.copy(actorId = actor(presence.actorId), displayName = COLLABORATOR_DISPLAY_NAME)

  private fun operation(operation: CommittedOperationV1): CommittedOperationV1 =
    when (val submission = operation.submission) {
      is DesignCommandV1 ->
        operation.copy(submission = submission.copy(actorId = actor(submission.actorId)))
      is UndoCommandV1 ->
        operation.copy(submission = submission.copy(actorId = actor(submission.actorId)))
      is RedoCommandV1 ->
        operation.copy(submission = submission.copy(actorId = actor(submission.actorId)))
    }

  /** A service answer as this reader may see it; anything that names nobody passes through. */
  fun response(response: UiBuilderServiceResponse): UiBuilderServiceResponse =
    when (response) {
      is UiBuilderServiceResponse.Snapshot ->
        UiBuilderServiceResponse.Snapshot(snapshot(response.snapshot))
      is UiBuilderServiceResponse.Delta -> UiBuilderServiceResponse.Delta(delta(response.delta))
      is UiBuilderServiceResponse.Revisions ->
        response.copy(
          revisions = response.revisions.map { it.copy(actorId = it.actorId?.let(::actor)) }
        )
      else -> response
    }

  /** One frame of the design's update stream as this reader may see it. */
  fun update(update: UiBuilderServiceUpdate): UiBuilderServiceUpdate =
    when (update) {
      is UiBuilderServiceUpdate.Snapshot ->
        UiBuilderServiceUpdate.Snapshot(snapshot(update.snapshot))
      is UiBuilderServiceUpdate.Delta -> UiBuilderServiceUpdate.Delta(delta(update.delta))
      is UiBuilderServiceUpdate.Presence ->
        when (val change = update.update) {
          is PresenceUpsertV1 ->
            UiBuilderServiceUpdate.Presence(PresenceUpsertV1(presence(change.presence)))
          is PresenceLeaveV1 ->
            UiBuilderServiceUpdate.Presence(PresenceLeaveV1(actor(change.actorId)))
        }
      is UiBuilderServiceUpdate.Outcome -> update
    }

  companion object {
    /** What a public reader sees in place of somebody else's name. */
    const val COLLABORATOR_DISPLAY_NAME: String = "Collaborator"

    /** The prefix every pseudonym carries, so a client can tell one from a login. */
    const val COLLABORATOR_PREFIX: String = "collaborator-"

    private val key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    internal fun collaboratorPseudonym(designId: String, actorId: String): String {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(key, "HmacSHA256"))
      val digest =
        mac.doFinal((designId + "\u0000" + actorId.lowercase()).toByteArray(StandardCharsets.UTF_8))
      return COLLABORATOR_PREFIX +
        digest.take(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
  }
}

/**
 * The view [actor] gets of [designId]'s collaborators, or null when they see everyone as they are.
 *
 * Null for the owner, for anyone the design is shared with by name, for an agent acting for either,
 * and for a design this actor cannot read at all (nothing is served to them, so there is nothing to
 * shape). A [PublicReaderView] for the signed-out visitor, and for a signed-in actor who can read
 * the design only because it is public.
 *
 * The second case is decided without a new runtime request. An actor with any action beyond what a
 * public grant may carry has a grant of their own; one whose actions fit inside it either holds a
 * named viewer grant or reaches the design through the public grant alone, and the runtime lists a
 * design to an actor only in the first case — a public design "is listed only to the owner and the
 * people it was shared with by name" ([UiBuilderPublicAccess]). So only a read-only actor pays for
 * the listing, and only when a response about people is about to be sent.
 */
internal suspend fun UiBuilderServicePort.publicReaderView(
  actor: AuthenticatedUiBuilderActor,
  designId: String,
): PublicReaderView? {
  val actions = designActions(actor, designId) ?: return null
  val view = PublicReaderView(designId, actor.accessIdentities.toSet())
  if (actor.actorId == ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID) return view
  if (actions.any { it !in UiBuilderPublicAccess.PUBLIC_ACTIONS }) return null
  return if (listsDesign(actor, designId)) null else view
}

private suspend fun UiBuilderServicePort.listsDesign(
  actor: AuthenticatedUiBuilderActor,
  designId: String,
): Boolean {
  var cursor: String? = null
  do {
    val listed =
      execute(UiBuilderServiceCall(actor, UiBuilderServiceRequest.ListDesigns(cursor, 200)))
        as? UiBuilderServiceResponse.Designs ?: return false
    if (listed.designs.any { it.designId == designId }) return true
    cursor = listed.nextCursor
  } while (cursor != null)
  return false
}

/** [response] as [actor] may see it: shaped when it names people on a design they read publicly. */
internal suspend fun UiBuilderServicePort.shapeForReader(
  actor: AuthenticatedUiBuilderActor,
  response: UiBuilderServiceResponse,
): UiBuilderServiceResponse {
  val designId =
    when (response) {
      is UiBuilderServiceResponse.Snapshot -> response.snapshot.designId
      is UiBuilderServiceResponse.Delta -> response.delta.designId
      is UiBuilderServiceResponse.Revisions -> response.designId
      else -> return response
    }
  return publicReaderView(actor, designId)?.response(response) ?: response
}
