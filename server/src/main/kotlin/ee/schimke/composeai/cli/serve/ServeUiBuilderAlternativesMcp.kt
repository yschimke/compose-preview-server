package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderBranch
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchCall
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchPort
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchStatus
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * "Explore N alternatives, pick one, merge" — phase 3 of yschimke/compose-ui-builder#375, on top of
 * the branch tools ([ServeUiBuilderBranchTools], phase 2).
 *
 * Two reads, and no new write: merging the chosen branch is
 * [ServeUiBuilderBranchTools.MERGE_BRANCH] unchanged, which already archives the siblings and
 * reports the parent revision it landed at.
 *
 * - [COMPARE_BRANCHES] renders the parent's head and each open branch (or the ones named) into one
 *   contact sheet, and diffs each branch against the parent at its fork point, so an agent can put
 *   the choice to a person in one call. The sheet travels as a short-lived signed https link, like
 *   `ui_builder_view`'s and `ui_builder_render_design_matrix`'s, because a chat surface (#1254) can
 *   show a link and nothing else.
 * - [PICK_BRANCH] asks the person. With an OpenAI-forms client (#1253's picker, [ServeOpenAiForms])
 *   it is a thumbnail picker, one option per branch, each opening that branch in
 *   [ServeUiBuilderMcp.VIEW]; with plain MCP form elicitation it is a single-choice enum; with
 *   neither — the stateless JSON path, or a chat-hosted agent — it is a numbered list plus the same
 *   sheet, for the agent to post and the person to answer with a number. It never blocks a client
 *   that cannot answer: nothing is sent to a client that declared neither form, and an asked form
 *   is bounded by [PICK_TIMEOUT_MILLIS] and then falls back to the numbered list.
 *
 * **Access.** Both are reads of the parent, gated at the door as [UiBuilderRouteCapability.READ].
 * Branches are listed through [UiBuilderBranchPort] as the calling actor, whose access is the
 * parent's — and a design-scoped grant reaches a branch through the parent
 * ([ServeUiBuilderGrantScope]) — so whoever may read the design may compare and pick among its
 * branches, and nobody else is told they exist. A `branchIds` entry that is not a branch of
 * `designId` is refused rather than rendered, so the tool cannot be used to look at an unrelated
 * design through a design the caller can read.
 */
internal class ServeUiBuilderAlternativeTools(
  private val service: UiBuilderServicePort,
  private val branches: UiBuilderBranchPort,
  /** Renders a design at another device without saving it; null refuses `device`. */
  private val validator: UiBuilderDraftValidator?,
) {

  suspend fun call(
    tool: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    interaction: ServeCatalogMcp.ClientInteraction,
  ): String =
    when (tool) {
      COMPARE_BRANCHES ->
        UI_BUILDER_JSON.encodeToString(DesignBranchComparisonV1.serializer(), compare(args, actor))
      PICK_BRANCH ->
        UI_BUILDER_JSON.encodeToString(
          DesignBranchPickV1.serializer(),
          pick(args, actor, interaction),
        )
      else -> throw McpRequestException("unknown UI-builder alternatives tool '$tool'")
    }

  // ---- compare ---------------------------------------------------------------------------------

  private suspend fun compare(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): DesignBranchComparisonV1 {
    val designId = args.requiredText("designId")
    val chosen = alternatives(designId, args.branchIds(), actor, openOnly = false)
    val device = args.device()
    val parent = snapshot(actor, designId, null)
    if (chosen.isEmpty()) {
      return DesignBranchComparisonV1(
        designId = designId,
        parentRevision = parent.revision,
        device = device?.id,
        summary = noBranches(designId),
      )
    }
    val pictures = render(actor, listOf(designId) + chosen.map { it.branchId }, device)
    val rows = chosen.mapIndexed { index, branch -> describe(actor, index + 1, branch, parent) }
    val tiles =
      listOf(ServeContactSheet.Tile(pictures[0].png, "Parent · r${parent.revision}")) +
        rows.mapIndexed { index, row ->
          ServeContactSheet.Tile(pictures[index + 1].png, "${row.number} · ${row.name}")
        }
    val sheet = ServeContactSheet.compose(tiles, maxBytes = UiBuilderDesignMatrix.MAX_SHEET_BYTES)
    fun tile(index: Int): DesignAlternativeTileV1 {
      val placed = sheet.placed[index]
      return DesignAlternativeTileV1(
        x = placed.x,
        y = placed.y,
        width = placed.width,
        height = placed.height,
        rendered = pictures[index].png != null,
        problem = pictures[index].problem,
      )
    }
    val summary = buildString {
      append(
        "${rows.size} alternative${if (rows.size == 1) "" else "s"} of $designId " +
          "(parent r${parent.revision}${device?.let { ", on ${it.label}" } ?: ""}), each " +
          "against the parent at its fork:"
      )
      rows.forEach { append("\n${it.number}. ${it.line}") }
      append(
        "\nShow the sheet, then let the person choose with $PICK_BRANCH (or ask them to reply " +
          "with the number); merge the choice with ${ServeUiBuilderBranchTools.MERGE_BRANCH} " +
          "(dryRun first)."
      )
      val failed = pictures.count { it.png == null }
      if (failed > 0) append(" $failed picture(s) could not be rendered; see each `problem`.")
    }
    return DesignBranchComparisonV1(
      designId = designId,
      parentRevision = parent.revision,
      device = device?.id,
      summary = summary,
      columns = sheet.columns,
      rows = sheet.rows,
      image =
        UiBuilderViewImageV1(
          widthPx = sheet.width,
          heightPx = sheet.height,
          scale = 1.0,
          sha256 = UiBuilderDesignMatrix.sha256(sheet.png),
        ),
      parent = tile(0),
      branches = rows.mapIndexed { index, row -> row.wire(tile(index + 1)) },
      imageBase64 = UiBuilderDesignMatrix.base64(sheet.png),
    )
  }

  // ---- pick ------------------------------------------------------------------------------------

  private suspend fun pick(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    interaction: ServeCatalogMcp.ClientInteraction,
  ): DesignBranchPickV1 {
    val designId = args.requiredText("designId")
    val chosen = alternatives(designId, args.branchIds(), actor, openOnly = true)
    if (chosen.isEmpty()) {
      return DesignBranchPickV1(
        designId = designId,
        outcome = OUTCOME_NO_BRANCHES,
        summary = noBranches(designId),
      )
    }
    val parent = snapshot(actor, designId, null)
    val rows = chosen.mapIndexed { index, branch -> describe(actor, index + 1, branch, parent) }
    val options = rows.map { it.option() }
    val question =
      args.text("message")
        ?: "Which alternative of “${parent.title}” should be kept? The others are archived."
    // Pictures are only drawn for a path that shows them: the picker's thumbnails, or the chat
    // fallback's sheet. A plain enum form has nowhere to put one.
    var pictures: List<Picture>? = null
    suspend fun pictures(): List<Picture> =
      pictures ?: render(actor, chosen.map { it.branchId }, device = null).also { pictures = it }

    fun chosenReply(number: Int, via: String): DesignBranchPickV1 {
      val row = rows[number - 1]
      return DesignBranchPickV1(
        designId = designId,
        outcome = OUTCOME_CHOSEN,
        via = via,
        branchId = row.branchId,
        options = options,
        summary =
          "The person chose ${row.number}. “${row.name}” (${row.branchId}). Next: " +
            "${ServeUiBuilderBranchTools.MERGE_BRANCH} {\"branchId\":\"${row.branchId}\"," +
            "\"dryRun\":true}, read the report, then merge for real; its open siblings are " +
            "archived and kept.",
      )
    }

    fun declinedReply(via: String) =
      DesignBranchPickV1(
        designId = designId,
        outcome = OUTCOME_DECLINED,
        via = via,
        options = options,
        summary =
          "The person declined to choose. Nothing was merged or archived; ask what they want " +
            "instead, or leave the branches open.",
      )

    // 1. OpenAI's thumbnail picker (#1253), when the client declared it.
    if (interaction.openAiFormsSupported) {
      val drawn = pictures()
      val picker = rows.mapIndexed { index, row ->
        row.pickerOption(drawn[index].png?.let { thumbnail(it) })
      }
      val schema =
        ServeOpenAiForms.form(
          mapOf(FIELD to ServeOpenAiForms.singleResourceField(picker, title = "Alternative")),
          required = listOf(FIELD),
        )
      when (val asked = interaction.elicitOpenAiForm(question, schema, PICK_TIMEOUT_MILLIS)) {
        OpenAiFormElicitation.Unsupported -> Unit // fall through to the plain form
        OpenAiFormElicitation.NoAnswer -> return chat(designId, rows, options, drawn, VIA_CHAT)
        is OpenAiFormElicitation.Answered -> {
          val result = asked.result
          if (result.action != ServeCatalogMcp.FormElicitationAction.ACCEPT) {
            return declinedReply(VIA_OPENAI_FORM)
          }
          val uri =
            ServeOpenAiForms.selectedUri(result.content, FIELD, picker.map { it.uri })
              ?: return chat(designId, rows, options, drawn, VIA_CHAT)
          return chosenReply(picker.indexOfFirst { it.uri == uri } + 1, VIA_OPENAI_FORM)
        }
      }
    }

    // 2. Plain MCP form elicitation: a single-choice enum of branch ids, titled with names.
    if (interaction.formElicitationSupported) {
      val schema =
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to
              JsonObject(
                mapOf(
                  FIELD to
                    JsonObject(
                      mapOf(
                        "type" to JsonPrimitive("string"),
                        "title" to JsonPrimitive("Alternative"),
                        "description" to JsonPrimitive(question),
                        "enum" to JsonArray(rows.map { JsonPrimitive(it.branchId) }),
                        "enumNames" to
                          JsonArray(rows.map { JsonPrimitive("${it.number}. ${it.name}") }),
                      )
                    )
                )
              ),
            "required" to JsonArray(listOf(JsonPrimitive(FIELD))),
          )
        )
      val answer = interaction.elicitForm(question, schema, PICK_TIMEOUT_MILLIS)
      if (answer != null) {
        if (answer.action != ServeCatalogMcp.FormElicitationAction.ACCEPT) {
          return declinedReply(VIA_FORM)
        }
        val id = (answer.content?.get(FIELD) as? JsonPrimitive)?.takeIf { it.isString }?.content
        val number = rows.indexOfFirst { it.branchId == id } + 1
        if (number > 0) return chosenReply(number, VIA_FORM)
      }
    }

    // 3. Ask in chat: a numbered list and one sheet, answered with a reply.
    return chat(designId, rows, options, pictures(), VIA_CHAT)
  }

  private fun chat(
    designId: String,
    rows: List<Alternative>,
    options: List<DesignPickOptionV1>,
    pictures: List<Picture>,
    via: String,
  ): DesignBranchPickV1 {
    val sheet =
      ServeContactSheet.compose(
        rows.mapIndexed { index, row ->
          ServeContactSheet.Tile(pictures[index].png, "${row.number} · ${row.name}")
        },
        maxBytes = UiBuilderDesignMatrix.MAX_SHEET_BYTES,
      )
    val summary = buildString {
      append("Nobody was asked in a form here. Post this to the person and ask them to reply ")
      append("with the number of the alternative to keep (the sheet's link shows all of them):")
      rows.forEach { append("\n${it.number}. ${it.line}") }
      append(
        "\nThen merge their choice with ${ServeUiBuilderBranchTools.MERGE_BRANCH} (dryRun " +
          "first). Do not choose for them."
      )
    }
    return DesignBranchPickV1(
      designId = designId,
      outcome = OUTCOME_ASK_IN_CHAT,
      via = via,
      options = options,
      summary = summary,
      image =
        UiBuilderViewImageV1(
          widthPx = sheet.width,
          heightPx = sheet.height,
          scale = 1.0,
          sha256 = UiBuilderDesignMatrix.sha256(sheet.png),
        ),
      imageBase64 = UiBuilderDesignMatrix.base64(sheet.png),
    )
  }

  // ---- shared ----------------------------------------------------------------------------------

  /** One branch, numbered as the person sees it, with its diff against the parent's fork point. */
  private class Alternative(
    val number: Int,
    val branch: UiBuilderBranch,
    val diff: DesignAlternativeDiffV1,
    val parentMoved: Boolean,
  ) {
    val branchId: String
      get() = branch.branchId

    val name: String
      get() = branch.name

    /** One line a person can read: name, id, what it changes, and whether the parent moved. */
    val line: String
      get() =
        "“$name” ($branchId, ${branch.commandCount} edit" +
          (if (branch.commandCount == 1) "" else "s") +
          "): ${diff.headline}" +
          (if (parentMoved) "; the parent has changed since the fork" else "")

    fun option() =
      DesignPickOptionV1(
        number = number,
        branchId = branchId,
        name = name,
        headRevision = branch.headRevision,
        commandCount = branch.commandCount,
        changes = diff.headline,
      )

    fun pickerOption(thumbnailBase64: String?) =
      ServeOpenAiForms.ResourceOption(
        uri = optionUri(branchId),
        name = name,
        title = "$number. $name",
        description = diff.headline,
        mimeType = "image/png",
        thumbnailPngBase64 = thumbnailBase64,
        previewTarget =
          ServeOpenAiForms.appToolTarget(
            ServeUiBuilderMcp.VIEW,
            buildJsonObject { put("designId", branchId) },
          ),
      )

    fun wire(tile: DesignAlternativeTileV1) =
      DesignAlternativeV1(
        number = number,
        branchId = branchId,
        name = name,
        status = branch.status.name.lowercase(),
        forkRevision = branch.forkRevision,
        headRevision = branch.headRevision,
        commandCount = branch.commandCount,
        parentMovedSinceFork = parentMoved,
        diff = diff,
        tile = tile,
      )
  }

  private suspend fun describe(
    actor: AuthenticatedUiBuilderActor,
    number: Int,
    branch: UiBuilderBranch,
    parentHead: DesignDocumentV1,
  ): Alternative {
    // What the alternative *changes* is the branch against the parent at the fork — the base is
    // pinned by the runtime, so it is retained. A parent edited since then would otherwise show
    // up as the branch "reverting" it. If the fork snapshot cannot be read, compare to the head.
    val base =
      runCatching { snapshot(actor, branch.parentDesignId, branch.forkRevision) }.getOrNull()
        ?: parentHead
    val head = snapshot(actor, branch.branchId, null)
    val diff = UiBuilderDocumentDiff.diff(base, head)
    val headline =
      if (diff.identical) "no changes"
      else
        listOfNotNull(
            diff.counts.added.takeIf { it > 0 }?.let { "$it added" },
            diff.counts.removed.takeIf { it > 0 }?.let { "$it removed" },
            diff.counts.moved.takeIf { it > 0 }?.let { "$it moved" },
            diff.counts.changed.takeIf { it > 0 }?.let { "$it changed" },
            diff.counts.document.takeIf { it > 0 }?.let { "$it document field(s)" },
          )
          .joinToString(", ")
          .ifEmpty { "changed" }
    val lines = diff.summary.lines().filter { it.isNotBlank() }
    return Alternative(
      number = number,
      branch = branch,
      diff =
        DesignAlternativeDiffV1(
          baseRevision = base.revision,
          identical = diff.identical,
          headline = headline,
          counts = diff.counts,
          summary =
            (lines.take(MAX_SUMMARY_LINES) +
                (if (lines.size > MAX_SUMMARY_LINES) {
                  listOf(
                    "… ${lines.size - MAX_SUMMARY_LINES} more; " +
                      "${ServeUiBuilderHistoryTools.DIFF_DESIGNS} has the rest"
                  )
                } else emptyList()))
              .joinToString("\n"),
          truncated = diff.truncated || lines.size > MAX_SUMMARY_LINES,
        ),
      parentMoved = parentHead.revision != branch.forkRevision,
    )
  }

  /**
   * The branches of [designId] to put side by side: the open ones, oldest first so the numbers read
   * in the order the alternatives were made, or exactly [named], in the order given.
   */
  private suspend fun alternatives(
    designId: String,
    named: List<String>?,
    actor: AuthenticatedUiBuilderActor,
    openOnly: Boolean,
  ): List<UiBuilderBranch> {
    val listed =
      when (
        val response =
          execute(actor, UiBuilderBranchRequest.ListBranches(designId, includeClosed = true))
      ) {
        is UiBuilderBranchResponse.Branches -> response.branches
        is UiBuilderBranchResponse.Error ->
          throw McpRequestException("no design `$designId` this actor can read")
        else -> throw McpRequestException("the design service did not list branches")
      }
    val picked =
      if (named == null) {
        listed
          .filter { it.status == UiBuilderBranchStatus.OPEN }
          .sortedWith(compareBy({ it.createdAtEpochMillis }, { it.branchId }))
      } else {
        named.distinct().map { id ->
          val branch =
            listed.firstOrNull { it.branchId == id }
              ?: throw McpRequestException(
                "`$id` is not a branch of `$designId`; " +
                  "${ServeUiBuilderBranchTools.LIST_BRANCHES} lists them"
              )
          if (openOnly && branch.status != UiBuilderBranchStatus.OPEN) {
            throw McpRequestException(
              "`$id` is ${branch.status.name.lowercase()}; only an open branch can be picked " +
                "and merged"
            )
          }
          branch
        }
      }
    if (picked.size > MAX_ALTERNATIVES) {
      throw McpRequestException(
        "${picked.size} branches is more than $MAX_ALTERNATIVES side by side; name the ones to " +
          "compare in `branchIds`"
      )
    }
    return picked
  }

  private fun noBranches(designId: String): String =
    "No open branches of $designId to choose between. Make one per idea with " +
      "${ServeUiBuilderBranchTools.BRANCH_DESIGN} from the same revision, edit each with " +
      "${ServeUiBuilderMcp.APPLY}, then compare them here."

  private class Picture(val png: ByteArray?, val problem: String?)

  /**
   * Each design's PNG export, as [actor]: the stored head through the service, or — with a [device]
   * — the head with its environment resized, through the scratch lane so nothing is written. A
   * picture that fails is a blank tile with its reason, never a failed comparison.
   */
  private suspend fun render(
    actor: AuthenticatedUiBuilderActor,
    designIds: List<String>,
    device: UiBuilderDesignMatrix.Device?,
  ): List<Picture> {
    if (device == null) return designIds.map { exportPng(actor, it) }
    val lane =
      validator
        ?: throw McpRequestException(
          "this host cannot render a design at another size without saving it; omit `device`"
        )
    val documents = designIds.map { id ->
      snapshot(actor, id, null).let {
        it.copy(
          environment = it.environment.copy(widthDp = device.widthDp, heightDp = device.heightDp)
        )
      }
    }
    val drawn =
      lane.exportPngs(actor, documents)
        ?: throw McpRequestException(
          "this host cannot render a design at another size without saving it; omit `device`"
        )
    return designIds.indices.map { index ->
      val picture = drawn.getOrNull(index)
      if (picture?.png != null) Picture(picture.png, null)
      else Picture(null, picture?.problem ?: "not rendered")
    }
  }

  private suspend fun exportPng(actor: AuthenticatedUiBuilderActor, designId: String): Picture {
    val response =
      try {
        service.execute(
          UiBuilderServiceCall(
            actor,
            UiBuilderServiceRequest.ExportDesign(designId, null, ExportFormatV1.PNG),
          )
        )
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        return Picture(null, "the PNG export failed: ${failure.message ?: failure.javaClass.name}")
      }
    val artifact =
      when (response) {
        is UiBuilderServiceResponse.Export -> response.artifact
        is UiBuilderServiceResponse.Error -> return Picture(null, response.error.message)
        else -> return Picture(null, "the PNG export answered no artifact")
      }
    val errors = artifact.diagnostics.filter { it.severity == DiagnosticSeverityV1.ERROR }
    val bytes = runCatching { Base64.getDecoder().decode(artifact.content) }.getOrNull()
    if (errors.isNotEmpty() || bytes == null || bytes.isEmpty()) {
      return Picture(
        null,
        errors.joinToString("; ") { "${it.code}: ${it.message}" }.ifEmpty { "no image" },
      )
    }
    return Picture(bytes, null)
  }

  private suspend fun snapshot(
    actor: AuthenticatedUiBuilderActor,
    designId: String,
    revision: Long?,
  ): DesignDocumentV1 =
    when (
      val response =
        service.execute(
          UiBuilderServiceCall(actor, UiBuilderServiceRequest.GetSnapshot(designId, revision))
        )
    ) {
      is UiBuilderServiceResponse.Snapshot -> response.snapshot.state.document
      else -> throw McpRequestException("no design `$designId` this actor can read")
    }

  private suspend fun execute(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderBranchRequest,
  ): UiBuilderBranchResponse = branches.executeBranch(UiBuilderBranchCall(actor, request))

  private fun JsonObject.text(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

  private fun JsonObject.requiredText(name: String): String =
    text(name) ?: throw McpRequestException("`$name` is required")

  private fun JsonObject.branchIds(): List<String>? =
    when (val raw = this["branchIds"]) {
      null,
      is JsonNull -> null
      is JsonArray ->
        raw
          .map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.isNotBlank() }?.content
              ?: throw McpRequestException("`branchIds` must be an array of branch ids")
          }
          .ifEmpty { throw McpRequestException("`branchIds` names no branch") }
      else -> throw McpRequestException("`branchIds` must be an array of branch ids")
    }

  private fun JsonObject.device(): UiBuilderDesignMatrix.Device? =
    when (val raw = this["device"]) {
      null,
      is JsonNull -> null
      is JsonPrimitive ->
        raw.contentOrNull?.let(UiBuilderDesignMatrix::preset)
          ?: throw McpRequestException(
            "`${raw.content}` is not a device preset; presets are " +
              UiBuilderDesignMatrix.PRESETS.joinToString(", ") { it.id } +
              ", or pass {\"widthDp\":…,\"heightDp\":…}"
          )
      is JsonObject -> {
        fun edge(name: String): Int =
          (raw[name] as? JsonPrimitive)
            ?.longOrNull
            ?.takeIf { it in 1..UiBuilderDesignMatrix.MAX_DEVICE_DP }
            ?.toInt()
            ?: throw McpRequestException(
              "`device.$name` must be an integer from 1 to ${UiBuilderDesignMatrix.MAX_DEVICE_DP}"
            )
        val width = edge("widthDp")
        val height = edge("heightDp")
        UiBuilderDesignMatrix.Device(
          id = "custom",
          label = "$width×$height dp",
          formFactor = "custom",
          widthDp = width,
          heightDp = height,
        )
      }
      else -> throw McpRequestException("`device` is a preset id or {\"widthDp\",\"heightDp\"}")
    }

  companion object {
    const val COMPARE_BRANCHES = "ui_builder_compare_branches"
    const val PICK_BRANCH = "ui_builder_pick_branch"

    /** Compare, then pick; both exist only where the branch tools do. */
    val TOOL_NAMES = listOf(COMPARE_BRANCHES, PICK_BRANCH)

    const val COMPARE_SCHEMA = "compose-preview/ui-builder-branch-comparison/v1"
    const val PICK_SCHEMA = "compose-preview/ui-builder-branch-pick/v1"

    const val OUTCOME_CHOSEN = "chosen"
    const val OUTCOME_DECLINED = "declined"
    const val OUTCOME_ASK_IN_CHAT = "ask-in-chat"
    const val OUTCOME_NO_BRANCHES = "no-branches"
    const val VIA_OPENAI_FORM = ServeOpenAiForms.EXTENSION_ID
    const val VIA_FORM = "elicitation"
    const val VIA_CHAT = "chat"

    /** The form field the chosen branch comes back in. */
    const val FIELD = "branch"

    /** The parent plus this many fill a [UiBuilderDesignMatrix.MAX_CELLS] sheet. */
    const val MAX_ALTERNATIVES = UiBuilderDesignMatrix.MAX_CELLS - 1

    /** Long edge of a picker thumbnail, in pixels — #1253's. */
    const val THUMBNAIL_EDGE_PX = 256

    /** A person needs time to look and choose; the decision forms allow the same. */
    const val PICK_TIMEOUT_MILLIS = 2 * 60 * 1000L

    private const val MAX_SUMMARY_LINES = 6

    /** The picker option's id for [branchId]: an identifier, never fetched. */
    fun optionUri(branchId: String): String =
      "compose-preview://ui-builder/branch/" + URLEncoder.encode(branchId, StandardCharsets.UTF_8)

    /** [png] scaled to fit [THUMBNAIL_EDGE_PX], base64; the original when it cannot be decoded. */
    internal fun thumbnail(png: ByteArray): String {
      val image =
        runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull()
          ?: return Base64.getEncoder().encodeToString(png)
      val (width, height) =
        ServeContactSheet.scaledSize(image.width, image.height, THUMBNAIL_EDGE_PX)
      if (width == image.width && height == image.height) {
        return Base64.getEncoder().encodeToString(png)
      }
      val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
      val g = scaled.createGraphics()
      try {
        g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION,
          RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.drawImage(image, 0, 0, width, height, null)
      } finally {
        g.dispose()
      }
      val out = ByteArrayOutputStream()
      ImageIO.write(scaled, "png", out)
      return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    fun outputSchema(name: String): JsonObject? =
      when (name) {
        COMPARE_BRANCHES -> compareOutput
        PICK_BRANCH -> pickOutput
        else -> null
      }

    private val compareOutput by lazy {
      SerialDescriptorJsonSchema.output(
        DesignBranchComparisonV1.serializer().descriptor,
        COMPARE_SCHEMA,
      )
    }
    private val pickOutput by lazy {
      SerialDescriptorJsonSchema.output(DesignBranchPickV1.serializer().descriptor, PICK_SCHEMA)
    }

    private val DEVICE_SCHEMA =
      """{"description":"Render every picture at this device instead of each design's own size: a preset id (${UiBuilderDesignMatrix.PRESETS.joinToString(", ") { it.id }}) or {\"widthDp\",\"heightDp\"}. Nothing is saved.","anyOf":[{"type":"string"},{"type":"object","properties":{"widthDp":{"type":"integer"},"heightDp":{"type":"integer"}},"required":["widthDp","heightDp"],"additionalProperties":false}]}"""

    fun declarations(tool: (String, String, String) -> JsonObject): List<JsonObject> =
      listOf(
        tool(
          COMPARE_BRANCHES,
          "Put a design's alternatives side by side in one call: the parent's head and each " +
            "open branch (or the `branchIds` named) rendered into ONE picture — a short-lived " +
            "signed https link, numbered by caption — plus, per branch, what it changes against " +
            "the parent at its fork (counts and a few lines, as " +
            "${ServeUiBuilderHistoryTools.DIFF_DESIGNS} reports them). Show the person the " +
            "picture and the numbered list, then ask with $PICK_BRANCH; merge the choice with " +
            "${ServeUiBuilderBranchTools.MERGE_BRANCH}. Writes nothing. Read access to the design.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string","description":"The parent design."},
            "branchIds":{"type":"array","items":{"type":"string"},"description":"Only these branches of the design, in this order. Defaults to every open branch, oldest first."},
            "device":$DEVICE_SCHEMA,
            "${ServeUiBuilderMcp.INLINE_ARGUMENT}":{"type":"boolean","description":"Also return the picture as an image block. Defaults to false: the signed link."}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
        tool(
          PICK_BRANCH,
          "Ask the person which alternative (open branch) of a design to keep, and return their " +
            "choice. In ChatGPT or Codex it is a thumbnail picker, one option per branch; with " +
            "plain form elicitation it is a single-choice form; otherwise — and in chat tools " +
            "like Slack — it returns a numbered list and one picture for YOU to post, and the " +
            "person replies with a number. It never waits on a client that cannot answer. " +
            "`outcome` is `chosen` (with `branchId`), `declined`, `ask-in-chat` or " +
            "`no-branches`. Writes nothing: merge the chosen branch with " +
            "${ServeUiBuilderBranchTools.MERGE_BRANCH} (dryRun first). Read access to the design.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string","description":"The parent design."},
            "branchIds":{"type":"array","items":{"type":"string"},"description":"Only these open branches, in this order. Defaults to every open branch, oldest first."},
            "message":{"type":"string","description":"The question to put to the person. Defaults to asking which alternative to keep."},
            "${ServeUiBuilderMcp.INLINE_ARGUMENT}":{"type":"boolean","description":"With a numbered list, also return its picture as an image block."}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
      )
  }
}

