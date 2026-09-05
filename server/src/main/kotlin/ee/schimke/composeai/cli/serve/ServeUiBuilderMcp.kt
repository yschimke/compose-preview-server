package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.ListDesignsRequestV1
import ee.schimke.composeai.uibuilder.protocol.McpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.ProtocolRequestMapping
import ee.schimke.composeai.uibuilder.service.UiBuilderProtocolMapper
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The UI builder, reachable by an agent rather than only by a browser.
 *
 * ## Why this exists
 *
 * `ui-builder-protocol` has shipped [ee.schimke.composeai.uibuilder.protocol.McpRequestEnvelopeV1]
 * and [McpResponseEnvelopeV1] since v1, and `UiBuilderProtocolMapper`'s own KDoc names the MCP
 * envelope's `actorId` as one of the untrusted fields it refuses to trust — a contract written for
 * a transport nothing implemented. So the builder was a browser feature: a design could be
 * authored, exported and refused entirely inside a tab, and an agent holding a grant for the box
 * could render previews but could not read, edit or export a single design.
 *
 * ## What it is, deliberately
 *
 * A thin typed door onto [UiBuilderServicePort] — the same port the HTTP routes call, with the same
 * [requiredCapability] mapping, the same [UiBuilderProtocolMapper] and the same authenticated actor
 * rule. One MCP tool per protocol request, and the reply is the released [McpResponseEnvelopeV1]
 * rather than a shape invented here. Nothing about a design's semantics lives in this file: an
 * agent that asks for something the service refuses gets the service's own refusal, and a design
 * the export cannot express gets the generator's own reasons.
 *
 * That is what makes this safe to expose. The gate an agent reaches is the gate a person reaches.
 */
