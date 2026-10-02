package ee.schimke.composeai.mcp

import ee.schimke.composeai.mcp.OpenAiForms.ResourceOption
import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Visual choices as thumbnail pickers (#1240): when the client declared OpenAI form elicitation, a
 * choice between previews is asked with a resource picker whose options are the
 * `compose-preview://` URIs themselves, each with the last render as its thumbnail and
 * `render_preview` as its full-size preview. Every other client keeps today's behaviour, which
 * [DaemonMcpServer] still owns: these functions return null to mean "not asked here".
 *
 * Every option should carry a thumbnail: a host draws a file placeholder for one without (the spec:
 * "Servers that provide images for SOME items … SHOULD provide images for ALL items"). An ambiguous
 * match uses each preview's last cached render ([PreviewActivity.lastRenderPng]) and
 * [DaemonMcpServer] renders the rest when there are no more than a variant grid holds, the renders
 * the grid would have made anyway; a matrix uses the cells the same call just rendered.
 *
 * Option URIs go out [OpenAiForms.encodeUri]'d, since the field is `format: "uri"` and a
 * multipreview's name has spaces; [OpenAiForms.selectedUri] maps the answer back.
 */
object PreviewPickers {
  /** Long edge of a picker thumbnail, in pixels. */
  const val THUMBNAIL_EDGE_PX: Int = 256

  /** Most options one picker offers; more falls back to today's behaviour. */
  const val MAX_OPTIONS: Int = 24

  /** The form field holding the picked preview URI. */
  const val FIELD: String = "preview"

  /** The MCP App tool an option's preview opens. */
  const val RENDER_TOOL: String = "render_preview"

  /** `PreviewTarget` opening [uri] full size through `render_preview` in the viewer. */
  fun renderPreviewTarget(uri: String): JsonObject =
    OpenAiForms.appToolTarget(RENDER_TOOL, buildJsonObject { put("uri", uri) })

  /**
   * Short labels that tell [uris] apart, in order: each preview's simple name (plus its config)
   * without the function name the variants share, so `ListScreenPreview_Devices - Small Round` and
   * `…_Devices - Large Round` read `Devices - Small Round` and `Devices - Large Round`. A chooser
   * row is narrow, and full names truncated to the identical prefix (Codex Desktop). Falls back to
   * the simple names, then to the URIs, whenever the shorter labels would not be unique.
   */
  fun variantLabels(uris: List<String>): List<String> {
    val names = uris.map { uri ->
      val parsed = PreviewUri.parseOrNull(uri) ?: return@map uri
      val name = parsed.previewFqn.substringAfterLast('.')
      if (parsed.config == null) name else "$name (${parsed.config})"
    }
    if (names.toSet().size != names.size) return uris
    if (names.size < 2) return names
    val shared = names.reduce { a, b -> a.commonPrefixWith(b) }
    val cut = shared.lastIndexOf('_') + 1
    if (cut == 0) return names
    val short = names.map { it.substring(cut) }
    return if (short.all { it.isNotBlank() } && short.toSet().size == short.size) short else names
  }

  /** One picker option for the preview at [uri]. */
  fun option(
    uri: String,
    title: String,
    description: String? = null,
    thumbnailPngBase64: String? = null,
    name: String = PreviewUri.parseOrNull(uri)?.previewFqn?.substringAfterLast('.') ?: uri,
  ): ResourceOption =
    ResourceOption(
      uri = uri,
      name = name,
      title = title,
      description = description,
      mimeType = "image/png",
      thumbnailPngBase64 = thumbnailPngBase64,
      previewTarget = renderPreviewTarget(uri),
    )

  /** The thumbnail of [uri]'s last render in this session, or null; never renders. */
  fun cachedThumbnail(
    uri: String,
    activity: PreviewActivity,
    thumbnails: RenderThumbnails,
  ): String? = thumbnails.base64(activity.lastRenderPng(uri), THUMBNAIL_EDGE_PX)

  /** What came of asking. */
  sealed interface Outcome {
    /** Not an OpenAI-forms client, or it rejected the request: use today's behaviour. */
    data object Unsupported : Outcome

    data object TimedOut : Outcome

    data object Declined : Outcome

    data object Cancelled : Outcome

    /** Accepted, but the answer named no offered option. */
    data object Invalid : Outcome

    data class Picked(val uri: String) : Outcome
  }

  /** Asks [session] to pick one of [options] with a single-select resource field. */
  suspend fun pickOne(
    session: Session,
    message: String,
    title: String,
    options: List<ResourceOption>,
  ): Outcome {
    val mcp = session as? McpSession ?: return Outcome.Unsupported
    if (!mcp.supportsOpenAiForms || options.isEmpty()) return Outcome.Unsupported
    val schema =
      OpenAiForms.form(
        mapOf(FIELD to OpenAiForms.singleResourceField(options, title = title)),
        required = listOf(FIELD),
      )
    val answer =
      when (val elicitation = mcp.elicitOpenAiForm(message, schema)) {
        FormElicitation.Unsupported -> return Outcome.Unsupported
        FormElicitation.TimedOut -> return Outcome.TimedOut
        is FormElicitation.Answered -> elicitation.result
      }
    return when (answer.action) {
      ElicitResult.Action.Decline -> Outcome.Declined
      ElicitResult.Action.Cancel -> Outcome.Cancelled
      ElicitResult.Action.Accept ->
        OpenAiForms.selectedUri(answer.content, FIELD, options.map { it.uri })?.let(Outcome::Picked)
          ?: Outcome.Invalid
    }
  }

  /**
   * `render_preview preview=` matched several previews ([choices], best first). With an
   * OpenAI-forms client, ask which one with a thumbnail picker and render only that one through
   * [render]; return null for every other client (or more choices than a picker holds) so the
   * caller keeps the grid (#1199) or its plain form. The result carries the same `variantChoice`
   * block as the other paths.
   */
  suspend fun ambiguousMatch(
    session: Session,
    query: String,
    choices: List<String>,
    label: (String) -> String,
    thumbnail: (String) -> String?,
    render: (String) -> CallToolResult,
  ): CallToolResult? {
    if ((session as? McpSession)?.supportsOpenAiForms != true) return null
    if (choices.size < 2 || choices.size > MAX_OPTIONS) return null
    val options = choices.map { uri ->
      val title = label(uri)
      option(uri, title, PreviewUri.parseOrNull(uri)?.modulePath, thumbnail(uri), name = title)
    }
    fun choiceBlock(mode: String, message: String) =
      ContentBlock.Text(
        buildJsonObject {
          putJsonObject("variantChoice") {
            put("mode", mode)
            put("form", OpenAiForms.EXTENSION_ID)
            put("message", message)
            putJsonArray("choices") { choices.forEach { add(it) } }
          }
        }
          .toString()
      )
    fun rendered(uri: String, mode: String, message: String): CallToolResult {
      val result = render(uri)
      return result.copy(content = result.content + choiceBlock(mode, message))
    }
    return when (
      val outcome =
        pickOne(
          session,
          message = "Several previews match '$query'. Choose the one to render.",
          title = "Preview",
          options = options,
        )
    ) {
      Outcome.Unsupported -> null
      is Outcome.Picked ->
        rendered(
          outcome.uri,
          "elicitation",
          "The user chose this preview in a thumbnail picker: ${outcome.uri}",
        )
      Outcome.TimedOut ->
        rendered(
          choices.first(),
          "timeout",
          "The picker was not answered in time; the first match was rendered. Do not re-open " +
            "it; list the choices and let the user pick.",
        )
      Outcome.Invalid ->
        rendered(
          choices.first(),
          "text",
          "Several previews match; the first was rendered. Ask the user which one they meant " +
            "and call render_preview with that uri.",
        )
      // A cancel is not an answer (a headless client cancels without showing the picker), so it
      // renders the first match; only a decline renders nothing.
      Outcome.Cancelled ->
        rendered(choices.first(), "cancelled", DaemonMcpServer.CANCELLED_CHOICE_MESSAGE)
      Outcome.Declined ->
        CallToolResult(
          content =
            listOf(
              choiceBlock(
                "declined",
                "The user declined the preview choice, so nothing was rendered. Do not ask again " +
                  "unless they bring it up; render one by uri if they do.",
              )
            )
        )
    }
  }

  /**
   * `render_matrix choose=true`: with an OpenAI-forms client, pick a cell from its thumbnail.
   * [labels] are the cells' labels (each names its overrides) and [options] builds one option per
   * cell, in the same order, only when the picker is actually asked. Returns the `selection`
   * object, or null so the caller asks with its plain form or answers in text.
   */
  suspend fun matrixSelection(
    session: Session,
    labels: List<String>,
    options: () -> List<ResourceOption>,
  ): JsonObject? {
    if ((session as? McpSession)?.supportsOpenAiForms != true) return null
    if (labels.isEmpty() || labels.size > MAX_OPTIONS) return null
    val offered = options()
    check(offered.size == labels.size) { "one picker option per matrix cell" }
    fun selection(mode: String, message: String) = buildJsonObject {
      put("mode", mode)
      put("form", OpenAiForms.EXTENSION_ID)
      put("message", message)
      putJsonArray("choices") { labels.forEach { add(it) } }
    }
    return when (
      val outcome =
        pickOne(
          session,
          message = "Choose the rendered variant to use. Each label names its display overrides.",
          title = "Variant",
          options = offered,
        )
    ) {
      Outcome.Unsupported -> null
      is Outcome.Picked ->
        buildJsonObject {
          put("mode", "elicitation")
          put("form", OpenAiForms.EXTENSION_ID)
          put("variant", labels[offered.indexOfFirst { it.uri == outcome.uri }])
          put("uri", outcome.uri)
        }
      Outcome.TimedOut ->
        selection(
          "timeout",
          "The variant picker was not answered in time. Do not re-open it or ask again " +
            "unprompted; report the labelled choices and let the user pick when they return.",
        )
      Outcome.Invalid ->
        selection("text", "Choose one rendered variant by label: ${labels.joinToString(" | ")}")
      Outcome.Declined ->
        selection(
          "declined",
          "The user declined to choose a rendered variant. Respect that: do not ask again for " +
            "a choice, in a form or in chat, unless the user brings it up.",
        )
      Outcome.Cancelled ->
        selection(
          "cancelled",
          "The user cancelled variant selection. Do not re-ask for a choice unless the user " +
            "asks to pick one.",
        )
    }
  }
}
