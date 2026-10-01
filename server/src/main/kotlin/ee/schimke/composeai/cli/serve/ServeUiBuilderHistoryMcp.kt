package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradeMutationV1
import ee.schimke.composeai.uibuilder.protocol.CommittedOperationV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignHomeV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.RedoCommandV1
import ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.RejectionCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.UndoCommandV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceError
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceLimits
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A design's history, for an agent: its revisions, a restore, a fork that remembers its parent, and
 * a diff between any two retained revisions of any two designs
 * (yschimke/compose-preview-server#1256, phase 1 of yschimke/compose-ui-builder#375).
 *
 * The history page (`/ui-builder/{id}/history`) has offered revisions, restore and fork to a person
 * for a while; an agent had none of them and could not compare two documents at all. These four
 * tools are that page's operations over the same [UiBuilderServicePort] requests — `ListRevisions`,
 * `GetSnapshot` at a revision, `RestoreRevision` (forward, hash-checked) and `CreateDesign` — with
 * the same access rules, so the gate an agent reaches is the gate a person reaches:
 *
 * - [LIST_REVISIONS] and [DIFF_DESIGNS] are reads. The service reads each document *as the actor*,
 *   so a design they may not open is "no design this actor can read", never its contents.
 * - [RESTORE_REVISION] is a write, and takes the design's own WRITE action — its dry run too, so a
 *   dry run never promises a restore the real call would refuse.
 * - [FORK_DESIGN] is a write on the host, like the page's fork form: a fork is a new design owned
 *   by whoever forks it, made from a revision they could read.
 *
 * Each reply is its own small shape with a `summary` a person can read and a schema declared as the
 * tool's `outputSchema`; none is the released envelope, because the contract has no history
 * request.
 */
internal class ServeUiBuilderHistoryTools(
  private val service: UiBuilderServicePort,
  private val serverOrigin: () -> String?,
  /** Where fork ancestry is recorded; null on a host that records no links, which then says so. */
  private val links: ServeUiBuilderLinksStore?,
) {

  suspend fun call(tool: String, args: JsonObject, actor: AuthenticatedUiBuilderActor): String =
    when (tool) {
      LIST_REVISIONS ->
        UI_BUILDER_JSON.encodeToString(DesignRevisionsV1.serializer(), listRevisions(args, actor))
      RESTORE_REVISION ->
        UI_BUILDER_JSON.encodeToString(DesignRestoreV1.serializer(), restore(args, actor))
      FORK_DESIGN -> UI_BUILDER_JSON.encodeToString(DesignForkedV1.serializer(), fork(args, actor))
      DIFF_DESIGNS -> UI_BUILDER_JSON.encodeToString(DesignDiffV1.serializer(), diff(args, actor))
      else -> throw McpRequestException("unknown UI-builder history tool '$tool'")
    }

  // ---- list ------------------------------------------------------------------------------------

  private suspend fun listRevisions(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): DesignRevisionsV1 {
    val designId = args.requiredText("designId")
    val limit = (args.number("limit") ?: DEFAULT_REVISION_PAGE.toLong())
    if (limit !in 1..MAX_REVISION_PAGE.toLong()) {
      throw McpRequestException("`limit` must be between 1 and $MAX_REVISION_PAGE")
    }
    val before = args.number("before")
    val listed = revisions(actor, designId)
    val all = listed.revisions.sortedByDescending { it.revision }
    val page = all.filter { before == null || it.revision < before }.take(limit.toInt())
    val operations =
      page.minOfOrNull { it.sequence }?.let { operations(actor, designId, it) }.orEmpty()
    val rows = page.map { summary ->
      val document = documentAt(actor, designId, summary.revision, listed)
      val operation = operations[summary.sequence]?.let(::describe)
      DesignRevisionV1(
        revision = summary.revision,
        sequence = summary.sequence,
        current = summary.revision == listed.currentRevision,
        actorId = summary.actorId,
        updatedAtEpochMillis = summary.updatedAtEpochMillis,
        documentDigest = designDocumentDigest(document),
        nodes = document.nodes.size,
        operation = operation,
        summary =
          "r${summary.revision}" +
            (summary.actorId?.let { " by $it" } ?: "") +
            ": " +
            (operation?.summary
              ?: if (summary.revision == 0L) "created" else "not in the retained operation log"),
      )
    }
    val oldest = all.minOfOrNull { it.revision }
    val retention =
      DesignRetentionV1(
        oldestRetainedRevision = oldest,
        retainedRevisions = all.size,
        maximumRevisions = DEFAULT_LIMITS.retainedRevisionSnapshots,
        minimumRevisions = DEFAULT_LIMITS.minimumRetainedRevisionSnapshots,
        maximumRevisionBytes = DEFAULT_LIMITS.retainedRevisionBytes,
        retainedOperations = DEFAULT_LIMITS.retainedCommittedOperations,
        note =
          "Revisions older than r${oldest ?: 0} are gone: get, diff, restore and fork reach back " +
            "to the retention floor and no further. The service keeps at most " +
            "${DEFAULT_LIMITS.retainedRevisionSnapshots} whole-document revisions per design " +
            "within a ${DEFAULT_LIMITS.retainedRevisionBytes / (1024 * 1024)} MiB budget (never " +
            "fewer than ${DEFAULT_LIMITS.minimumRetainedRevisionSnapshots}), and the last " +
            "${DEFAULT_LIMITS.retainedCommittedOperations} operations.",
      )
    val nextBefore = page.lastOrNull()?.revision?.takeIf { last -> all.any { it.revision < last } }
    return DesignRevisionsV1(
      designId = designId,
      currentRevision = listed.currentRevision,
      revisions = rows,
      retention = retention,
      nextBefore = nextBefore,
      summary =
        "${rows.size} of ${all.size} retained revisions of $designId (current " +
          "r${listed.currentRevision}, oldest retained r${oldest ?: listed.currentRevision})" +
          (nextBefore?.let { "; pass before=$it for older" } ?: "") +
          rows.joinToString("") { "\n${it.summary}" },
    )
  }

  /**
   * The committed operations still in the log at or after [fromSequence], by the sequence each one
   * produced. Best effort: a log trimmed past a revision leaves that revision without one, which
   * the row says rather than inventing.
   */
  private suspend fun operations(
    actor: AuthenticatedUiBuilderActor,
    designId: String,
    fromSequence: Long,
  ): Map<Long, CommittedOperationV1> {
    val found = mutableMapOf<Long, CommittedOperationV1>()
    var after = (fromSequence - 1).coerceAtLeast(0)
    repeat(MAX_DELTA_PAGES) {
      when (
        val response =
          service.execute(
            UiBuilderServiceCall(
              actor,
              UiBuilderServiceRequest.GetDelta(designId, after, DELTA_PAGE),
            )
          )
      ) {
        is UiBuilderServiceResponse.Delta -> {
          response.delta.operations.forEach { found[it.outcome.sequence] = it }
          if (!response.delta.hasMore || response.delta.throughSequence <= after) return found
          after = response.delta.throughSequence
        }
        is UiBuilderServiceResponse.Error -> {
          val floor = response.error.retainedFromSequence
          if (
            response.error.code != ServiceErrorCodeV1.SNAPSHOT_REQUIRED ||
              floor == null ||
              floor <= after
          ) {
            return found
          }
          after = floor
        }
        else -> return found
      }
    }
    return found
  }

  private fun describe(committed: CommittedOperationV1): DesignRevisionOperationV1 =
    when (val submission = committed.submission) {
      is DesignCommandV1 -> {
        val types = submission.operations.map(::mutationType)
        val restored =
          submission.operations
            .singleOrNull()
            ?.let { it as? CatalogUpgradeMutationV1 }
            ?.previewDigest
            ?.takeIf { it.startsWith(RESTORE_DIGEST_PREFIX) }
            ?.removePrefix(RESTORE_DIGEST_PREFIX)
            ?.toLongOrNull()
        DesignRevisionOperationV1(
          operationId = submission.operationId,
          kind = if (restored != null) "restore" else "batch",
          clientId = submission.clientId,
          mutations = types,
          restoredRevision = restored,
          summary =
            if (restored != null) "restored r$restored"
            else
              types
                .groupingBy { it }
                .eachCount()
                .entries
                .joinToString(", ") { (type, count) -> if (count == 1) type else "$type ×$count" }
                .ifEmpty { "no operations" },
        )
      }
      is UndoCommandV1 ->
        DesignRevisionOperationV1(
          operationId = submission.operationId,
          kind = "undo",
          clientId = submission.clientId,
          summary = "undo of ${submission.targetOperationId}",
        )
      is RedoCommandV1 ->
        DesignRevisionOperationV1(
          operationId = submission.operationId,
          kind = "redo",
          clientId = submission.clientId,
          summary = "redo of ${submission.targetUndoOperationId}",
        )
      else ->
        DesignRevisionOperationV1(
          operationId = committed.outcome.sequence.toString(),
          kind = "other",
          summary = "an operation this server cannot describe",
        )
    }

  private fun mutationType(mutation: DesignMutationV1): String =
    UI_BUILDER_JSON.encodeToJsonElement(DesignMutationV1.serializer(), mutation)
      .jsonObject["type"]
      ?.jsonPrimitive
      ?.contentOrNull ?: mutation::class.simpleName.orEmpty()

  // ---- restore ---------------------------------------------------------------------------------

  private suspend fun restore(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): DesignRestoreV1 {
    val designId = args.requiredText("designId")
    val revision = args.requiredNumber("revision")
    val baseRevision = args.requiredNumber("baseRevision")
    val dryRun = args["dryRun"]?.jsonPrimitive?.booleanOrNull == true
    val listed = revisions(actor, designId)
    if (service.designActions(actor, designId)?.contains(DesignAccessActionV1.WRITE) != true) {
      throw McpRequestException("design `$designId` does not grant this actor write access")
    }
    val target = documentAt(actor, designId, revision, listed)
    val current = documentAt(actor, designId, null, listed)
    val diff = UiBuilderDocumentDiff.diff(current, target)
    val stale = baseRevision != current.revision
    if (revision == current.revision) {
      throw McpRequestException(
        "revision $revision is already the current revision of `$designId`; there is nothing to " +
          "restore"
      )
    }
    if (dryRun) {
      return DesignRestoreV1(
        designId = designId,
        revision = revision,
        baseRevision = baseRevision,
        currentRevision = current.revision,
        dryRun = true,
        applied = false,
        stale = stale,
        diff = diff,
        summary =
          "Dry run: restoring r$revision onto r${current.revision} would apply this as a new " +
            "revision. " +
            (if (stale)
              "baseRevision $baseRevision is stale (current is r${current.revision}); quote " +
                "${current.revision} when you restore. "
            else "") +
            diff.summary,
      )
    }
    val operationId = args.text("operationId") ?: ("restore-" + java.util.UUID.randomUUID())
    val response =
      service.execute(
        UiBuilderServiceCall(
          actor,
          UiBuilderServiceRequest.RestoreRevision(designId, revision, baseRevision, operationId),
        )
      )
    val outcome =
      when (response) {
        is UiBuilderServiceResponse.OperationOutcome -> response.outcome
        is UiBuilderServiceResponse.Error -> throw refusal(designId, revision, response.error)
        else -> throw McpRequestException("the design service did not answer the restore")
      }
    return when (outcome) {
      is AcceptedOutcomeV1 ->
        DesignRestoreV1(
          designId = designId,
          revision = revision,
          baseRevision = baseRevision,
          currentRevision = outcome.committedRevision,
          dryRun = false,
          applied = true,
          stale = false,
          committedRevision = outcome.committedRevision,
          documentDigest = outcome.documentHash,
          operationId = operationId,
          diff = diff,
          summary =
            "Restored r$revision of $designId as r${outcome.committedRevision}" +
              (if (outcome.idempotentReplay) " (a replay of operation $operationId)" else "") +
              ". The revisions in between are kept: restore again to undo. " +
              diff.summary,
        )
      is RejectedOutcomeV1 ->
        throw McpRequestException(
          if (outcome.code == RejectionCodeV1.REVISION_MISMATCH)
            "stale: baseRevision $baseRevision is not the current revision of `$designId` " +
              "(${outcome.currentRevision}); somebody changed it. Dry-run again and quote " +
              "${outcome.currentRevision}. Nothing was restored."
          else "${outcome.code.name}: ${outcome.message}. Nothing was restored."
        )
    }
  }

  // ---- fork ------------------------------------------------------------------------------------

  private suspend fun fork(args: JsonObject, actor: AuthenticatedUiBuilderActor): DesignForkedV1 {
    val designId = args.requiredText("designId")
    val requested = args.number("revision")
    val source = documentAt(actor, designId, requested, listed = null)
    val forkId = args.text("newDesignId") ?: defaultForkId(designId, source.revision)
    val document =
      forkedDesignDocument(source, designId, forkId, args.text("title"))
        .withServerHome(serverOrigin())
    val created =
      when (
        val response =
          service.execute(
            UiBuilderServiceCall(actor, UiBuilderServiceRequest.CreateDesign(document))
          )
      ) {
        is UiBuilderServiceResponse.Snapshot -> response.snapshot.state.document
        is UiBuilderServiceResponse.Error ->
          throw McpRequestException(
            if (
              response.error.code == ServiceErrorCodeV1.BAD_REQUEST &&
                service.canRead(actor, forkId)
            )
              "design id `$forkId` is already taken; choose another `newDesignId`. Nothing was " +
                "forked."
            else "${response.error.code.name}: ${response.error.message}. Nothing was forked."
          )
        else -> throw McpRequestException("the design service did not answer the fork")
      }
    val (point, recorded) = recordDesignFork(links, source, designId, forkId)
    return DesignForkedV1(
      designId = created.id,
      title = created.title,
      revision = created.revision,
      home = created.home,
      forkedFrom = point,
      ancestryRecorded = recorded,
      summary =
        "Forked $designId@r${source.revision} as ${created.id} (\"${created.title}\"). " +
          (if (recorded)
            "Its parent is recorded: ${ServeUiBuilderMcp.GET_LINKS} on either shows the link. "
          else "This host records no links, so the fork's parent is not kept beyond this reply. ") +
          "The fork is a separate design, not a branch: nothing flows back to $designId on its " +
          "own. Tell the person it exists, and bring a chosen change back with " +
          "${ServeUiBuilderMcp.APPLY} on $designId (${DIFF_DESIGNS} shows what differs).",
    )
  }

  // ---- diff ------------------------------------------------------------------------------------

  private suspend fun diff(args: JsonObject, actor: AuthenticatedUiBuilderActor): DesignDiffV1 {
    val a = args.side("a")
    val b = if (args["b"] == null) DiffSide(a.designId, null) else args.side("b")
    val before = documentAt(actor, a.designId, a.revision, listed = null)
    val after = documentAt(actor, b.designId, b.revision, listed = null)
    return UiBuilderDocumentDiff.diff(before, after)
  }

  private data class DiffSide(val designId: String, val revision: Long?)

  private fun JsonObject.side(name: String): DiffSide {
    val side =
      this[name] as? JsonObject
        ?: throw McpRequestException("`$name` is required: {\"designId\": …, \"revision\"?: …}")
    return DiffSide(side.requiredText("designId"), side.number("revision"))
  }

  // ---- shared ----------------------------------------------------------------------------------

  private suspend fun revisions(
    actor: AuthenticatedUiBuilderActor,
    designId: String,
  ): UiBuilderServiceResponse.Revisions =
    when (
      val response =
        service.shapeForReader(
          actor,
          service.execute(
            UiBuilderServiceCall(actor, UiBuilderServiceRequest.ListRevisions(designId))
          ),
        )
    ) {
      is UiBuilderServiceResponse.Revisions -> response
      is UiBuilderServiceResponse.Error -> throw refusal(designId, null, response.error)
      else -> throw McpRequestException("the design service did not list revisions")
    }

  /**
   * One design's document at [revision] (null: the current one), read as [actor].
   *
   * A revision that never existed and one that existed but is no longer retained are the same
   * `SNAPSHOT_REQUIRED` to the service; they are told apart here, because "pick another number" and
   * "that is gone, here is how far back you can go" ask different things of an agent.
   */
  private suspend fun documentAt(
    actor: AuthenticatedUiBuilderActor,
    designId: String,
    revision: Long?,
    listed: UiBuilderServiceResponse.Revisions?,
  ): DesignDocumentV1 {
    if (revision != null && revision < 0) {
      throw McpRequestException("revision $revision does not exist: revisions start at 0")
    }
    return when (
      val response =
        service.execute(
          UiBuilderServiceCall(actor, UiBuilderServiceRequest.GetSnapshot(designId, revision))
        )
    ) {
      is UiBuilderServiceResponse.Snapshot -> response.snapshot.state.document
      is UiBuilderServiceResponse.Error -> {
        // A retention refusal names the floor, so read the list if the caller had not.
        val known =
          listed
            ?: if (response.error.code != ServiceErrorCodeV1.SNAPSHOT_REQUIRED) null
            else
              service.execute(
                UiBuilderServiceCall(actor, UiBuilderServiceRequest.ListRevisions(designId))
              ) as? UiBuilderServiceResponse.Revisions
        throw refusal(designId, revision, response.error, known)
      }
      else -> throw McpRequestException("the design service did not answer with a document")
    }
  }

  private suspend fun refusal(
    designId: String,
    revision: Long?,
    error: UiBuilderServiceError,
    listed: UiBuilderServiceResponse.Revisions? = null,
  ): McpRequestException =
    McpRequestException(
      when (error.code) {
        ServiceErrorCodeV1.NOT_FOUND,
        ServiceErrorCodeV1.FORBIDDEN,
        ServiceErrorCodeV1.UNAUTHORIZED -> "no design `$designId` this actor can read"
        ServiceErrorCodeV1.SNAPSHOT_REQUIRED -> {
          val current = error.currentRevision ?: listed?.currentRevision
          if (revision != null && current != null && revision > current) {
            "revision $revision of `$designId` does not exist: the current revision is $current"
          } else {
            val oldest = listed?.revisions?.minOfOrNull { it.revision }
            "revision ${revision ?: "?"} of `$designId` is past the retention floor and no " +
              "longer retained" +
              (oldest?.let { ": the oldest retained revision is $it" } ?: "") +
              (current?.let { " (current $it)" } ?: "") +
              ". ${LIST_REVISIONS} lists what is still reachable."
          }
        }
        else -> "${error.code.name}: ${error.message}"
      }
    )

  private fun JsonObject.text(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

  private fun JsonObject.requiredText(name: String): String =
    text(name) ?: throw McpRequestException("`$name` is required")

  private fun JsonObject.number(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

  private fun JsonObject.requiredNumber(name: String): Long =
    number(name) ?: throw McpRequestException("`$name` is required and must be an integer")

  companion object {
    const val LIST_REVISIONS = "ui_builder_list_revisions"
    const val RESTORE_REVISION = "ui_builder_restore_revision"
    const val FORK_DESIGN = "ui_builder_fork_design"
    const val DIFF_DESIGNS = "ui_builder_diff_designs"

    /** In the order a session uses them: look, compare, then act. */
    val TOOL_NAMES = listOf(LIST_REVISIONS, DIFF_DESIGNS, RESTORE_REVISION, FORK_DESIGN)

    const val REVISIONS_SCHEMA = "compose-preview/ui-builder-revisions/v1"
    const val RESTORE_SCHEMA = "compose-preview/ui-builder-restore/v1"
    const val FORKED_SCHEMA = "compose-preview/ui-builder-forked/v1"

    private const val DEFAULT_REVISION_PAGE = 20
    private const val MAX_REVISION_PAGE = 128
    private const val DELTA_PAGE = 1_024
    private const val MAX_DELTA_PAGES = 4

    /** How `ui-builder-runtime` marks a restore's upgrade mutation; see its `RestoreRevision`. */
    private const val RESTORE_DIGEST_PREFIX = "restore-revision:"

    /** The retention policy the service runs with here: the runtime's defaults. */
    private val DEFAULT_LIMITS = UiBuilderServiceLimits()

    /** What each tool's `structuredContent` is, generated from the classes the replies encode. */
    fun outputSchema(name: String): JsonObject? =
      when (name) {
        LIST_REVISIONS -> revisionsOutput
        RESTORE_REVISION -> restoreOutput
        FORK_DESIGN -> forkedOutput
        DIFF_DESIGNS -> diffOutput
        else -> null
      }

    private val revisionsOutput by lazy {
      SerialDescriptorJsonSchema.output(
        DesignRevisionsV1.serializer().descriptor,
        REVISIONS_SCHEMA,
      )
    }
    private val restoreOutput by lazy {
      SerialDescriptorJsonSchema.output(DesignRestoreV1.serializer().descriptor, RESTORE_SCHEMA)
    }
    private val forkedOutput by lazy {
      SerialDescriptorJsonSchema.output(DesignForkedV1.serializer().descriptor, FORKED_SCHEMA)
    }
    private val diffOutput by lazy {
      SerialDescriptorJsonSchema.output(
        DesignDiffV1.serializer().descriptor,
        UI_BUILDER_DESIGN_DIFF_SCHEMA,
      )
    }

    private const val SIDE_SCHEMA =
      """{"type":"object","properties":{"designId":{"type":"string"},"revision":{"type":"integer","description":"A retained revision. Omit for the current one."}},"required":["designId"],"additionalProperties":false}"""

    fun declarations(tool: (String, String, String) -> JsonObject): List<JsonObject> =
      listOf(
        tool(
          LIST_REVISIONS,
          "A design's retained revisions, newest first: each one's number, who made it, when, " +
            "the operation that produced it (`setProperty ×2, insertNode`, `undo`, `restored " +
            "r3`) and its document digest. `retention` says how far back the history still " +
            "reaches — older revisions are gone, and get, diff, restore and fork stop at that " +
            "floor. Page with `before`. Read access.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "limit":{"type":"integer","minimum":1,"maximum":$MAX_REVISION_PAGE,"description":"Revisions per page. Defaults to $DEFAULT_REVISION_PAGE."},
            "before":{"type":"integer","description":"Only revisions older than this one; pass the previous reply's `nextBefore`."}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
        tool(
          DIFF_DESIGNS,
          "Compare two designs, or two retained revisions of one: nodes added, removed and " +
            "moved, and properties, modifiers and other fields changed, each with its node id " +
            "and path (`root/slot[index]/node`), plus changed document fields (title, " +
            "environment, state). Works across a design and its fork. `b` defaults to the " +
            "current revision of `a`'s design. Values are the protocol's own JSON, ready to " +
            "send back through ${ServeUiBuilderMcp.APPLY}. Read access to both.",
          """
          {"type":"object","properties":{
            "a":$SIDE_SCHEMA,
            "b":$SIDE_SCHEMA
          },"required":["a"],"additionalProperties":false}
          """,
        ),
        tool(
          RESTORE_REVISION,
          "Make a retained revision the design's current content again, **forward**: it is " +
            "committed as a new revision and nothing after it is lost, so a restore is undone by " +
            "restoring again. `baseRevision` must be the current revision (a restore that raced " +
            "an edit is refused as stale). `dryRun: true` writes nothing and returns the diff " +
            "the restore would apply. Needs write access to the design.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "revision":{"type":"integer","description":"The retained revision to restore."},
            "baseRevision":{"type":"integer","description":"The design's current revision, from ${ServeUiBuilderMcp.GET_DESIGN} or $LIST_REVISIONS."},
            "dryRun":{"type":"boolean","description":"Return the diff without restoring. Defaults to false."},
            "operationId":{"type":"string","description":"Makes a retry idempotent. Defaults to a fresh id."}
          },"required":["designId","revision","baseRevision"],"additionalProperties":false}
          """,
        ),
        tool(
          FORK_DESIGN,
          "Make a new design from one revision of another — yours to edit, owned by you, with " +
            "its parent recorded: the fork's `forkedFrom` (design, revision, document digest) " +
            "and the parent's `forks` both show in ${ServeUiBuilderMcp.GET_LINKS}. A fork is not " +
            "a branch: it never inherits the parent's canonical home and nothing flows back on " +
            "its own, so tell the person it exists and bring a chosen change back with " +
            "${ServeUiBuilderMcp.APPLY} on the parent. Needs write access on this server and " +
            "read access to the parent.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string","description":"The design to fork."},
            "revision":{"type":"integer","description":"A retained revision. Omit for the current one."},
            "title":{"type":"string","description":"The fork's title. Defaults to the parent's, marked with the revision."},
            "newDesignId":{"type":"string","description":"The fork's id. Defaults to one derived from the parent; an id already taken is refused."}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
      )
  }
}

@Serializable
internal data class DesignRevisionsV1(
  val schema: String = ServeUiBuilderHistoryTools.REVISIONS_SCHEMA,
  val designId: String,
  val currentRevision: Long,
  /** Newest first. */
  val revisions: List<DesignRevisionV1>,
  val retention: DesignRetentionV1,
  /** Pass as `before` for the next, older page; absent on the last one. */
  val nextBefore: Long? = null,
  val summary: String,
)

@Serializable
internal data class DesignRevisionV1(
  val revision: Long,
  val sequence: Long,
  val current: Boolean,
  /** Who committed the operation that produced it; absent for the design's creation. */
  val actorId: String? = null,
  val updatedAtEpochMillis: Long? = null,
  /** [designDocumentDigest] of the document at this revision. */
  val documentDigest: String,
  val nodes: Int,
  /** The operation that produced it, while the operation log still holds it. */
  val operation: DesignRevisionOperationV1? = null,
  val summary: String,
)

@Serializable
internal data class DesignRevisionOperationV1(
  val operationId: String,
  /** `batch`, `restore`, `undo`, `redo` or `other`. */
  val kind: String,
  val clientId: String? = null,
  /** The mutation types of a batch, in order. */
  val mutations: List<String> = emptyList(),
  /** For a restore, the revision it brought back. */
  val restoredRevision: Long? = null,
  val summary: String,
)

/** How far back a design's history reaches, and the policy that decides it. */
@Serializable
internal data class DesignRetentionV1(
  /** The retention floor: nothing older can be read, diffed, restored or forked. */
  val oldestRetainedRevision: Long? = null,
  val retainedRevisions: Int,
  val maximumRevisions: Int,
  val minimumRevisions: Int,
  val maximumRevisionBytes: Long,
  val retainedOperations: Int,
  val note: String,
)

@Serializable
internal data class DesignRestoreV1(
  val schema: String = ServeUiBuilderHistoryTools.RESTORE_SCHEMA,
  val designId: String,
  /** The revision restored, or that would be. */
  val revision: Long,
  val baseRevision: Long,
  /** The design's revision now: after the restore, or unchanged by a dry run. */
  val currentRevision: Long,
  val dryRun: Boolean,
  val applied: Boolean,
  /** A dry run whose `baseRevision` is not current: the real call would be refused. */
  val stale: Boolean,
  val committedRevision: Long? = null,
  /**
   * The service's hash of the design's document after the restore, as list_revisions reports it.
   */
  val documentDigest: String? = null,
  val operationId: String? = null,
  /** From the current document (`a`) to the restored one (`b`). */
  val diff: DesignDiffV1,
  val summary: String,
)

@Serializable
internal data class DesignForkedV1(
  val schema: String = ServeUiBuilderHistoryTools.FORKED_SCHEMA,
  val designId: String,
  val title: String,
  val revision: Long,
  /**
   * The fork's own canonical home: this server under the fork's id when the host has a public
   * origin, absent otherwise. Never the parent's.
   */
  val home: DesignHomeV1? = null,
  val forkedFrom: DesignForkPointV1,
  /** Whether the host kept the ancestry (it does wherever it records links). */
  val ancestryRecorded: Boolean,
  val summary: String,
)