/** `ui_builder_compare_branches`'s reply. The picture becomes a signed link, like a view's. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DesignBranchComparisonV1(
  @EncodeDefault val schema: String = ServeUiBuilderAlternativeTools.COMPARE_SCHEMA,
  val designId: String,
  /** The parent's current revision, drawn first on the sheet. */
  val parentRevision: Long,
  /** The device preset every picture was drawn at, when one was asked for. */
  val device: String? = null,
  /** A numbered list a person can read, and what to do next. */
  val summary: String,
  val columns: Int = 0,
  val rows: Int = 0,
  /** The sheet: the parent, then each branch by number. Absent when there are no branches. */
  val image: UiBuilderViewImageV1? = null,
  /** Where the parent sits on the sheet. */
  val parent: DesignAlternativeTileV1? = null,
  val branches: List<DesignAlternativeV1> = emptyList(),
  /** Taken off before the reply is sent, and turned into [image]'s link or an image block. */
  val imageBase64: String? = null,
)

@Serializable
internal data class DesignAlternativeV1(
  /** What the person calls it: the sheet's caption and the list's number. */
  val number: Int,
  val branchId: String,
  val name: String,
  /** `open`, `merged` or `archived`. */
  val status: String,
  val forkRevision: Long,
  val headRevision: Long,
  /** What a merge would replay. */
  val commandCount: Int,
  /** Whether the parent was edited after this branch forked; the merge replays onto its head. */
  val parentMovedSinceFork: Boolean,
  val diff: DesignAlternativeDiffV1,
  val tile: DesignAlternativeTileV1,
)