class ServeUiBuilderMcp(
  private val service: UiBuilderServicePort,
  /**
   * The native render lane, on a box that has one.
   *
   * Null on a host without a playground bundle — compiling a design needs a Kotlin compiler and the
   * catalog's own classpath, which not every deployment carries. The tool is then absent rather
   * than present and failing, exactly as the whole surface is on a box with no builder.
   */
  private val nativePreview: UiBuilderNativePreviewLane? = null,
) {

  /** What a tool needs from the caller before it may run. Null when the name is not ours. */
  fun capabilityFor(tool: String): UiBuilderRouteCapability? =
    when (tool) {
      LIST_CATALOGS,
      LIST_DESIGNS,
      GET_DESIGN -> UiBuilderRouteCapability.READ
      CREATE_DESIGN,
      APPLY -> UiBuilderRouteCapability.WRITE
      EXPORT -> UiBuilderRouteCapability.EXPORT
      // The same capability as an export, and for the same reason: a native render compiles and
      // runs the Kotlin an export hands back, so an actor who may not read that source may not
      // run it. Absent entirely on a host that cannot compile.
      RENDER_NATIVE -> if (nativePreview == null) null else UiBuilderRouteCapability.EXPORT
      else -> null
    }

  /**
   * Runs one tool as [actor], as the released MCP envelope.
   *
   * [callId] is the client's own JSON-RPC id, echoed back in the envelope: an agent batching calls
   * needs to tell two replies apart, and inventing an id here would defeat that.
   */
  suspend fun call(
    tool: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    callId: String,
  ): String {
    val request =
      when (tool) {
        LIST_CATALOGS -> ListCatalogsRequestV1
        LIST_DESIGNS ->
          ListDesignsRequestV1(
            cursor = args.text("cursor"),
            limit = args.number("limit")?.toInt() ?: DEFAULT_DESIGN_PAGE,
          )
        GET_DESIGN ->
          GetSnapshotRequestV1(
            designId = args.requiredText("designId"),
            revision = args.number("revision"),
          )
        CREATE_DESIGN -> createDesign(args, actor)
        APPLY -> apply(args, actor)
        EXPORT ->
          ExportDesignRequestV1(
            designId = args.requiredText("designId"),
            revision = args.number("revision"),
            format = args.exportFormat(),
          )
        RENDER_NATIVE -> return renderNative(args, actor)
        else -> throw McpRequestException("unknown UI-builder tool '$tool'")
      }
    return envelope(callId, execute(request, actor))
  }

  /**
   * A design compiled and rendered by real Compose on the host.
   *
   * The one reply here that is **not** an [McpResponseEnvelopeV1], and deliberately so: a native
   * render is not a `UiBuilderRequestV1`, the released contract defines no request type for one,
   * and inventing an envelope shape for a request the contract does not define would be worse than
   * being plainly outside it. The tool description says as much.
   *
   * The design is read back through the service as this actor, so a design's own access control
   * decides whether there is anything to render — a lane that took the document from anywhere else
   * would be a way to render a design you cannot open.
   */
  private suspend fun renderNative(args: JsonObject, actor: AuthenticatedUiBuilderActor): String {
    val lane = nativePreview ?: throw McpRequestException("this host has no native render lane")
    val designId = args.requiredText("designId")
    val snapshot =
      execute(GetSnapshotRequestV1(designId = designId, revision = args.number("revision")), actor)
        as? UiBuilderServiceResponse.Snapshot
        ?: throw McpRequestException("no design `$designId` this actor can read")
    val document = snapshot.snapshot.state.document
    return when (val outcome = lane.render(document)) {
      is UiBuilderNativePreviewOutcome.Refused ->
        UI_BUILDER_JSON.encodeToString(
          NativePreviewRefusalV1.serializer(),
          NativePreviewRefusalV1(code = outcome.code, reasons = outcome.reasons),
        )
      is UiBuilderNativePreviewOutcome.Rendered ->
        UI_BUILDER_JSON.encodeToString(
          NativePreviewResultV1.serializer(),
          NativePreviewResultV1(
            designId = designId,
            revision = document.revision,
            previewId = outcome.response.previewId,
            previewToken = outcome.response.previewToken,
            previewUrl = outcome.response.previewUrl,
            imageBase64 = outcome.response.image,
            taggedNodeIds = outcome.taggedNodeIds,
            compileError = outcome.response.exception,
          ),
        )
    }
  }

  /**
   * A new design, either from a document the caller wrote or copied from one this box already has.
   *
   * There is no third option, and the absence is deliberate. A design's `catalogPin` names a
   * catalog revision and a capability digest that the service checks against the live catalog, so a
   * starter document assembled here would either carry values invented in this file — which the
   * service would reject — or reach into the browser's own bootstrap, which fetches the checked-in
   * fixture and patches three fields from the catalog it just listed. Copying an existing design
   * gives an agent a pin that is real by construction.
   */
  private suspend fun createDesign(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): UiBuilderRequestV1 {
    val designId = args.requiredText("designId")
    val explicit = args["document"] as? JsonObject
    val source = args.text("fromDesignId")
    if ((explicit == null) == (source == null)) {
      throw McpRequestException("pass exactly one of `document` or `fromDesignId`")
    }
    val document =
      if (explicit != null) {
        try {
          UI_BUILDER_JSON.decodeFromJsonElement(DesignDocumentV1.serializer(), explicit)
        } catch (e: SerializationException) {
          throw McpRequestException("`document` is not a DesignDocumentV1: ${e.message}")
        }
      } else {
        val snapshot =
          execute(GetSnapshotRequestV1(designId = source!!, revision = null), actor)
            as? UiBuilderServiceResponse.Snapshot
            ?: throw McpRequestException("`fromDesignId` names no design this actor can read")
        snapshot.snapshot.state.document
      }
    return CreateDesignRequestV1(
      document.copy(id = designId, revision = 0, title = args.text("title") ?: document.title)
    )
  }

  private fun apply(args: JsonObject, actor: AuthenticatedUiBuilderActor): UiBuilderRequestV1 {
    val operations =
      (args["operations"] as? JsonArray)
        ?: throw McpRequestException("`operations` must be an array of design mutations")
    val mutations =
      try {
        operations.map { UI_BUILDER_JSON.decodeFromJsonElement(DesignMutationV1.serializer(), it) }
      } catch (e: SerializationException) {
        throw McpRequestException(
          "`operations` holds something that is not a mutation: ${e.message}"
        )
      }
    return ApplyOperationRequestV1(
      DesignCommandV1(
        designId = args.requiredText("designId"),
        operationId = args.requiredText("operationId"),
        // Not read from the arguments. `UiBuilderProtocolMapper` rejects a command whose nested
        // actor is not the authenticated one, and the whole point of that check is that a caller
        // does not get to choose. Filling it from the grant means the check passes because the
        // claim is true, rather than because nobody made one.
        actorId = actor.actorId,
        clientId = args.text("clientId") ?: MCP_CLIENT_ID,
        baseRevision =
          args.number("baseRevision")
            ?: throw McpRequestException(
              "`baseRevision` is required: it is how a concurrent edit is detected"
            ),
        operations = mutations,
      )
    )
  }

  private suspend fun execute(
    request: UiBuilderRequestV1,
    actor: AuthenticatedUiBuilderActor,
  ): UiBuilderServiceResponse =
    when (val mapping = UiBuilderProtocolMapper.toServiceCall(actor, request)) {
      is ProtocolRequestMapping.Mapped ->
        try {
          service.execute(mapping.call)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          UiBuilderServiceResponse.Error(
            ee.schimke.composeai.uibuilder.service.UiBuilderServiceError(
              ServiceErrorCodeV1.INTERNAL,
              "UI-builder service failed",
              retryable = true,
            )
          )
        }
      is ProtocolRequestMapping.Rejected -> UiBuilderServiceResponse.Error(mapping.error)
    }

  private fun envelope(callId: String, response: UiBuilderServiceResponse): String =
    UI_BUILDER_JSON.encodeToString(
      McpResponseEnvelopeV1.serializer(),
      McpResponseEnvelopeV1(
        callId = callId,
        response = UiBuilderProtocolMapper.toProtocolResponse(response),
      ),
    )

  private fun JsonObject.text(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

  private fun JsonObject.requiredText(name: String): String =
    text(name) ?: throw McpRequestException("`$name` is required")

  private fun JsonObject.number(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

  private fun JsonObject.exportFormat(): ExportFormatV1 {
    val requested = text("format") ?: return ExportFormatV1.COMPOSE
    return ExportFormatV1.entries.firstOrNull { it.name.equals(requested, ignoreCase = true) }
      ?: throw McpRequestException(
        "unknown export format '$requested'; this server knows " +
          ExportFormatV1.entries.joinToString(", ") { it.name.lowercase() }
      )
  }

  companion object {
    const val LIST_CATALOGS = "ui_builder_list_catalogs"
    const val LIST_DESIGNS = "ui_builder_list_designs"
    const val GET_DESIGN = "ui_builder_get_design"
    const val CREATE_DESIGN = "ui_builder_create_design"
    const val APPLY = "ui_builder_apply"
    const val EXPORT = "ui_builder_export"
    const val RENDER_NATIVE = "ui_builder_render_native"

    /** Every tool this class answers to, in the order a session naturally uses them. */
    val TOOL_NAMES = listOf(LIST_CATALOGS, LIST_DESIGNS, GET_DESIGN, CREATE_DESIGN, APPLY, EXPORT)

    /** Separate because it exists only where the host can compile. */
    val NATIVE_TOOL_NAMES = listOf(RENDER_NATIVE)

    private const val DEFAULT_DESIGN_PAGE = 50
    private const val MCP_CLIENT_ID = "mcp"

    /**
     * Tool declarations, built with the caller's own `tool` helper so this list has the same shape
     * as every other tool on the surface rather than a second one that drifts.
     */
    fun declarations(
      tool: (String, String, String) -> JsonObject,
      native: Boolean = false,
    ): List<JsonObject> =
      listOfNotNull(
        tool(
          LIST_CATALOGS,
          "List the component catalogs a UI-builder design can pin to, with each catalog's " +
            "components, their properties and slots, and which export formats it supports. Start " +
            "here: a design's `catalogPin` must name a revision this server actually serves.",
          """{"type":"object","properties":{},"additionalProperties":false}""",
        ),
        tool(
          LIST_DESIGNS,
          "List the UI-builder designs on this server, newest first, with the cursor to continue.",
          """
          {"type":"object","properties":{
            "cursor":{"type":"string","description":"Continue a previous page."},
            "limit":{"type":"integer","description":"Designs per page. Defaults to $DEFAULT_DESIGN_PAGE."}
          },"additionalProperties":false}
          """,
        ),
        tool(
          GET_DESIGN,
          "Read one design: its whole document — nodes, slots, properties, modifiers, state " +
            "variables and catalog pin — plus the revision to quote as `baseRevision` when " +
            "editing it.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "revision":{"type":"integer","description":"A past revision. Omit for the current one."}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
        tool(
          CREATE_DESIGN,
          "Create a design, either from a whole `document` you supply or by copying an existing " +
            "design named by `fromDesignId`. Copying is usually right: a document's `catalogPin` " +
            "must match a catalog revision this server serves, and a copy carries one that does.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string","description":"The id for the new design."},
            "title":{"type":"string"},
            "document":{"type":"object","description":"A whole DesignDocumentV1."},
            "fromDesignId":{"type":"string","description":"Copy this design's document instead."}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
        tool(
          APPLY,
          "Apply design mutations — insertNode, setProperty, deleteNode, moveNode and the rest of " +
            "DesignMutationV1 — as one operation. `baseRevision` is the revision you read, and a " +
            "mismatch is reported rather than merged, so a concurrent edit cannot be lost. This " +
            "is how an agent adds a scaffold, fills its slots and sets modifiers.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "operationId":{"type":"string","description":"Your id for this operation; makes a retry idempotent."},
            "baseRevision":{"type":"integer","description":"The revision these mutations were written against."},
            "clientId":{"type":"string"},
            "operations":{"type":"array","items":{"type":"object"},"description":"DesignMutationV1 objects."}
          },"required":["designId","operationId","baseRevision","operations"],"additionalProperties":false}
          """,
        ),
        tool(
          EXPORT,
          "Export a design. `compose` returns the Kotlin the generator writes, or — when the " +
            "design holds something it cannot express — diagnostics naming each reason. This is " +
            "the same gate the browser's code pane shows, so an agent and a designer get the " +
            "same answer about the same design.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "revision":{"type":"integer"},
            "format":{"type":"string","description":"compose, svg or png. Defaults to compose."}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
        if (!native) null
        else
          tool(
            RENDER_NATIVE,
            "Compile a design and render it with real Compose on this host, rather than in the " +
              "browser's Wasm canvas — the way to see what a design looks like on Android. " +
              "Returns the first frame, the token the live frame stream is opened with, and the " +
              "design node ids the render is tagged with, so `get_preview_data` can report each " +
              "node's bounds and a client can put selectable regions over the image. The reply " +
              "is not an McpResponseEnvelopeV1: the released contract defines no request type " +
              "for a native render.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "revision":{"type":"integer","description":"A past revision. Omit for the current one."}
            },"required":["designId"],"additionalProperties":false}
            """,
          ),
      )
  }
}
