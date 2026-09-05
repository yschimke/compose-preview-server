package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignUpdateEnvelopeV1
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
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionRejectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
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
  /**
   * The design's discussion, on a host that keeps one.
   *
   * Null on a host with no durable UI-builder state, where the comment routes are absent too — the
   * tools then do not appear in `tools/list` rather than appearing and failing, which is the rule
   * the whole surface follows.
   *
   * This is what makes an agent a participant rather than a tool: it can read what a designer
   * asked, answer in the same thread, and — through [AWAIT_COMMENTS] — *wait* for the next reply
   * instead of polling. The browser panel and this share one feed, so a person watching the page
   * and an agent waiting on a tool call learn about a comment at the same moment.
   */
  private val comments: ServeUiBuilderCommentStore? = null,
) {

  /** Whether this host keeps design discussions, and so whether the comment tools exist. */
  val supportsComments: Boolean
    get() = comments != null

  /** What a tool needs from the caller before it may run. Null when the name is not ours. */
  fun capabilityFor(tool: String): UiBuilderRouteCapability? =
    when (tool) {
      LIST_CATALOGS,
      LIST_DESIGNS,
      GET_DESIGN -> UiBuilderRouteCapability.READ
      AWAIT_DESIGN -> UiBuilderRouteCapability.READ
      LIST_COMMENTS,
      AWAIT_COMMENTS -> if (comments == null) null else UiBuilderRouteCapability.READ
      POST_COMMENT,
      RESOLVE_COMMENT_THREAD -> if (comments == null) null else UiBuilderRouteCapability.WRITE
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
        AWAIT_DESIGN -> return awaitDesign(args, actor)
        LIST_COMMENTS,
        AWAIT_COMMENTS,
        POST_COMMENT,
        RESOLVE_COMMENT_THREAD -> return commentTool(tool, args, actor)
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
            nodeBounds =
              outcome.nodeBounds.mapValues { (_, box) ->
                NativePreviewNodeBoundsV1(
                  x = box.x,
                  y = box.y,
                  width = box.width,
                  height = box.height,
                )
              },
            compileError = outcome.failure,
          ),
        )
    }
  }

  /**
   * Wait for somebody to change a design, rather than asking again whether they have.
   *
   * ## Why a tool and not an MCP notification
   *
   * MCP does have server-to-client notifications, and this surface deliberately cannot send one:
   * `/mcp` is a stateless JSON-RPC endpoint, `GET /mcp` — the Streamable-HTTP listening stream a
   * notification would travel on — answers 405, and `initialize` says as much by advertising
   * `resources: {"subscribe": false}`. Honouring `resources/subscribe` would mean session ids, a
   * per-session SSE stream, resumability and server-held subscription state: a stateful transport,
   * which is the property this endpoint is built not to have. A call that blocks needs none of
   * that, and it is the shape the grant flow's `poll_access` and [AWAIT_COMMENTS] already use here.
   *
   * ## What it is
   *
   * The same [UiBuilderServicePort.subscribe] the browser's `/updates` socket is built on, held for
   * one call. The reply is the released [DesignUpdateEnvelopeV1] — the identical frame that socket
   * delivers — so an agent and a designer are told the same thing in the same words, and neither
   * can learn about an edit the other does not.
   *
   * The cursor follows the service's own rule rather than a second one invented here: a
   * `afterSequence` inside the retained window is answered with the operations after it, and one
   * that is null, too far behind, or ahead of the design is answered with a whole snapshot.
   */
  private suspend fun awaitDesign(args: JsonObject, actor: AuthenticatedUiBuilderActor): String {
    val designId = args.requiredText("designId")
    val afterSequence =
      args.number("afterSequence")
        ?: throw McpRequestException(
          "`afterSequence` is required: it is the `lastSequence` you last saw"
        )
    if (afterSequence < 0) throw McpRequestException("`afterSequence` must not be negative")
    val waitSeconds =
      (args.number("waitSeconds") ?: DEFAULT_DESIGN_WAIT_SECONDS).coerceIn(
        0,
        MAX_DESIGN_WAIT_SECONDS,
      )

    val waiter = CompletableDeferred<UiBuilderServiceUpdate>()
    val subscription =
      try {
        service.subscribe(
          UiBuilderSubscriptionCall(
            actor = actor,
            designId = designId,
            afterSequence = afterSequence,
          )
        ) { update ->
          // Presence is chrome — who is looking and what they have selected — and is excluded from
          // the document, the revision and the sequence. Waking an agent for it would turn a
          // colleague moving their cursor into a tool call that returns nothing to act on.
          if (update.movesTheDocument(afterSequence)) waiter.complete(update)
        }
      } catch (rejected: UiBuilderSubscriptionRejectedException) {
        // The service's own refusal, verbatim: no such design, no read access, or too many
        // subscribers. Inventing a sentence here would describe a decision made elsewhere.
        throw McpRequestException(rejected.error.message)
      }
    val update =
      try {
        // The subscription's catch-up update is delivered before `subscribe` returns, so a design
        // that has already moved past the cursor is answered from here without entering the wait.
        //
        // Checked rather than left to `withTimeout`, because `withTimeout(0)` throws without ever
        // running its body: a caller asking `waitSeconds: 0` — the non-blocking "has anything
        // changed since my cursor", and the shape a polling loop wants — would be told nothing
        // happened while the edit it asked about sat completed in this deferred.
        if (waiter.isCompleted) waiter.await()
        else
          try {
            withTimeout(waitSeconds * 1000) { waiter.await() }
          } catch (_: TimeoutCancellationException) {
            return UI_BUILDER_JSON.encodeToString(
              DesignWaitTimeoutV1.serializer(),
              DesignWaitTimeoutV1(designId = designId, afterSequence = afterSequence),
            )
          }
      } finally {
        subscription.close()
      }
    return UI_BUILDER_JSON.encodeToString(
      DesignUpdateEnvelopeV1.serializer(),
      UiBuilderProtocolMapper.toProtocolUpdate(designId, update),
    )
  }

  /**
   * The discussion around a design: read it, join it, close a thread, or wait for the next reply.
   *
   * Not an [McpResponseEnvelopeV1], for the same reason a native render is not: the released
   * contract defines no request type for a comment, and inventing an envelope shape for a request
   * the contract does not define would be worse than being plainly outside it.
   *
   * The design is read through the service as this actor before anything is read or written, so the
   * design's own access control decides whether there is a discussion here to join — the identical
   * check the HTTP comment routes make, for the identical reason.
   */
  private suspend fun commentTool(
    tool: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): String {
    val store = comments ?: throw McpRequestException("this host keeps no design discussions")
    val designId = args.requiredText("designId")
    if (
      execute(GetSnapshotRequestV1(designId = designId, revision = null), actor)
        !is UiBuilderServiceResponse.Snapshot
    ) {
      throw McpRequestException("no design `$designId` this actor can read")
    }
    val board =
      when (tool) {
        LIST_COMMENTS -> store.readOrEmpty(designId)
        AWAIT_COMMENTS -> {
          val after = args.number("afterSequence") ?: 0
          val wait =
            (args.number("waitSeconds") ?: DEFAULT_COMMENT_WAIT_SECONDS).coerceIn(
              0,
              MAX_COMMENT_WAIT_SECONDS,
            )
          // Null is a timeout rather than a failure: the caller asked whether anything was said
          // and the answer is "not yet", which it acts on by asking again with the same cursor.
          store.awaitBoardAfter(designId, after, wait * 1000)
            ?: return UI_BUILDER_JSON.encodeToString(
              CommentWaitTimeoutV1.serializer(),
              CommentWaitTimeoutV1(designId = designId, afterSequence = after),
            )
        }
        POST_COMMENT ->
          store
            .post(
              designId,
              actor.actorId,
              CommentPostRequest(
                threadId = args.text("threadId"),
                anchor =
                  StoredCommentAnchor(
                      markId = args.text("markId"),
                      nodeId = args.text("nodeId"),
                      x = args.decimal("x"),
                      y = args.decimal("y"),
                    )
                    .takeIf { !it.isEmpty },
                body = args.requiredText("body"),
                displayName = args.text("displayName") ?: actor.actorId,
                // Declared rather than inferred, but defaulted to `agent` here: everything
                // reaching this class arrived over MCP. A tool that wanted to post as a person
                // would be posting somebody else's words under their own grant.
                authorKind = StoredComment.AUTHOR_KIND_AGENT,
              ),
            )
            .orThrow()
        RESOLVE_COMMENT_THREAD ->
          store
            .resolve(
              designId,
              actor.actorId,
              args.requiredText("threadId"),
              args["resolved"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
            .orThrow()
        else -> throw McpRequestException("unknown UI-builder comment tool '$tool'")
      }
    return UI_BUILDER_JSON.encodeToString(StoredCommentBoard.serializer(), board)
  }

  private fun CommentWriteResult.orThrow(): StoredCommentBoard =
    when (this) {
      is CommentWriteResult.Stored -> board
      is CommentWriteResult.Refused -> throw McpRequestException(reason)
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

  /** A frame fraction; `0.5` never survives [number], and an anchor is written in fractions. */
  private fun JsonObject.decimal(name: String): Float? = this[name]?.jsonPrimitive?.floatOrNull

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
    const val LIST_COMMENTS = "ui_builder_list_comments"
    const val POST_COMMENT = "ui_builder_post_comment"
    const val RESOLVE_COMMENT_THREAD = "ui_builder_resolve_comment_thread"
    const val AWAIT_COMMENTS = "ui_builder_await_comments"
    const val AWAIT_DESIGN = "ui_builder_await_design"

    /** Every tool this class answers to, in the order a session naturally uses them. */
    val TOOL_NAMES =
      listOf(LIST_CATALOGS, LIST_DESIGNS, GET_DESIGN, AWAIT_DESIGN, CREATE_DESIGN, APPLY, EXPORT)

    /** Separate because it exists only where the host can compile. */
    val NATIVE_TOOL_NAMES = listOf(RENDER_NATIVE)

    /** Separate because they exist only where the host keeps a discussion. */
    val COMMENT_TOOL_NAMES =
      listOf(LIST_COMMENTS, POST_COMMENT, RESOLVE_COMMENT_THREAD, AWAIT_COMMENTS)

    private const val DEFAULT_DESIGN_PAGE = 50

    /** How long a design watcher waits by default, and the most it may ask for. */
    private const val DEFAULT_DESIGN_WAIT_SECONDS = 25L

    private const val MAX_DESIGN_WAIT_SECONDS = 120L
    private const val MCP_CLIENT_ID = "mcp"

    /**
     * Tool declarations, built with the caller's own `tool` helper so this list has the same shape
     * as every other tool on the surface rather than a second one that drifts.
     */
    fun declarations(
      tool: (String, String, String) -> JsonObject,
      native: Boolean = false,
      comments: Boolean = false,
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
          AWAIT_DESIGN,
          "Wait for somebody else to change a design and return what they changed, rather than " +
            "asking again whether they have. Returns the moment a designer in the browser or " +
            "another agent commits an edit, as the same update frame the browser's own live " +
            "socket receives; returns a `timedOut` reply if nothing happens within `waitSeconds`, " +
            "which you answer by calling again with the same cursor. Quote as `afterSequence` the " +
            "`throughSequence` of the delta you last received, or the `lastSequence` of the last " +
            "snapshot; a cursor the server no longer retains is answered with a whole snapshot " +
            "instead of the operations you missed. `waitSeconds: 0` checks without blocking. " +
            "Nothing is lost between calls — the cursor is replayed when you call again — so " +
            "somebody merely looking at the design does not wake you, and only a committed " +
            "change does.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "afterSequence":{"type":"integer","description":"The `lastSequence` you last saw, from $GET_DESIGN or a previous wait."},
            "waitSeconds":{"type":"integer","description":"Up to $MAX_DESIGN_WAIT_SECONDS. Defaults to $DEFAULT_DESIGN_WAIT_SECONDS."}
          },"required":["designId","afterSequence"],"additionalProperties":false}
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
        if (!comments) null
        else
          tool(
            LIST_COMMENTS,
            "Read the discussion on a design: every thread, where each is pinned — a markup " +
              "stroke, a design node, or a point on the frame — whether it is resolved, and every " +
              "reply under it. `sequence` rises on each change and is the cursor to quote to " +
              "$AWAIT_COMMENTS. Comments are kept beside the design and are never part of it: no " +
              "node holds them and no export sees them.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"}
            },"required":["designId"],"additionalProperties":false}
            """,
          ),
        if (!comments) null
        else
          tool(
            POST_COMMENT,
            "Say something on a design — a reply into `threadId`, or a new thread when it is " +
              "omitted. Pin a new thread with `markId` (a stroke on the reference overlay), " +
              "`nodeId` (a node in the design), or `x`/`y` in frame fractions, so the person " +
              "reading it can see what you meant. The comment is attributed to your own grant; " +
              "you cannot post as somebody else.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "threadId":{"type":"string","description":"Reply into this thread. Omit to start one."},
              "body":{"type":"string"},
              "displayName":{"type":"string","description":"The name to show beside your actor id."},
              "markId":{"type":"string","description":"Pin to a markup stroke on the reference."},
              "nodeId":{"type":"string","description":"Pin to a design node."},
              "x":{"type":"number","description":"Pin to a point on the frame, 0..1 across."},
              "y":{"type":"number","description":"Pin to a point on the frame, 0..1 down."}
            },"required":["designId","body"],"additionalProperties":false}
            """,
          ),
        if (!comments) null
        else
          tool(
            RESOLVE_COMMENT_THREAD,
            "Close a comment thread once it is answered, or reopen one by passing " +
              "`resolved: false`. The resolution is attributed to you and is reversible.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "threadId":{"type":"string"},
              "resolved":{"type":"boolean","description":"Defaults to true."}
            },"required":["designId","threadId"],"additionalProperties":false}
            """,
          ),
        if (!comments) null
        else
          tool(
            AWAIT_COMMENTS,
            "Wait for the discussion to move past `afterSequence` and return it, rather than " +
              "polling for it. Returns as soon as anybody — a designer in the browser or another " +
              "agent — posts, resolves or deletes; returns a `timedOut` reply if nothing happens " +
              "within `waitSeconds`, which you answer by calling again with the same cursor. This " +
              "is how you hold a conversation about a design: post, wait, read, act.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "afterSequence":{"type":"integer","description":"The `sequence` you last saw. 0 for anything at all."},
              "waitSeconds":{"type":"integer","description":"Up to $MAX_COMMENT_WAIT_SECONDS. Defaults to $DEFAULT_COMMENT_WAIT_SECONDS."}
            },"required":["designId","afterSequence"],"additionalProperties":false}
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

/**
 * Nothing was said within the wait.
 *
 * Its own shape rather than an empty board, so a caller cannot read "no news" as "the discussion
 * was emptied" — the same distinction the HTTP watch route draws with a 204.
 */
@kotlinx.serialization.Serializable
internal data class CommentWaitTimeoutV1(
  val schema: String = "compose-preview/ui-builder-comment-wait/v1",
  val designId: String,
  val timedOut: Boolean = true,
  /** Echoed back, so a caller that loops can pass the same cursor without tracking it itself. */
  val afterSequence: Long,
)

/**
 * Whether this update is a change to the design, past the cursor the caller quoted.
 *
 * A snapshot always is: the service sends one when the cursor cannot be served from the retained
 * window, and the caller has to resync whether or not it names an edit they care about. A delta is
 * one only when it carries something they have not seen — subscribing at the design's current
 * sequence produces an empty catch-up delta, which is "nothing has happened", not news.
 *
 * Presence never is. It is ephemeral chrome, excluded by design from the document, the revision and
 * the durable sequence, and waking an agent because a colleague moved their cursor would spend a
 * tool call on something with nothing to act on. An outcome is an acknowledgement addressed to
 * whoever submitted it, and the edit it acknowledges arrives as its own delta.
 */
private fun UiBuilderServiceUpdate.movesTheDocument(afterSequence: Long): Boolean =
  when (this) {
    is UiBuilderServiceUpdate.Snapshot -> true
    is UiBuilderServiceUpdate.Delta -> delta.throughSequence > afterSequence
    is UiBuilderServiceUpdate.Presence,
    is UiBuilderServiceUpdate.Outcome -> false
  }

/**
 * Nobody changed the design within the wait.
 *
 * Its own shape rather than an empty delta, so a caller cannot read "no news" as "the design was
 * emptied" — the same distinction [CommentWaitTimeoutV1] draws, and the HTTP comment watch draws
 * with a 204.
 */
@kotlinx.serialization.Serializable
internal data class DesignWaitTimeoutV1(
  val schema: String = "compose-preview/ui-builder-design-wait/v1",
  val designId: String,
  val timedOut: Boolean = true,
  /** Echoed back, so a caller that loops can pass the same cursor without tracking it itself. */
  val afterSequence: Long,
)