@Serializable
internal data class DesignAlternativeDiffV1(
  /** The parent revision compared against: the fork point, unless it could not be read. */
  val baseRevision: Long,
  val identical: Boolean,
  /** `2 changed, 1 added`, or `no changes`. */
  val headline: String,
  val counts: DesignDiffCountsV1,
  /** The first lines of `ui_builder_diff_designs`'s summary. */
  val summary: String,
  /** Whether [summary] was cut; `ui_builder_diff_designs` has the whole diff. */
  val truncated: Boolean = false,
)

@Serializable
internal data class DesignAlternativeTileV1(
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
  val rendered: Boolean,
  /** Why the tile is blank, when it is. */
  val problem: String? = null,
)

/** `ui_builder_pick_branch`'s reply. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DesignBranchPickV1(
  @EncodeDefault val schema: String = ServeUiBuilderAlternativeTools.PICK_SCHEMA,
  val designId: String,
  /** `chosen`, `declined`, `ask-in-chat` or `no-branches`. */
  val outcome: String,
  /** How the person was asked: `openai/elicitation`, `elicitation` or `chat`. */
  val via: String? = null,
  /** The branch the person chose, for `chosen`. */
  val branchId: String? = null,
  /** What was offered, numbered as the person saw it. */
  val options: List<DesignPickOptionV1> = emptyList(),
  /** For `ask-in-chat`, the numbered list to post; otherwise what happened and what is next. */
  val summary: String,
  /** For `ask-in-chat`, the alternatives on one sheet, numbered by caption. */
  val image: UiBuilderViewImageV1? = null,
  val imageBase64: String? = null,
)

@Serializable
internal data class DesignPickOptionV1(
  val number: Int,
  val branchId: String,
  val name: String,
  val headRevision: Long,
  val commandCount: Int,
  /** What it changes against the parent at its fork, as counts. */
  val changes: String,
)
