package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CommandConflictV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderBranch
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchCall
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchMergeCommand
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchMergeCommandStatus
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchMergeReport
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchPort
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchStatus
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Design branches, for an agent (phase 2, server half, of yschimke/compose-ui-builder#375; the
 * runtime half is compose-ui-builder#377 and `docs/design/UI_BUILDER_BRANCHES.md` there).
 *
 * A branch is a design forked at a revision, edited through the ordinary [ServeUiBuilderMcp.APPLY]
 * on its own id, and **replay-merged** back onto its parent through the service's reducer — the
 * same replay the browser's Sync runs, so there is no second merge algorithm here. These four tools
 * are [UiBuilderBranchPort]'s requests and nothing more:
 *
 * - [BRANCH_DESIGN] forks a design into a new branch. Needs write on the parent.
 * - [LIST_BRANCHES] lists a design's branches. Needs read on the parent.
 * - [MERGE_BRANCH] replays a branch onto its parent, all or nothing, and reports every command:
 *   applied (with the last-writer-wins notices it overwrote) or the refusal code that stopped the
 *   run, plus what was skipped and how much was never tried. `dryRun` writes nothing. Needs write
 *   on the parent.
 * - [ARCHIVE_BRANCH] closes a branch without merging it. Needs write on the parent, or to own the
 *   branch.
 *
 * Every other tool already works on a branch, because a branch *is* a design: get, apply, view,
 * export, diff ([ServeUiBuilderHistoryTools.DIFF_DESIGNS] across a branch and its parent is the
 * review surface). A grant naming the parent reaches its branches ([ServeUiBuilderGrantScope]).
 *
 * Each reply is its own small shape with a `summary` and a schema declared as the tool's
 * `outputSchema`: the port speaks runtime types, not `ui-builder-protocol` wire shapes, so there is
 * no released envelope to reuse.
 */
internal class ServeUiBuilderBranchTools(private val branches: UiBuilderBranchPort) {

  suspend fun call(tool: String, args: JsonObject, actor: AuthenticatedUiBuilderActor): String =
    when (tool) {
      BRANCH_DESIGN ->
        UI_BUILDER_JSON.encodeToString(DesignBranchReplyV1.serializer(), branch(args, actor))
      LIST_BRANCHES ->
        UI_BUILDER_JSON.encodeToString(DesignBranchesV1.serializer(), list(args, actor))
      MERGE_BRANCH ->
        UI_BUILDER_JSON.encodeToString(DesignBranchMergeV1.serializer(), merge(args, actor))
      ARCHIVE_BRANCH ->
        UI_BUILDER_JSON.encodeToString(DesignBranchReplyV1.serializer(), archive(args, actor))
      else -> throw McpRequestException("unknown UI-builder branch tool '$tool'")
    }

  private suspend fun branch(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): DesignBranchReplyV1 {
    val designId = args.requiredText("designId")
    val name = args.requiredText("name")
    val revision = args.number("revision")
    val created =
      when (
        val response =
          execute(
            actor,
            UiBuilderBranchRequest.CreateBranch(
              designId = designId,
              name = name,
              revision = revision,
              branchId = args.text("branchId"),
            ),
          )
      ) {
        is UiBuilderBranchResponse.Branch -> response.branch
        is UiBuilderBranchResponse.Error ->
          throw refusal(designId, response.error, "Nothing was branched.")
        else -> throw McpRequestException("the design service did not answer the branch")
      }
    return DesignBranchReplyV1(
      branch = created.wire(),
      summary =
        "Branched $designId@r${created.forkRevision} as ${created.branchId} " +
          "(\"${created.name}\"). Edit it with ${ServeUiBuilderMcp.APPLY} on " +
          "designId=${created.branchId} (baseRevision ${created.headRevision}); compare with " +
          "${ServeUiBuilderHistoryTools.DIFF_DESIGNS}; bring it back with $MERGE_BRANCH " +
          "(dryRun first), or drop it with $ARCHIVE_BRANCH.",
    )
  }

  private suspend fun list(args: JsonObject, actor: AuthenticatedUiBuilderActor): DesignBranchesV1 {
    val designId = args.requiredText("designId")
    val status = args.text("status")?.lowercase() ?: STATUS_ALL
    val wanted =
      when (status) {
        STATUS_ALL -> null
        else ->
          UiBuilderBranchStatus.entries.firstOrNull { it.name.lowercase() == status }
            ?: throw McpRequestException(
              "`status` must be one of ${STATUS_VALUES.joinToString()}; got `$status`"
            )
      }
    val listed =
      when (
        val response =
          execute(
            actor,
            UiBuilderBranchRequest.ListBranches(
              designId,
              includeClosed = wanted != UiBuilderBranchStatus.OPEN,
            ),
          )
      ) {
        is UiBuilderBranchResponse.Branches -> response.branches
        is UiBuilderBranchResponse.Error -> throw refusal(designId, response.error)
        else -> throw McpRequestException("the design service did not list branches")
      }
    val rows = listed.filter { wanted == null || it.status == wanted }.map { it.wire() }
    return DesignBranchesV1(
      designId = designId,
      status = status,
      branches = rows,
      summary =
        (if (rows.isEmpty()) "No ${if (wanted == null) "" else "$status "}branches of $designId."
        else
          "${rows.size} ${if (wanted == null) "" else "$status "}branch" +
            (if (rows.size == 1) "" else "es") +
            " of $designId, newest first:" +
            rows.joinToString("") { "\n${it.summary}" }),
    )
  }

  private suspend fun merge(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): DesignBranchMergeV1 {
    val branchId = args.requiredText("branchId")
    val dryRun = args["dryRun"]?.jsonPrimitive?.booleanOrNull == true
    val skip =
      when (val raw = args["skipOperationIds"]) {
        null -> emptySet()
        is JsonArray ->
          raw
            .map {
              (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: throw McpRequestException("`skipOperationIds` must be an array of strings")
            }
            .toSet()
        else -> throw McpRequestException("`skipOperationIds` must be an array of strings")
      }
    val report =
      when (
        val response = execute(actor, UiBuilderBranchRequest.MergeBranch(branchId, dryRun, skip))
      ) {
        is UiBuilderBranchResponse.Merge -> response.report
        is UiBuilderBranchResponse.Error ->
          throw refusal(branchId, response.error, "Nothing was merged.", noun = "branch")
        else -> throw McpRequestException("the design service did not answer the merge")
      }
    return report.wire()
  }

  private suspend fun archive(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): DesignBranchReplyV1 {
    val branchId = args.requiredText("branchId")
    val archived =
      when (val response = execute(actor, UiBuilderBranchRequest.ArchiveBranch(branchId))) {
        is UiBuilderBranchResponse.Branch -> response.branch
        is UiBuilderBranchResponse.Error ->
          throw refusal(branchId, response.error, "Nothing was archived.", noun = "branch")
        else -> throw McpRequestException("the design service did not answer the archive")
      }
    return DesignBranchReplyV1(
      branch = archived.wire(),
      summary =
        "Archived ${archived.branchId}, a branch of ${archived.parentDesignId}. It stays " +
          "readable and listed; it can no longer be edited or merged.",
    )
  }

  private suspend fun execute(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderBranchRequest,
  ): UiBuilderBranchResponse = branches.executeBranch(UiBuilderBranchCall(actor, request))

  private fun refusal(
    id: String,
    error: UiBuilderServiceError,
    tail: String = "",
    noun: String = "design",
  ): McpRequestException =
    McpRequestException(
      when (error.code) {
        ServiceErrorCodeV1.NOT_FOUND,
        ServiceErrorCodeV1.UNAUTHORIZED -> "no $noun `$id` this actor can read"
        ServiceErrorCodeV1.FORBIDDEN ->
          "${error.message}: this actor needs write access to the design${
            if (noun == "branch") " the branch was made from" else ""
          }"
        ServiceErrorCodeV1.SNAPSHOT_REQUIRED ->
          "${error.message}" +
            (error.currentRevision?.let { " (current revision $it)" } ?: "") +
            ". ${ServeUiBuilderHistoryTools.LIST_REVISIONS} lists what is still reachable."
        else -> "${error.code.name}: ${error.message}"
      } + (if (tail.isEmpty()) "" else " $tail")
    )

  private fun JsonObject.text(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

  private fun JsonObject.requiredText(name: String): String =
    text(name) ?: throw McpRequestException("`$name` is required")

  private fun JsonObject.number(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

  companion object {
    const val BRANCH_DESIGN = "ui_builder_branch_design"
    const val LIST_BRANCHES = "ui_builder_list_branches"
    const val MERGE_BRANCH = "ui_builder_merge_branch"
    const val ARCHIVE_BRANCH = "ui_builder_archive_branch"

    /** In the order a session uses them: branch, look, then merge or archive. */
    val TOOL_NAMES = listOf(BRANCH_DESIGN, LIST_BRANCHES, MERGE_BRANCH, ARCHIVE_BRANCH)

    const val BRANCH_SCHEMA = "compose-preview/ui-builder-branch/v1"
    const val BRANCHES_SCHEMA = "compose-preview/ui-builder-branches/v1"
    const val MERGE_SCHEMA = "compose-preview/ui-builder-branch-merge/v1"

    const val STATUS_ALL = "all"
    val STATUS_VALUES = UiBuilderBranchStatus.entries.map { it.name.lowercase() } + STATUS_ALL

    fun outputSchema(name: String): JsonObject? =
      when (name) {
        BRANCH_DESIGN,
        ARCHIVE_BRANCH -> branchOutput
        LIST_BRANCHES -> branchesOutput
        MERGE_BRANCH -> mergeOutput
        else -> null
      }

    private val branchOutput by lazy {
      SerialDescriptorJsonSchema.output(DesignBranchReplyV1.serializer().descriptor, BRANCH_SCHEMA)
    }
    private val branchesOutput by lazy {
      SerialDescriptorJsonSchema.output(DesignBranchesV1.serializer().descriptor, BRANCHES_SCHEMA)
    }
    private val mergeOutput by lazy {
      SerialDescriptorJsonSchema.output(DesignBranchMergeV1.serializer().descriptor, MERGE_SCHEMA)
    }

    fun declarations(tool: (String, String, String) -> JsonObject): List<JsonObject> =
      listOf(
        tool(
          BRANCH_DESIGN,
          "Fork a design at a revision into a **branch** to explore an alternative, or to change " +
            "it without trampling somebody editing it live. A branch is a design with its own id: " +
            "edit it with ${ServeUiBuilderMcp.APPLY}, look at it with " +
            "${ServeUiBuilderMcp.VIEW} or ${ServeUiBuilderMcp.GET_DESIGN}, compare it with " +
            "${ServeUiBuilderHistoryTools.DIFF_DESIGNS}, then bring it back with $MERGE_BRANCH " +
            "or drop it with $ARCHIVE_BRANCH. Branch N times from the same revision to try N " +
            "alternatives: merging one archives the others. Branches of a branch are refused; " +
            "so are asset uploads, restores and document replacements on a branch. Needs write " +
            "access to the design.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string","description":"The design to branch."},
            "name":{"type":"string","description":"What this alternative is, e.g. `compact header`."},
            "revision":{"type":"integer","description":"A retained revision to fork at. Omit for the current one."},
            "branchId":{"type":"string","description":"The branch's design id. Defaults to one minted from the parent's; naming one makes a retry idempotent."}
          },"required":["designId","name"],"additionalProperties":false}
          """,
        ),
        tool(
          LIST_BRANCHES,
          "A design's branches, newest first: each one's id, name, owner, status (open, merged, " +
            "archived), fork revision, head revision and the number of commands a merge would " +
            "replay; for a merged one the parent revision it landed at, for an archived sibling " +
            "the branch whose merge superseded it. Read access to the design.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string","description":"The parent design."},
            "status":{"type":"string","enum":${STATUS_VALUES.joinToString(",", "[", "]") { "\"$it\"" }},"description":"Only branches in this state. Defaults to all."}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
        tool(
          MERGE_BRANCH,
          "Replay a branch's edits onto its parent's current revision through the same reducer " +
            "every edit goes through — all or nothing. The reply reports every command: " +
            "`applied` with the revision it landed at and any `STALE_*` notice of what it " +
            "overwrote, or `refused` with the rejection code that stopped the run (nothing is " +
            "then written), plus `skippedOperationIds` and how many were never tried. Run " +
            "`dryRun: true` first. Resolve a refusal by passing that command's id in " +
            "`skipOperationIds` (skip an undo with what it undoes), or branch again from the " +
            "parent. On success the branch is merged and its open siblings — branches forked " +
            "at the same revision — are archived. Needs write access to the parent.",
          """
          {"type":"object","properties":{
            "branchId":{"type":"string"},
            "dryRun":{"type":"boolean","description":"Replay and report without writing anything. Defaults to false."},
            "skipOperationIds":{"type":"array","items":{"type":"string"},"description":"Logged commands to leave out of the replay."}
          },"required":["branchId"],"additionalProperties":false}
          """,
        ),
        tool(
          ARCHIVE_BRANCH,
          "Close a branch without merging it: it stays readable and listed, and can no longer be " +
            "edited or merged. Needs write access to the parent, or to have made the branch.",
          """
          {"type":"object","properties":{
            "branchId":{"type":"string"}
          },"required":["branchId"],"additionalProperties":false}
          """,
        ),
      )
  }
}

/**
 * [linksReply] — a `ui_builder_get_links` reply — with the design's branch parentage spliced on,
 * read from the runtime rather than stored here: for a branch, `branchOf` (its parent, fork point,
 * name and status); for a parent, `branches` (every branch of it). The fork ancestry recorded by
 * [ServeUiBuilderAncestryStore] is a separate relation — a fork is a new, independent design, a
 * branch is not — and stays where it is.
 *
 * Best effort, like the ancestry: a branch lane that fails to answer costs the reply nothing.
 */
internal suspend fun withBranches(
  branches: UiBuilderBranchPort?,
  actor: AuthenticatedUiBuilderActor,
  designId: String,
  linksReply: String,
): String {
  if (branches == null) return linksReply
  val parsed =
    try {
      UI_BUILDER_JSON.parseToJsonElement(linksReply) as? JsonObject ?: return linksReply
    } catch (_: SerializationException) {
      return linksReply
    }
  suspend fun ask(request: UiBuilderBranchRequest): UiBuilderBranchResponse? =
    try {
      branches.executeBranch(UiBuilderBranchCall(actor, request))
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }
  val extra = buildMap {
    (ask(UiBuilderBranchRequest.GetBranch(designId)) as? UiBuilderBranchResponse.Branch)?.let {
      put(BRANCH_OF_KEY, UI_BUILDER_JSON.encodeToJsonElement(it.branch.wire()))
    }
    (ask(UiBuilderBranchRequest.ListBranches(designId)) as? UiBuilderBranchResponse.Branches)
      ?.branches
      ?.takeIf { it.isNotEmpty() }
      ?.let { list ->
        put(BRANCHES_KEY, UI_BUILDER_JSON.encodeToJsonElement(list.map { it.wire() }))
      }
  }
  return if (extra.isEmpty()) linksReply else JsonObject(parsed + extra).toString()
}

internal const val BRANCH_OF_KEY = "branchOf"
internal const val BRANCHES_KEY = "branches"

/** A branch as these tools report it. */
@Serializable
internal data class DesignBranchV1(
  /** The branch's own design id: get, apply, view, export and diff it by this. */
  val branchId: String,
  val parentDesignId: String,
  val name: String,
  val ownerActorId: String,
  /** `open`, `merged` or `archived`. */
  val status: String,
  /** The parent revision the branch forked at. */
  val forkRevision: Long,
  /** The service's hash of the parent's document at [forkRevision]. */
  val forkDocumentHash: String,
  /** The branch's current revision; it continues the parent's numbering from the fork. */
  val headRevision: Long,
  /** Commands accepted on the branch since the fork: what a merge would replay. */
  val commandCount: Int,
  val createdAtEpochMillis: Long,
  val closedAtEpochMillis: Long? = null,
  val closedByActorId: String? = null,
  val mergedAtParentRevision: Long? = null,
  /** For a sibling archived by a merge: the branch whose merge archived it. */
  val supersededByBranchId: String? = null,
  val summary: String,
)

@Serializable
internal data class DesignBranchReplyV1(
  val schema: String = ServeUiBuilderBranchTools.BRANCH_SCHEMA,
  val branch: DesignBranchV1,
  val summary: String,
)

@Serializable
internal data class DesignBranchesV1(
  val schema: String = ServeUiBuilderBranchTools.BRANCHES_SCHEMA,
  val designId: String,
  /** The status filter applied: `open`, `merged`, `archived` or `all`. */
  val status: String,
  /** Newest first. */
  val branches: List<DesignBranchV1>,
  val summary: String,
)

@Serializable
internal data class DesignBranchMergeV1(
  val schema: String = ServeUiBuilderBranchTools.MERGE_SCHEMA,
  val branchId: String,
  val parentDesignId: String,
  val dryRun: Boolean,
  /** True when every command landed — and, unless [dryRun], the parent now carries them. */
  val merged: Boolean,
  val forkRevision: Long,
  val parentRevisionBefore: Long,
  /** Equal to [parentRevisionBefore] when nothing was written. */
  val parentRevisionAfter: Long,
  /** One per command attempted, in log order. */
  val commands: List<DesignBranchMergeCommandV1>,
  /** Commands after the refused one, never attempted. */
  val remaining: Int,
  /** Open siblings the merge archived, or (dry run) would archive. */
  val archivedSiblingIds: List<String> = emptyList(),
  /** Logged commands left out at the caller's request, in log order. */
  val skippedOperationIds: List<String> = emptyList(),
  val summary: String,
)

@Serializable
internal data class DesignBranchMergeCommandV1(
  val operationId: String,
  /** Who authored it on the branch; the parent's history attributes it to them too. */
  val actorId: String,
  /** `applied` or `refused`. */
  val status: String,
  val baseRevision: Long,
  val committedRevision: Long? = null,
  /** What an applied command overwrote on the parent (last writer wins). */
  val conflicts: List<DesignBranchConflictV1> = emptyList(),
  /** The reducer's rejection code, for a refused command. */
  val code: String? = null,
  val message: String? = null,
  val nodeId: String? = null,
  val field: String? = null,
)

@Serializable
internal data class DesignBranchConflictV1(
  /** `STALE_PROPERTY_WRITE`, `STALE_MOVE`, … */
  val code: String,
  val nodeId: String? = null,
  val field: String? = null,
  /** The parent revision whose change this command overwrote. */
  val overwrittenRevision: Long,
  val explanation: String? = null,
)

internal fun UiBuilderBranch.wire(): DesignBranchV1 {
  val state = status.name.lowercase()
  return DesignBranchV1(
    branchId = branchId,
    parentDesignId = parentDesignId,
    name = name,
    ownerActorId = ownerActorId,
    status = state,
    forkRevision = forkRevision,
    forkDocumentHash = forkDocumentHash,
    headRevision = headRevision,
    commandCount = commandCount,
    createdAtEpochMillis = createdAtEpochMillis,
    closedAtEpochMillis = closedAtEpochMillis,
    closedByActorId = closedByActorId,
    mergedAtParentRevision = mergedAtParentRevision,
    supersededByBranchId = supersededByBranchId,
    summary =
      "$branchId \"$name\" ($state) from $parentDesignId@r$forkRevision, head r$headRevision, " +
        "$commandCount command${if (commandCount == 1) "" else "s"}" +
        (mergedAtParentRevision?.let { ", merged at r$it" } ?: "") +
        (supersededByBranchId?.let { ", superseded by $it" } ?: ""),
  )
}

internal fun UiBuilderBranchMergeReport.wire(): DesignBranchMergeV1 {
  val rows = commands.map { it.wire() }
  val applied = rows.count { it.status == APPLIED }
  val refused = rows.firstOrNull { it.status == REFUSED }
  val overwrites = rows.sumOf { it.conflicts.size }
  val summary = buildString {
    append(if (dryRun) "Dry run: " else "")
    if (merged) {
      append(
        if (dryRun)
          "merging $branchId would replay $applied command(s) onto " +
            "$parentDesignId@r$parentRevisionBefore, landing at r$parentRevisionAfter"
        else
          "Merged $branchId: $applied command(s) replayed onto $parentDesignId, " +
            "r$parentRevisionBefore → r$parentRevisionAfter"
      )
      if (overwrites > 0) append("; $overwrites last-writer-wins overwrite(s), listed per command")
      if (archivedSiblingIds.isNotEmpty()) {
        append("; ${if (dryRun) "would archive" else "archived"} sibling(s) ")
        append(archivedSiblingIds.joinToString())
      }
      append(".")
    } else {
      append("$branchId did not merge: ")
      if (refused != null) {
        append(
          "command ${refused.operationId} (by ${refused.actorId}) was refused " +
            "${refused.code ?: ""}${refused.message?.let { ": $it" } ?: ""}"
        )
        append(" after $applied command(s) replayed cleanly; $remaining never tried. ")
        append("Nothing was written. Fix it on the branch, or pass ")
        append("skipOperationIds [\"${refused.operationId}\"] to leave it out.")
      } else {
        append("nothing was written.")
      }
    }
    if (skippedOperationIds.isNotEmpty()) {
      append(" Skipped: ${skippedOperationIds.joinToString()}.")
    }
  }
  return DesignBranchMergeV1(
    branchId = branchId,
    parentDesignId = parentDesignId,
    dryRun = dryRun,
    merged = merged,
    forkRevision = forkRevision,
    parentRevisionBefore = parentRevisionBefore,
    parentRevisionAfter = parentRevisionAfter,
    commands = rows,
    remaining = remaining,
    archivedSiblingIds = archivedSiblingIds,
    skippedOperationIds = skippedOperationIds,
    summary = summary,
  )
}

private fun UiBuilderBranchMergeCommand.wire(): DesignBranchMergeCommandV1 =
  DesignBranchMergeCommandV1(
    operationId = operationId,
    actorId = actorId,
    status =
      when (status) {
        UiBuilderBranchMergeCommandStatus.APPLIED -> APPLIED
        UiBuilderBranchMergeCommandStatus.REFUSED -> REFUSED
      },
    baseRevision = baseRevision,
    committedRevision = committedRevision,
    conflicts = conflicts.map { it.wire() },
    code = code,
    message = message,
    nodeId = nodeId,
    field = field,
  )

private fun CommandConflictV1.wire(): DesignBranchConflictV1 =
  DesignBranchConflictV1(
    code = code.name,
    nodeId = nodeId,
    field = field ?: environmentField?.name,
    overwrittenRevision = overwrittenRevision,
    explanation = explanation,
  )

private const val APPLIED = "applied"
private const val REFUSED = "refused"
